package okay.audit

import java.nio.file.{Files, Path, Paths}

/** `okay.audit.Export <sbt-build-root> <out-dir>` — the inputs of an audit, from any sbt build, without a plugin and
  * without changing the build: each project's class directory and runtime classpath, read with sbt's own `export`
  * (aggregation off, so each command prints exactly one line), then `<out-dir>/projects.tsv` (name, platform, class
  * directory, classpath) and `<out-dir>/audit.json`, a manifest with every JVM project `untracked` beside its class
  * directory, the dependency jars one `untracked` row and the Scala library `runtime` — set the layers, then run
  * `okay.audit.Main --manifest`. sbt must be on the PATH and the build must compile (export compiles it, into its
  * own `target/`). specs/audit-cli-standalone.md, "Getting the inputs". Like Main, the module's own I/O. */
object Export:
  final case class Project(name: String, classDirectory: String, classpath: Vector[String]):
    /** `jvm`, or `js`/`native`, whose output is no class file the check reads */
    def platform: String =
      if classpath.exists(_.contains("scalajs-library")) then "js"
      else if classpath.exists(p => p.contains("nativelib_native") || p.contains("scala3lib_native")) then "native"
      else "jvm"

  /** the project names of the build's first `In file:` section of `sbt projects` */
  def projects(lines: Seq[String]): Vector[String] =
    val Name = """\[info\]\s+[* ]\s*(\S+)\s*""".r
    lines.iterator.dropWhile(!_.contains("In file:")).drop(1).takeWhile(l => !l.contains("In file:")).collect {
      case Name(n) => n
    }.toVector

  /** the values sbt's `export` printed — every line that is not sbt's own log (`[info]`, `[warn]`, `[error]`, ...) */
  def values(lines: Seq[String]): Vector[String] =
    lines.iterator.map(_.stripTrailing).filter(l => l.nonEmpty && !l.startsWith("[")).toVector

  /** the commands: aggregation off, then per project its class directory and runtime classpath */
  def commands(names: Seq[String]): Vector[String] =
    "set Global / aggregate := false" +: names.toVector.flatMap(n =>
      Vector(s"export $n/Compile/classDirectory", s"export $n/Runtime/fullClasspath"))

  /** the values in command order, two per project; a count that does not match is refused (a project failed) */
  def read(names: Seq[String], printed: Vector[String]): Vector[Project] =
    if printed.size != 2 * names.size then
      throw Audit.Refused(s"export: ${names.size} projects need ${2 * names.size} values, sbt printed " +
        s"${printed.size} — a project did not compile or resolve; read sbt's log")
    names.toVector.zip(printed.grouped(2).toVector).map { (n, v) =>
      Project(n, v(0), v(1).split(java.io.File.pathSeparatorChar).toVector.filter(_.nonEmpty))
    }

  /** the manifest a person finishes: own JVM projects untracked, dependency jars one untracked row, the Scala
    * library runtime. A class directory of another project on a classpath is that project's, not a dependency */
  def skeleton(ps: Vector[Project]): String =
    val jvm = ps.filter(p => p.platform == "jvm" && Files.isDirectory(Paths.get(p.classDirectory)))
    val dirs = ps.map(_.classDirectory).toSet
    def scalaLibrary(p: String) =
      Paths.get(p).getFileName.toString.matches("scala(3)?-library.*\\.jar|scala-reflect.*\\.jar|tasty-core.*\\.jar")
    val jars = jvm.flatMap(_.classpath).filterNot(dirs).distinct
    def q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    def row(name: String, layer: String, paths: Vector[String]) =
      s"""    {"name": ${q(name)}, "layer": ${q(layer)}, "paths": [${paths.map(q).mkString(", ")}]}"""
    val rows = jvm.map(p => row(p.name, "untracked", Vector(p.classDirectory))) ++
      Option(jars.filterNot(scalaLibrary)).filter(_.nonEmpty).map(row("dependencies", "untracked", _)) ++
      Option(jars.filter(scalaLibrary)).filter(_.nonEmpty).map(row("scala-library", "runtime", _))
    s"""{\n  "rules": "default",\n  "modules": [\n${rows.mkString(",\n")}\n  ]\n}\n"""

  def tsv(ps: Vector[Project]): String =
    ps.map(p => s"${p.name}\t${p.platform}\t${p.classDirectory}\t${p.classpath.mkString(java.io.File.pathSeparator)}")
      .mkString("", "\n", "\n")

  private def sbt(root: Path, args: Seq[String]): Vector[String] =
    val cmd = Vector("sbt", "-Dsbt.log.noformat=true", "-Dsbt.supershell=false", "-batch") ++ args
    val p = ProcessBuilder(cmd*).directory(root.toFile).redirectErrorStream(true).start()
    val out = String(p.getInputStream.readAllBytes(), "UTF-8").linesIterator.toVector
    if p.waitFor() != 0 then
      throw Audit.Refused(s"export: sbt ${args.headOption.getOrElse("")} … failed:\n${out.takeRight(20).mkString("\n")}")
    out

  def run(root: Path, out: Path): Vector[Project] =
    val names = projects(sbt(root, Vector("projects")))
    if names.isEmpty then throw Audit.Refused(s"export: no projects in $root")
    val ps = read(names, values(sbt(root, commands(names))))
    Files.createDirectories(out)
    Files.writeString(out.resolve("projects.tsv"), tsv(ps))
    Files.writeString(out.resolve("audit.json"), skeleton(ps))
    ps

  def main(args: Array[String]): Unit =
    val code =
      try
        args.toList match
          case root :: out :: Nil =>
            val ps = run(Paths.get(root).toAbsolutePath.normalize, Paths.get(out).toAbsolutePath.normalize)
            System.out.println(s"export: ${ps.size} projects (${ps.count(_.platform == "jvm")} JVM) -> $out")
            0
          case _ => throw Audit.Refused("usage: okay.audit.Export <sbt-build-root> <out-dir>")
      catch
        case r: Audit.Refused =>
          System.err.println(s"audit: refused — ${r.getMessage}")
          2
    System.exit(code)
