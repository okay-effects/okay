package okay.audit

import java.io.{ByteArrayInputStream, DataInputStream}
import java.nio.file.{Files, Path}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*

/** one reference a class file makes to something outside itself: the owner
  * class, the member and its descriptor (empty for a bare class reference);
  * `Native` is the class's OWN method carrying `ACC_NATIVE` — not a reference,
  * but the same kind of reach past the boundary, so it is reported as one */
final case class Ref(from: String, kind: Ref.Kind, owner: String, name: String, descriptor: String):
  /** `java.lang.System#currentTimeMillis` — how a rule and a report name it */
  def member: String = if name.isEmpty then owner else s"$owner#$name"

object Ref:
  enum Kind:
    case Class, Field, Method, InterfaceMethod, Native

/** one instruction of a method that names something: `op` is the JVM opcode
  * (getstatic 0xb2 … invokeinterface 0xb9, new 0xbb; checkcast, instanceof, anewarray, multianewarray and an `ldc`
  * of a class name a class too), `line` the source line of the LineNumberTable (0 when the class carries none),
  * `text` the string constant an `ldc` pushed just before it (a reflective name: `getDeclaredField("x$lzy1")`) */
final case class Site(op: Int, line: Int, ref: Ref, text: String = ""):
  /** a call or a field access — what the rules on code read; the class-naming sites only place a reference */
  def isMember: Boolean = op >= Site.GetStatic && op <= 0xb9

object Site:
  val GetStatic = 0xb2; val PutStatic = 0xb3; val GetField = 0xb4; val PutField = 0xb5
  val New = 0xbb
  val Ldc = 0x12; val LdcW = 0x13; val CheckCast = 0xc0; val InstanceOf = 0xc1; val ANewArray = 0xbd
  val MultiANewArray = 0xc5

final case class FieldInfo(access: Int, name: String, descriptor: String, signature: Option[String]):
  def isStatic: Boolean = (access & 0x0008) != 0
  def isFinal: Boolean = (access & 0x0010) != 0

final case class MethodInfo(access: Int, name: String, descriptor: String, signature: Option[String], sites: Vector[Site]):
  def isStatic: Boolean = (access & 0x0008) != 0

/** a class file as this module reads it: its name and supertypes, its fields
  * and methods (with their generic `Signature`s and, when asked, the sites of
  * their code), and every reference its constant pool makes; `scala`: compiled by Scala (a `TASTY` or `ScalaSig`
  * attribute) — its `Object` may be an erased union, opaque type, boxed type argument or `Any`, which the bytecode
  * alone cannot tell apart */
final case class ClassInfo(name: String, access: Int, superName: Option[String], interfaces: Vector[String],
                           signature: Option[String], sourceFile: Option[String], fields: Vector[FieldInfo],
                           methods: Vector[MethodInfo], refs: Vector[Ref], scala: Boolean = false):
  def isInterface: Boolean = (access & 0x0200) != 0
  def isFinal: Boolean = (access & 0x0010) != 0
  def isAbstract: Boolean = (access & 0x0400) != 0
  def supers: Vector[String] = superName.toVector ++ interfaces
  /** a Scala `object`'s class: `X$` holding its own `static final MODULE$` */
  def isModule: Boolean = name.endsWith("$") && fields.exists(f => f.name == "MODULE$" && f.isStatic)

/** the class-file reader: JVMS §4 — the constant pool, the access flags, the
  * fields' and methods' `Signature` attributes, and (with `code`) each method's
  * instructions that name a field, a method or a class, with their lines. Not
  * `java.lang.classfile` — dotty 3.9 cannot load its sealed model types
  * (specs/core-gaps.md, stage 4), and the format is a few screens. The reader
  * of `TestInlineBudget` extended from "a method's code length" to "everything
  * the class refers to, and where". */
