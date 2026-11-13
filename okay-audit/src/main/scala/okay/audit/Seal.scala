package okay.audit

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

/** A SEAL: a build that proves it was checked (okay-safe's seal and launcher, generalized). It records the check's
  * version, the configuration's hash (`Boundary.canonical`), the result, the JVM options the deployment must run
  * with, fields of the caller's own (a compiler's version and flags, an entry point), and every file of the
  * deployable by its SHA-256. A launcher reads it at start and refuses to run what it does not match: `verify`.
  *
  * Text, one line an entry: `okay-audit-seal 1`, then `key value` lines in the order given, then
  * `file <sha-256> <path>` lines sorted by path.
  *
  * NOT SIGNED. A seal proves the deployable is the one the check passed and its settings the recorded ones; it does
  * not prove who wrote it — anyone can recompute the hashes over other classes and write "pass". A signature by the
  * builder's key, its public half pinned in the launcher, would bind it to the builder. */
final case class Seal(fields: Vector[(String, String)], files: Vector[(String, String)]):
  def apply(key: String): Either[String, String] = fields.collectFirst { case (`key`, v) => v }
    .toRight(s"the seal records no $key")

  def text: String =
    (fields.map((k, v) => s"$k $v") ++ files.sortBy(_._1).map((p, sha) => s"file $sha $p")).mkString("\n") + "\n"

object Seal:
  val format = "1"
  /** the check's version, recorded in a seal: raised when what the check finds changes */
  val checker = "okay-audit 2"
  /** where a seal sits in a jar */
  val Entry = "META-INF/okay-audit.seal"

  def sha256(bytes: Array[Byte]): String = HexFormat.of.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  /** the seal of a passed (or failed) report: `files` are the deployable's, `jvm` the options it must run with
    * (by default the launcher options the report recorded), `extra` the caller's own fields */
  def of(report: Report, boundary: Boundary, files: Vector[(String, String)],
         extra: Vector[(String, String)] = Vector.empty, jvm: Vector[String] = Vector.empty): Seal =
    val classes = report.inputs.size
    val result = if report.passed then s"pass: $classes inputs, no finding"
      else s"fail: ${report.findings.size} finding(s)"
    val options = if jvm.nonEmpty then jvm else report.jpms.launcherOptions
    Seal(Vector("okay-audit-seal" -> format, "checker" -> checker,
      "config" -> sha256(boundary.canonical.getBytes("UTF-8")), "result" -> result, "jvm" -> options.mkString(" ")) ++
      extra, files.sortBy(_._1))

  /** every file under a directory (by its path there, with `/`) or in a jar (by its entry), with its SHA-256;
    * `skip`: names left out (the seal itself, a manifest written after it) */
  def files(path: Path, skip: String => Boolean = _ => false): Vector[(String, String)] =
    if Files.isDirectory(path) then
      val s = Files.walk(path)
      try s.iterator.asScala.filter(Files.isRegularFile(_)).toVector
        .map(f => path.relativize(f).toString.replace('\\', '/') -> f).filterNot(x => skip(x._1))
        .map((n, f) => n -> sha256(Files.readAllBytes(f))).sortBy(_._1)
      finally s.close()
    else
      val jar = JarFile(path.toFile)
      try jar.entries.asScala.filterNot(_.isDirectory).map(_.getName).filterNot(skip).toVector
        .map(n => n -> sha256(jar.getInputStream(jar.getEntry(n)).readAllBytes)).sortBy(_._1)
      finally jar.close()

  def parse(text: String): Seal =
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).map(l => l.span(_ != ' ')).toVector
    Seal(lines.collect { case (k, v) if k != "file" => k -> v.trim },
      lines.collect { case ("file", v) => v.trim.span(_ != ' ') }.map((sha, p) => p.trim -> sha))

  /** the seal in a jar, or why there is none */
  def read(jar: Path, entry: String = Entry): Either[String, Seal] =
    val j = JarFile(jar.toFile)
    try Option(j.getEntry(entry)).map(e => parse(String(j.getInputStream(e).readAllBytes, "UTF-8")))
      .toRight(s"no seal in $jar: it was not built by a sealing check")
    finally j.close()

  /** every present file sealed and as it was; none of the seal's missing; `refused` names a file that may not be
    * there at all (a class in a trusted package, say) */
  def verifyFiles(seal: Seal, present: Vector[(String, String)],
                  refused: String => Option[String] = _ => None): Either[String, Unit] =
    val inSeal = seal.files.toMap
    val here = present.toMap
    present.iterator.flatMap((n, _) => refused(n)).nextOption()
      .orElse(present.collectFirst { case (n, _) if !inSeal.contains(n) => s"$n is not in the seal: added after the check" })
      .orElse(present.collectFirst { case (n, sha) if inSeal(n) != sha => s"$n does not match its seal: changed after the check" })
      .orElse(seal.files.collectFirst { case (n, _) if !here.contains(n) => s"$n of the seal is not here" })
      .toLeft(())

  /** the seal's own format, and the check passed */
  def verifyResult(seal: Seal): Either[String, Unit] =
    for
      f <- seal("okay-audit-seal")
      _ <- Either.cond(f == format, (), s"a seal of format $f, not $format")
      _ <- seal("checker")
      _ <- seal("config")
      result <- seal("result")
      _ <- Either.cond(result.startsWith("pass"), (), s"the check did not pass: $result")
    yield ()

  /** every JVM option the seal records is one this JVM was started with */
  def verifyJvm(seal: Seal, arguments: Vector[String] = jvmArguments): Either[String, Unit] =
    seal("jvm").flatMap(_.split(' ').filter(_.nonEmpty).find(o => !arguments.contains(o))
      .map(o => s"the JVM was not started with $o, which the seal requires").toLeft(()))

  /** what a launcher asks before it starts anything: the result, the files, the JVM's options */
  def verify(seal: Seal, present: Vector[(String, String)], arguments: Vector[String] = jvmArguments,
             refused: String => Option[String] = _ => None): Either[String, Unit] =
    for
      _ <- verifyResult(seal)
      _ <- verifyFiles(seal, present, refused)
      _ <- verifyJvm(seal, arguments)
    yield ()

  def jvmArguments: Vector[String] = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toVector
