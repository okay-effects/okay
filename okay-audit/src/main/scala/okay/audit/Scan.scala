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
  * `text` the string constant an `ldc` pushed just before it (a reflective name: `getDeclaredField("x$lzy1")`), `recv`
  * for a call the index (among the method's sites) of the field load or call whose value is its receiver — a static
  * call's first argument —, -1 when not known (`Scan.sites`, the receiver tracking) */
final case class Site(op: Int, line: Int, ref: Ref, text: String = "", recv: Int = -1):
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

final case class MethodInfo(access: Int, name: String, descriptor: String, signature: Option[String],
                            sites: Vector[Site]):
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
      val (sig, _) = attributes(body => if code then sites = this.sites(body, tags, a, b, ref,
        idx => Ref(self, Ref.Kind.Class, className(idx), "", ""), utf8,
        (if (acc & 0x0008) != 0 then 0 else 1) + slots(desc)._1))
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

  /** a method descriptor's arguments in slots (a long or a double takes two) and its result's (void: none) */
  private[audit] def slots(descriptor: String): (Int, Int) =
    var i = 1
    var args = 0
    while descriptor.charAt(i) != ')' do
      val c = descriptor.charAt(i)
      args += (if c == 'J' || c == 'D' then 2 else 1)
      while descriptor.charAt(i) == '[' do i += 1
      if descriptor.charAt(i) == 'L' then i = descriptor.indexOf(';', i)
      i += 1
    val r = descriptor.charAt(i + 1)
    (args, if r == 'V' then 0 else if r == 'J' || r == 'D' then 2 else 1)

  /** the slots a field of this descriptor takes on the operand stack */
  private def width(descriptor: String): Int = if descriptor == "J" || descriptor == "D" then 2 else 1

  /** the operand-stack effect (slots popped, slots pushed, none of them a member's value) of an instruction whose
    * values the receiver tracking does not follow: constants, arithmetic, conversions, comparisons, arrays */
  private def plain(op: Int): (Int, Int) = op match
    case o if o >= 0x01 && o <= 0x08 => (0, 1)
    case 0x09 | 0x0a | 0x0e | 0x0f | 0x14 => (0, 2)
    case 0x0b | 0x0c | 0x0d | 0x10 | 0x11 | 0x12 | 0x13 | 0xbb => (0, 1)
    case 0x2e | 0x30 | 0x32 | 0x33 | 0x34 | 0x35 => (2, 1)
    case 0x2f | 0x31 => (2, 2)
    case 0x4f | 0x51 | 0x53 | 0x54 | 0x55 | 0x56 => (3, 0)
    case 0x50 | 0x52 => (4, 0)
    case 0x57 | 0xc2 | 0xc3 => (1, 0)
    case 0x58 => (2, 0)
    case o if o >= 0x60 && o <= 0x73 => if (o - 0x60) % 2 == 0 then (2, 1) else (4, 2)
    case 0x74 | 0x76 => (1, 1)
    case 0x75 | 0x77 => (2, 2)
    case o if o >= 0x78 && o <= 0x7d => if o % 2 == 0 then (2, 1) else (3, 2)
    case o if o >= 0x7e && o <= 0x83 => if o % 2 == 0 then (2, 1) else (4, 2)
    case 0x85 | 0x87 | 0x8c | 0x8d => (1, 2)
    case 0x86 | 0x8b | 0x91 | 0x92 | 0x93 | 0xbc | 0xbd | 0xbe | 0xc1 => (1, 1)
    case 0x88 | 0x89 | 0x8e | 0x90 => (2, 1)
    case 0x8a | 0x8f => (2, 2)
    case 0x94 | 0x97 | 0x98 => (4, 1)
    case 0x95 | 0x96 => (2, 1)
    case _ => (0, 0)                                                   // nop, iinc, checkcast (its value kept)

  /** the instructions of one Code attribute that name a field, a method or a class (`new`), with their lines, and
    * for a call the site whose value is its receiver (`Site.recv`; a static call's first argument). `params`: the
    * local slots the method's parameters (and `this`) take.
    *
    * THE RECEIVER TRACKING: one pass over the code with an abstract operand stack whose entries are ORIGINS — the
    * index of the member site that pushed the value, or -1 (anything else). A forward jump's state joins its
    * target's (an origin kept where every way in agrees); a backward jump's target and an exception handler start
    * empty. A local keeps the origin it was stored with: in the flow, and — for a slot stored exactly once in the
    * method, not a parameter's — from its store on everywhere, so a loop over a list loaded before it is still seen.
    * An unknown origin only ever misses a receiver, never invents one. */
  private def sites(body: Array[Byte], tags: Array[Int], a: Array[Int], b: Array[Int], ref: Int => Ref,
                    classRef: Int => Ref, utf8: Array[String], params: Int): Vector[Site] =
    val in = DataInputStream(ByteArrayInputStream(body))
    in.skipNBytes(4)                                                     // max_stack, max_locals
    val length = in.readInt()
    val code = in.readNBytes(length)
    val handlers = Set.newBuilder[Int]
    for _ <- 0 until in.readUnsignedShort() do
      in.skipNBytes(4); handlers += in.readUnsignedShort(); in.skipNBytes(2)   // start, end, HANDLER, type
    val handler = handlers.result()
    val lines = Vector.newBuilder[(Int, Int)]
    for _ <- 0 until in.readUnsignedShort() do
      val name = utf8(in.readUnsignedShort())
      val len = in.readInt() & 0xFFFFFFFFL
      if name == "LineNumberTable" then
        for _ <- 0 until in.readUnsignedShort() do lines += ((in.readUnsignedShort(), in.readUnsignedShort()))
      else in.skipNBytes(len)
    val table = lines.result().sortBy(_._1)
    def lineAt(pc: Int): Int = table.takeWhile(_._1 <= pc).lastOption.fold(0)(_._2)
    def u1(at: Int): Int = code(at) & 0xff
    def u2(at: Int): Int = (u1(at) << 8) | u1(at + 1)
    def s2(at: Int): Int = (code(at) << 8) | u1(at + 1)
    def s4(at: Int): Int = (u1(at) << 24) | (u1(at + 1) << 16) | (u1(at + 2) << 8) | u1(at + 3)
    def sizeAt(pc: Int): Int = u1(pc) match
      case 0x10 | 0x12 | 0x15 | 0x16 | 0x17 | 0x18 | 0x19 | 0x36 | 0x37 | 0x38 | 0x39 | 0x3a | 0xa9 | 0xbc => 2
      case 0x11 | 0x13 | 0x14 | 0x84 | 0xbb | 0xbd | 0xc0 | 0xc1 | 0xc6 | 0xc7 => 3
      case o if o >= 0x99 && o <= 0xa8 => 3
      case o if o >= 0xb2 && o <= 0xb8 => 3
      case 0xb9 | 0xba | 0xc8 | 0xc9 => 5
      case 0xc5 => 4
      case 0xc4 => if u1(pc + 1) == 0x84 then 6 else 4
      case 0xaa =>
        val at = (pc + 4) & ~3
        val low = s4(at + 4); val high = s4(at + 8)
        at - pc + 12 + 4 * (high - low + 1)
      case 0xab =>
        val at = (pc + 4) & ~3
        at - pc + 8 + 8 * s4(at + 4)
      case _ => 1
    /** where an instruction jumps: a branch's target, a switch's every target */
    def jumps(pc: Int): Vector[Int] = u1(pc) match
      case o if (o >= 0x99 && o <= 0xa8) || o == 0xc6 || o == 0xc7 => Vector(pc + s2(pc + 1))
      case 0xc8 | 0xc9 => Vector(pc + s4(pc + 1))
      case 0xaa =>
        val at = (pc + 4) & ~3
        val low = s4(at + 4); val high = s4(at + 8)
        (pc + s4(at)) +: Vector.tabulate(high - low + 1)(k => pc + s4(at + 12 + 4 * k))
      case 0xab =>
        val at = (pc + 4) & ~3
        (pc + s4(at)) +: Vector.tabulate(s4(at + 4))(k => pc + s4(at + 12 + 8 * k))
      case _ => Vector.empty
    /** the local a load or a store names, with its kind's opcode (iload 0x15 … astore 0x3a), else None */
    def local(pc: Int): Option[(Int, Int)] = u1(pc) match
      case o if o >= 0x15 && o <= 0x19 => Some((o, u1(pc + 1)))
      case o if o >= 0x36 && o <= 0x3a => Some((o, u1(pc + 1)))
      case o if o >= 0x1a && o <= 0x2d => Some((0x15 + (o - 0x1a) / 4, (o - 0x1a) % 4))
      case o if o >= 0x3b && o <= 0x4e => Some((0x36 + (o - 0x3b) / 4, (o - 0x3b) % 4))
      case 0xc4 if u1(pc + 1) != 0x84 => Some((u1(pc + 1), u2(pc + 2)))
      case _ => None

    // the first pass: where instructions start, which are jumped to backward, how often each local is stored
    val starts = Vector.newBuilder[Int]
    val backward = scala.collection.mutable.Set.empty[Int]
    val stores = scala.collection.mutable.Map.empty[Int, Int].withDefaultValue(0)
    var at = 0
    while at < length do
      starts += at
      jumps(at).filter(_ <= at).foreach(backward += _)
      local(at).filter(_._1 >= 0x36).foreach { (k, n) =>
        stores(n) += 1
        if k == 0x37 || k == 0x39 then stores(n + 1) += 1
      }
      at += sizeAt(at)
    def single(n: Int) = n >= params && stores(n) == 1

    val out = Vector.newBuilder[Site]
    var count = 0
    def site(s: Site): Int = { out += s; count += 1; count - 1 }
    def add(s: Site): Unit = site(s): Unit
    var stack = Vector.empty[Int]                                        // origins, the top last
    var locals = Map.empty[Int, Int]
    val fixed = scala.collection.mutable.Map.empty[Int, Int]              // single-store slots, from their store on
    val pending = scala.collection.mutable.Map.empty[Int, (Vector[Int], Map[Int, Int])]
    def join(x: (Vector[Int], Map[Int, Int]), y: (Vector[Int], Map[Int, Int])): (Vector[Int], Map[Int, Int]) =
      val n = x._1.length.min(y._1.length)
      (x._1.takeRight(n).zip(y._1.takeRight(n)).map((p, q) => if p == q then p else -1),
        x._2.filter((k, v) => y._2.get(k).contains(v)))
    def jump(at: Int)(to: Int): Unit =
      if to > at then pending(to) = pending.get(to).fold((stack, locals))(join(_, (stack, locals)))
    def pop(): Int = if stack.isEmpty then -1 else { val v = stack.last; stack = stack.init; v }
    def drop(n: Int): Unit = for _ <- 0 until n do pop()
    def push(vs: Int*): Unit = stack = stack ++ vs
    def unknown(n: Int): Unit = for _ <- 0 until n do push(-1)
    // the string an `ldc` pushed, kept for the one instruction right after it
    var pushed = ""
    def utf8At(idx: Int) = if idx > 0 && idx < tags.length && tags(idx) == 8 then Option(utf8(a(idx))) else None
    var live = true                                                      // the instruction before falls through
    for pc <- starts.result() do
      val incoming = pending.remove(pc)
      if handler(pc) then { stack = Vector(-1); locals = Map.empty }
      else if backward(pc) then { stack = Vector.empty; locals = Map.empty }
      else incoming.foreach { p =>
        val (s, l) = if live then join((stack, locals), p) else p
        stack = s; locals = l
      }
      live = true
      val op = u1(pc)
      var next = ""
      if op >= 0xb2 && op <= 0xb9 then
        val idx = u2(pc + 1)
        val r = ref(idx)
        val named = tags(idx) == 9 || tags(idx) == 10 || tags(idx) == 11
        op match
          case 0xb2 | 0xb4 =>
            if op == 0xb4 then drop(1)
            val at = if named then site(Site(op, lineAt(pc), r, pushed)) else -1
            if width(r.descriptor) == 2 then unknown(2) else push(at)
          case 0xb3 => drop(width(r.descriptor)); if named then add(Site(op, lineAt(pc), r, pushed))
          case 0xb5 => drop(width(r.descriptor) + 1); if named then add(Site(op, lineAt(pc), r, pushed))
          case _ =>
            val (args, result) = slots(r.descriptor)
            val popped = Vector.fill(args)(pop())                       // the last argument first
            val recv = if op == 0xb8 then popped.lastOption.getOrElse(-1) else pop()
            val at = if named then site(Site(op, lineAt(pc), r, pushed, recv)) else -1
            if result == 1 then push(at) else unknown(result)
      else if op == 0xba then
        val (args, result) = slots(utf8(b(b(u2(pc + 1)))))
        drop(args); unknown(result)
      else if op == Site.New || op == Site.CheckCast || op == Site.InstanceOf || op == Site.ANewArray ||
          op == Site.MultiANewArray then
        val idx = u2(pc + 1)
        if tags(idx) == 7 && utf8(a(idx)).charAt(0) != '[' then add(Site(op, lineAt(pc), classRef(idx)))
        if op == Site.MultiANewArray then { drop(u1(pc + 3)); unknown(1) }
        else { val (p, q) = plain(op); drop(p); unknown(q) }
      else if op == Site.Ldc || op == Site.LdcW then
        val idx = if op == Site.Ldc then u1(pc + 1) else u2(pc + 1)
        if tags(idx) == 7 && utf8(a(idx)).charAt(0) != '[' then add(Site(op, lineAt(pc), classRef(idx)))
        else utf8At(idx).foreach(next = _)
        unknown(1)
      else local(pc) match
        case Some((0x19, n)) => push(if single(n) then fixed.getOrElse(n, -1) else locals.getOrElse(n, -1))
        case Some((0x3a, n)) =>
          val v = pop()
          if single(n) then fixed(n) = v else locals += n -> v
        case Some((k, n)) if k >= 0x36 =>
          val two = k == 0x37 || k == 0x39
          drop(if two then 2 else 1); locals = locals - n - (n + 1) - (n - 1)
        case Some((k, _)) => unknown(if k == 0x16 || k == 0x18 then 2 else 1)
        case None => op match
          case 0x59 => val v = pop(); push(v, v)
          case 0x5a => val v1 = pop(); val v2 = pop(); push(v1, v2, v1)
          case 0x5b => val v1 = pop(); val v2 = pop(); val v3 = pop(); push(v1, v3, v2, v1)
          case 0x5c => val v1 = pop(); val v2 = pop(); push(v2, v1, v2, v1)
          case 0x5d => val v1 = pop(); val v2 = pop(); val v3 = pop(); push(v2, v1, v3, v2, v1)
          case 0x5e =>
            val v1 = pop(); val v2 = pop(); val v3 = pop(); val v4 = pop(); push(v2, v1, v4, v3, v2, v1)
          case 0x5f => val v1 = pop(); val v2 = pop(); push(v1, v2)
          case o if o >= 0x99 && o <= 0x9e || o == 0xc6 || o == 0xc7 => drop(1); jumps(pc).foreach(jump(pc))
          case o if o >= 0x9f && o <= 0xa6 => drop(2); jumps(pc).foreach(jump(pc))
          case 0xa7 | 0xc8 => jumps(pc).foreach(jump(pc)); live = false
          case 0xa8 | 0xc9 => push(-1); jumps(pc).foreach(jump(pc)); drop(1)
          case 0xaa | 0xab => drop(1); jumps(pc).foreach(jump(pc)); live = false
          case o if (o >= 0xac && o <= 0xb1) || o == 0xbf || o == 0xa9 => stack = Vector.empty; live = false
          case _ => val (p, q) = plain(op); drop(p); unknown(q)
      pushed = next
      if !live then { stack = Vector.empty; locals = Map.empty }
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