object Scan:

  def classFile(bytes: Array[Byte]): Vector[Ref] = parse(bytes, code = false).refs

  def parse(bytes: Array[Byte], code: Boolean = true): ClassInfo =
    val in = DataInputStream(ByteArrayInputStream(bytes))
    if in.readInt() != 0xCAFEBABE then throw IllegalArgumentException("not a class file")
    in.readUnsignedShort(); in.readUnsignedShort()                      // minor, major
    val count = in.readUnsignedShort()
    val tags = new Array[Int](count)
    val utf8 = new Array[String](count)
    val a = new Array[Int](count)                                       // class_index / name_index
    val b = new Array[Int](count)                                       // name_and_type_index / descriptor_index
    var i = 1
    while i < count do
      val tag = in.readUnsignedByte()
      tags(i) = tag
      tag match
        case 1 => utf8(i) = in.readUTF()
        case 7 | 8 | 16 | 19 | 20 => a(i) = in.readUnsignedShort()     // Class, String, MethodType, Module, Package
        case 9 | 10 | 11 | 12 | 17 | 18 => a(i) = in.readUnsignedShort(); b(i) = in.readUnsignedShort()
        case 3 | 4 => in.skipNBytes(4)
        case 5 | 6 => in.skipNBytes(8); i += 1                          // a long or double takes two slots
        case 15 => in.readUnsignedByte(); a(i) = in.readUnsignedShort() // MethodHandle: kind, reference
        case other => throw IllegalArgumentException(s"constant pool tag $other unknown")
      i += 1
    def className(idx: Int): String = utf8(a(idx)).replace('/', '.')
    val access = in.readUnsignedShort()
    val self = className(in.readUnsignedShort())
    val superIdx = in.readUnsignedShort()
    val superName = if superIdx == 0 then None else Some(className(superIdx))
    val interfaces = Vector.fill(in.readUnsignedShort())(className(in.readUnsignedShort()))

    var scalaAttr = false
    /** a member's or the class's attributes: its Signature, its SourceFile, and its Code read by `onCode` */
    def attributes(onCode: Array[Byte] => Unit = _ => ()): (Option[String], Option[String]) =
      var signature = Option.empty[String]
      var source = Option.empty[String]
      for _ <- 0 until in.readUnsignedShort() do
        val name = utf8(in.readUnsignedShort())
        val length = in.readInt() & 0xFFFFFFFFL
        name match
          case "Signature" => signature = Some(utf8(in.readUnsignedShort()))
          case "SourceFile" => source = Some(utf8(in.readUnsignedShort()))
          case "Code" => onCode(in.readNBytes(length.toInt))
          case "TASTY" | "ScalaSig" | "Scala" => scalaAttr = true; in.skipNBytes(length)
          case _ => in.skipNBytes(length)
      (signature, source)

    def ref(idx: Int): Ref =
      val owner = className(a(idx))
      val nt = b(idx)
      val kind = tags(idx) match
        case 9 => Ref.Kind.Field
        case 10 => Ref.Kind.Method
        case _ => Ref.Kind.InterfaceMethod
      Ref(self, kind, owner, utf8(a(nt)), utf8(b(nt)))

    val fields = Vector.fill(in.readUnsignedShort()) {
      val acc = in.readUnsignedShort()
      val name = utf8(in.readUnsignedShort())
      val desc = utf8(in.readUnsignedShort())
      FieldInfo(acc, name, desc, attributes()._1)
    }
    val natives = Vector.newBuilder[Ref]
    val methods = Vector.fill(in.readUnsignedShort()) {
      val acc = in.readUnsignedShort()
      val name = utf8(in.readUnsignedShort())
      val desc = utf8(in.readUnsignedShort())
      if (acc & 0x0100) != 0 then natives += Ref(self, Ref.Kind.Native, self, name, desc)
      var sites = Vector.empty[Site]
      val (sig, _) = attributes(body => if code then sites = this.sites(body, tags, a, ref,
        idx => Ref(self, Ref.Kind.Class, className(idx), "", ""), utf8))
      MethodInfo(acc, name, desc, sig, sites)
    }
    val (classSig, source) = attributes()
    val refs = Vector.newBuilder[Ref]
    // a class the pool names only through a member reference is covered by
    // that reference; a bare CONSTANT_Class (new, checkcast, classOf) is its own
    var k = 1
    while k < count do
      tags(k) match
        case 7 if utf8(a(k)).charAt(0) != '[' =>
          val owner = className(k)
          if owner != self then refs += Ref(self, Ref.Kind.Class, owner, "", "")
        case 9 | 10 | 11 =>
          val r = ref(k)
          if r.owner != self then refs += r
        case _ =>
      k += 1
    ClassInfo(self, access, superName, interfaces, classSig, source, fields, methods,
      (refs.result() ++ natives.result()).distinct, scalaAttr)

  /** the instructions of one Code attribute that name a field, a method or a class (`new`), with their lines */
  private def sites(body: Array[Byte], tags: Array[Int], a: Array[Int], ref: Int => Ref, classRef: Int => Ref,
                    utf8: Array[String]): Vector[Site] =
    val in = DataInputStream(ByteArrayInputStream(body))
    in.skipNBytes(4)                                                     // max_stack, max_locals
    val length = in.readInt()
    val code = in.readNBytes(length)
    in.skipNBytes(8L * in.readUnsignedShort())                         // exception table
    val lines = Vector.newBuilder[(Int, Int)]
    for _ <- 0 until in.readUnsignedShort() do
      val name = utf8(in.readUnsignedShort())
      val len = in.readInt() & 0xFFFFFFFFL
      if name == "LineNumberTable" then
        for _ <- 0 until in.readUnsignedShort() do lines += ((in.readUnsignedShort(), in.readUnsignedShort()))
      else in.skipNBytes(len)
    val table = lines.result().sortBy(_._1)
    def lineAt(pc: Int): Int = table.takeWhile(_._1 <= pc).lastOption.fold(0)(_._2)
    def u2(at: Int): Int = ((code(at) & 0xff) << 8) | (code(at + 1) & 0xff)
    def s4(at: Int): Int = ((code(at) & 0xff) << 24) | ((code(at + 1) & 0xff) << 16) | ((code(at + 2) & 0xff) << 8) |
      (code(at + 3) & 0xff)
    val out = Vector.newBuilder[Site]
    // the string an `ldc` pushed, kept for the one instruction right after it
    var pushed = ""
    def utf8At(idx: Int) = if idx > 0 && idx < tags.length && tags(idx) == 8 then Option(utf8(a(idx))) else None
    var pc = 0
    while pc < length do
      val op = code(pc) & 0xff
      val size = op match
        case 0x10 | 0x12 | 0x15 | 0x16 | 0x17 | 0x18 | 0x19 | 0x36 | 0x37 | 0x38 | 0x39 | 0x3a | 0xa9 | 0xbc => 2
        case 0x11 | 0x13 | 0x14 | 0x84 | 0xbb | 0xbd | 0xc0 | 0xc1 | 0xc6 | 0xc7 => 3
        case o if o >= 0x99 && o <= 0xa8 => 3
        case o if o >= 0xb2 && o <= 0xb8 => 3
        case 0xb9 | 0xba | 0xc8 | 0xc9 => 5
        case 0xc5 => 4
        case 0xc4 => if (code(pc + 1) & 0xff) == 0x84 then 6 else 4
        case 0xaa =>
          val at = (pc + 4) & ~3
          val low = s4(at + 4); val high = s4(at + 8)
          at - pc + 12 + 4 * (high - low + 1)
        case 0xab =>
          val at = (pc + 4) & ~3
          at - pc + 8 + 8 * s4(at + 4)
        case _ => 1
      var next = ""
      if (op >= 0xb2 && op <= 0xb9) then
        val idx = u2(pc + 1)
        if tags(idx) == 9 || tags(idx) == 10 || tags(idx) == 11 then out += Site(op, lineAt(pc), ref(idx), pushed)
      else if op == Site.New || op == Site.CheckCast || op == Site.InstanceOf || op == Site.ANewArray ||
          op == Site.MultiANewArray then
        val idx = u2(pc + 1)
        if tags(idx) == 7 && utf8(a(idx)).charAt(0) != '[' then out += Site(op, lineAt(pc), classRef(idx))
      else if op == Site.Ldc || op == Site.LdcW then
        val idx = if op == Site.Ldc then code(pc + 1) & 0xff else u2(pc + 1)
        if tags(idx) == 7 && utf8(a(idx)).charAt(0) != '[' then out += Site(op, lineAt(pc), classRef(idx))
        else utf8At(idx).foreach(next = _)
      pushed = next
      pc += size
    out.result()

  def directory(dir: Path): Vector[Ref] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val files = Files.walk(dir).iterator().asScala.filter(p => p.toString.endsWith(".class")).toVector.sortBy(_.toString)
      files.flatMap(p => classFile(Files.readAllBytes(p)))

  def jar(path: Path): Vector[Ref] =
    if !Files.isRegularFile(path) then Vector.empty
    else
      val jf = JarFile(path.toFile)
      try
        jf.entries().asScala.toVector.filter(e => e.getName.endsWith(".class") && !e.getName.startsWith("META-INF/"))
          .sortBy(_.getName)
          .flatMap(e => classFile(jf.getInputStream(e).readAllBytes()))
      finally jf.close()

  def path(p: Path): Vector[Ref] = if Files.isDirectory(p) then directory(p) else jar(p)

  /** every class of a directory or jar, parsed (`code`: with its methods' sites), in path order */
  def classes(p: Path, code: Boolean): Vector[ClassInfo] =
    if Files.isDirectory(p) then
      Files.walk(p).iterator().asScala.filter(f => f.toString.endsWith(".class") && !f.endsWith("module-info.class"))
        .toVector.sortBy(_.toString).map(f => parse(Files.readAllBytes(f), code))
    else if Files.isRegularFile(p) then
      val jf = JarFile(p.toFile)
      try
        jf.entries().asScala.toVector.filter(e => e.getName.endsWith(".class") && !e.getName.startsWith("META-INF/") &&
            e.getName != "module-info.class").sortBy(_.getName)
          .map(e => parse(jf.getInputStream(e).readAllBytes(), code))
      finally jf.close()
    else Vector.empty

  /** the names of the classes of a directory or jar, without reading them */
  def names(p: Path): Vector[String] =
    def name(entry: String) = entry.stripSuffix(".class").replace('/', '.')
    if Files.isDirectory(p) then
      val s = Files.walk(p)
      try s.iterator.asScala.filter(_.toString.endsWith(".class")).map(f => name(p.relativize(f).toString
        .replace('\\', '/'))).filterNot(_ == "module-info").toVector
      finally s.close()
    else if Files.isRegularFile(p) then
      val jf = JarFile(p.toFile)
      try jf.entries.asScala.map(_.getName).filter(n => n.endsWith(".class") && !n.startsWith("META-INF/") &&
        n != "module-info.class").map(name).toVector
      finally jf.close()
    else Vector.empty

  def classpath(entries: Seq[Path]): Map[Path, Vector[Ref]] = entries.map(p => p -> path(p)).toMap

  /** sha-256 of what was scanned — a jar's bytes, or a directory's class files in path order */
  def digest(p: Path): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    if Files.isDirectory(p) then
      Files.walk(p).iterator().asScala.filter(f => f.toString.endsWith(".class")).toVector.sortBy(_.toString)
        .foreach { f => md.update(p.relativize(f).toString.getBytes("UTF-8")); md.update(Files.readAllBytes(f)) }
    else if Files.isRegularFile(p) then md.update(Files.readAllBytes(p))
    md.digest().map(x => f"$x%02x").mkString
