package okay.audit

import java.nio.file.{Files, Path}
import java.util.jar.JarFile

/** a JVM type as a descriptor or a generic `Signature` attribute writes it (JVMS §4.7.9.1) */
enum JType:
  case Base(code: Char)
  case Var(name: String)
  case Arr(of: JType)
  case Cls(name: String, args: Vector[JArg])

  def show: String = this match
    case Base(c) => c match
      case 'Z' => "boolean"; case 'B' => "byte"; case 'C' => "char"; case 'S' => "short"; case 'I' => "int"
      case 'J' => "long"; case 'F' => "float"; case 'D' => "double"; case _ => "void"
    case Var(n) => n
    case Arr(of) => s"${of.show}[]"
    case Cls(n, args) => if args.isEmpty then n else s"$n<${args.map(_.show).mkString(", ")}>"

enum JArg:
  case Star
  case Exact(t: JType)
  case Extends(t: JType)
  case Super(t: JType)

  def show: String = this match
    case Star => "?"
    case Exact(t) => t.show
    case Extends(t) => s"? extends ${t.show}"
    case Super(t) => s"? super ${t.show}"

/** the reader of descriptors and generic signatures: a small recursive descent, bounded by the signature's length */
object Signatures:
  /** the deepest nesting of types read: a written type is a handful of levels deep, and this bounds the recursion */
  val MaxNesting = 256

  private final class Reader(s: String):
    var at = 0
    def peek: Char = if at < s.length then s.charAt(at) else '\u0000'
    def take(): Char = { val c = s.charAt(at); at += 1; c }
    def skip(): Unit = at += 1

    def typeParams(): Unit =
      if peek == '<' then
        skip()
        while peek != '>' do
          while peek != ':' do skip()                                    // the parameter's name
          while peek == ':' do
            skip()
            if peek != ':' && peek != '>' && (peek == 'L' || peek == 'T' || peek == '[') then fieldType(): Unit
        skip()

    /** nesting deeper than `Signatures.MaxNesting` is refused: the bound of this descent and of every walk over
      * the types it makes (`show`, `classes`) */
    private var depth = 0
    def fieldType(): JType =
      depth += 1
      if depth > Signatures.MaxNesting then
        throw IllegalArgumentException(s"a signature nested deeper than ${Signatures.MaxNesting} levels: $s")
      try one() finally depth -= 1

    private def one(): JType = take() match
      case 'L' => classType()
      case 'T' =>
        val start = at
        while peek != ';' do skip()
        val n = s.substring(start, at); skip(); JType.Var(n)
      case '[' => JType.Arr(fieldType())
      case c => JType.Base(c)

    /** `Lpkg/Outer<A>.Inner<B>;` → `pkg.Outer$Inner` with every part's arguments */
    def classType(): JType =
      val name = StringBuilder()
      val args = Vector.newBuilder[JArg]
      var done = false
      while !done do
        take() match
          case ';' => done = true
          case '<' =>
            while peek != '>' do args += argument()
            skip()
          case '.' => name += '$'
          case '/' => name += '.'
          case c => name += c
      JType.Cls(name.result(), args.result())

    def argument(): JArg = peek match
      case '*' => skip(); JArg.Star
      case '+' => skip(); JArg.Extends(fieldType())
      case '-' => skip(); JArg.Super(fieldType())
      case _ => JArg.Exact(fieldType())

  /** a field's type, from its Signature or its descriptor */
  def field(sig: String): JType = Reader(sig).fieldType()

  /** a method's parameters and result, from its Signature or its descriptor */
  def method(sig: String): (Vector[JType], JType) =
    val r = Reader(sig)
    r.typeParams()
    r.skip()                                                       // '('
    val params = Vector.newBuilder[JType]
    while r.peek != ')' do params += r.fieldType()
    r.skip()
    (params.result(), r.fieldType())

  /** a class's superclass and interfaces, from its Signature */
  def supers(sig: String): Vector[JType] =
    val r = Reader(sig)
    r.typeParams()
    val out = Vector.newBuilder[JType]
    while r.at < sig.length do out += r.fieldType()
    out.result()

  /** every class a type names, its arguments' too */
  def classes(t: JType): Vector[String] = t match
    case JType.Cls(n, args) => n +: args.flatMap {
      case JArg.Star => Vector.empty
      case JArg.Exact(x) => classes(x); case JArg.Extends(x) => classes(x); case JArg.Super(x) => classes(x)
    }
    case JType.Arr(of) => classes(of)
    case _ => Vector.empty

/** THE CLASSES an audit can look up by name: those of its inputs, then the
  * JDK's and whatever this JVM can load as a resource (a class file is a
  * resource never encapsulated). Parsed without their code, once, on demand —
  * the hierarchy a rule matches up, a field's finality, a type's fields */
final class Classes(find: String => Option[Array[Byte]], release: () => Unit = () => ()):
  private val parsed = scala.collection.mutable.HashMap.empty[String, Option[ClassInfo]]

  def get(name: String): Option[ClassInfo] = parsed.getOrElseUpdate(name, find(name).map(Scan.parse(_, code = false)))

  private val supersCache = scala.collection.mutable.HashMap.empty[String, Vector[String]]

  /** `name`, then its superclasses and interfaces, breadth first, as far as their class files are found */
  def supers(name: String): Vector[String] = supersCache.getOrElseUpdate(name, {
    val out = Vector.newBuilder[String]
    val seen = scala.collection.mutable.HashSet.empty[String]
    val todo = scala.collection.mutable.Queue(name)
    while todo.nonEmpty do
      val n = todo.dequeue()
      if seen.add(n) then
        out += n
        get(n).foreach(c => todo ++= c.supers)
    out.result()
  })

  /** the field `name` found from `owner` up */
  def field(owner: String, name: String): Option[FieldInfo] =
    supers(owner).iterator.flatMap(c => get(c).flatMap(_.fields.find(_.name == name))).nextOption()

  def close(): Unit = release()

object Classes:
  /** the classes of `inputs` (directories and jars, the first one's class winning), then this JVM's */
  def apply(inputs: Seq[Path]): Classes =
    lazy val index: Map[String, Path] = inputs.reverse.flatMap(p => Scan.names(p).map(_ -> p)).toMap
    val jars = scala.collection.mutable.HashMap.empty[Path, JarFile]
    def find(name: String): Option[Array[Byte]] =
      val file = name.replace('.', '/') + ".class"
      index.get(name) match
        case Some(p) if Files.isDirectory(p) => Some(Files.readAllBytes(p.resolve(file)))
        case Some(p) =>
          val jf = jars.getOrElseUpdate(p, JarFile(p.toFile))
          Option(jf.getEntry(file)).map(e => jf.getInputStream(e).readAllBytes())
        case None => system(name)
    new Classes(find, () => jars.values.foreach(_.close()))

  /** the classes a function finds by INTERNAL name (`java/lang/Object`), null when it has none — a
    * transformer's own lookup (okay-java's resumable transformation) */
  def byInternalName(find: java.util.function.Function[String, Array[Byte]]): Classes =
    new Classes(name => Option(find.apply(name.replace('.', '/'))))

  /** a class file this JVM can read as a resource: the JDK's, and what is on its own class path */
  def system(name: String): Option[Array[Byte]] =
    Option(ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class"))
      .map(s => try s.readAllBytes() finally s.close())

/** THE BOUNDARY RULE ON TYPES (okay-safe's, on bytecode): what crosses from a
  * handlers or runtime module into business code — the arguments it passes to a
  * business method or constructor, an operation's answer — must be FIRST-ORDER
  * DATA: a value whose every part is a primitive, a string, a number, an
  * immutable collection, option or tuple of data, a type of `data`, or a
  * business or runtime class that is final or closed (nothing in a handlers or
  * runtime module extends it), its fields final and of data. Not a function (a
  * host's lambda is IO in disguise, with what it captured), not `Object` (it
  * says nothing of what a value is), not an array (shared and mutable), not a
  * library's class, not a handler's. A type variable is data here: what it
  * stands for is held to the rule where it is given.
  *
  * `answers`: an effect type and which of its type arguments is an operation's
  * answer (`-1` the last): a business class extending it answers that type. `programs`: the runtime's program
  * types (okay-scala's `Prog`, okay.rows' `Comp`, okay-safe's `Comp`, okay-java's `Eff`, their nested classes with
  * them): a program the host gives is a program — a value the runtime runs, not a function (specs/okay-audit.md, "The
  * first real-world run"). */
final case class Types(data: Vector[String] = Types.Data, answers: Map[String, Int] = Map.empty,
                       programs: Vector[String] = Types.Programs):
  def canonical: String = (data.sorted.map("data " + _) ++
    answers.toVector.sorted.map((t, i) => s"answer $t $i") ++ programs.sorted.map("program " + _)).mkString("\n")

object Types:
  /** the data of the JDK and the Scala library beside the primitives, and the family's own vouched values:
    * okay-http's `Bytes` (immutable: copied in, copied out — okay-http's `HttpSuite` pins it) */
  val Data: Vector[String] = Vector("java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Character",
    "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Void",
    "java.math.BigInteger", "java.math.BigDecimal", "java.time.Instant", "java.time.Duration", "java.time.LocalDate",
    "java.time.LocalDateTime", "java.time.LocalTime", "java.time.ZoneOffset", "java.time.Period", "java.util.UUID",
    "java.util.Optional", "scala.math.BigInt", "scala.math.BigDecimal", "scala.Option", "scala.Some", "scala.None$",
    "scala.util.Either", "scala.util.Left", "scala.util.Right", "scala.runtime.BoxedUnit", "scala.Unit",
    "scala.runtime.Nothing$", "scala.Tuple*", "scala.collection.immutable.",
    "okay.http.Bytes")

  /** the kernels' program types */
  val Programs: Vector[String] = Vector("okay.scala3.Prog", "okay.rows.Comp", "okay.safe.Comp", "okay.core.Eff")

  /** `name` is a program type or one of its nested classes (`okay.scala3.Prog$Defer`) */
  def program(programs: Vector[String], name: String): Boolean = programs.exists(p => Boundary.under(name, p))

  /** never data, whatever a list says: they say nothing of what a value is */
  val Anything: Set[String] = Set("java.lang.Object", "java.io.Serializable", "java.lang.Comparable",
    "java.lang.CharSequence", "scala.Product", "scala.Equals", "scala.Serializable", "scala.Matchable")

  /** code, not data */
  def function(name: String): Boolean =
    name.matches("""scala\.Function\d+""") || name.matches("""scala\.ContextFunction\d+""") ||
      name == "scala.PartialFunction" || name == "scala.PolyFunction" || name.startsWith("java.util.function.") ||
      name == "java.lang.Runnable" || name == "java.util.concurrent.Callable"

  /** never data even under a data prefix: lazy, so code */
  private val lazyCollections = Vector("scala.collection.immutable.LazyList", "scala.collection.immutable.Stream")

  def listed(data: Vector[String], name: String): Boolean =
    !lazyCollections.exists(l => name == l || name.startsWith(l + "$")) && data.exists { e =>
      if e.endsWith(".") then name.startsWith(e)
      else if e.endsWith("*") then name.startsWith(e.dropRight(1))
      else name == e
    }
