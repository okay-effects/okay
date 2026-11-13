package okay.audit

enum Layer:
  /** may touch the outside world only through okay effects — scanned, failed on a finding */
  case Business
  /** the trusted base: listed by provider, never failed */
  case Handlers
  /** okay core and its platform: listed as the runtime, never failed */
  case Runtime
  /** no layer declared: reported, neither failed nor listed — the state adoption starts from */
  case Untracked

/** an API outside the boundary, WHY, and what to USE instead (the effect to
  * perform; empty where there is none). `api` is one of:
  *  - a package prefix, ending in a dot: `java.net.` (or `java.net.*`)
  *  - a class: `java.lang.Thread` (its nested classes with it)
  *  - a member: `java.lang.System#currentTimeMillis`; `#system*` is a name
  *    prefix; `java.util.Date#<init>()` pins the descriptor's start. A dotted
  *    `java.lang.System.exit` (okay-java's spelling) names the member too
  *  - `state`: the static-state rule, checked on code (`Code`), not on refs */
final case class Rule(api: String, why: String, use: String = ""):
  def isPackage: Boolean = api.endsWith(".") || api.endsWith(".*")

  def matches(r: Ref): Boolean =
    api.indexOf('#') match
      case -1 =>
        if api.endsWith(".*") then r.owner.startsWith(api.dropRight(1))
        else if api.endsWith(".") then r.owner.startsWith(api)
        else r.owner == api || r.owner.startsWith(api + "$") || {
          val d = api.lastIndexOf('.')
          d > 0 && r.name.nonEmpty && r.owner == api.substring(0, d) && r.name == api.substring(d + 1)
        }
      case h =>
        val owner = api.substring(0, h)
        val rest = api.substring(h + 1)
        val (name, desc) = rest.indexOf('(') match
          case -1 => (rest, "")
          case p => (rest.substring(0, p), rest.substring(p))
        r.name.nonEmpty && r.owner == owner && desc.forall(_ => r.descriptor.startsWith(desc)) &&
          (if name.endsWith("*") then r.name.startsWith(name.dropRight(1)) else r.name == name)

  /** the same, the owner's superclasses and interfaces tried too (`supers(owner)` starts with the owner): a
    * subclass of `Thread` is a `Thread`, `String#wait` is `Object#wait`. A package rule reads the owner alone */
  def matchesUp(r: Ref, supers: String => Vector[String]): Boolean =
    matches(r) || (!isPackage && api != "state" && supers(r.owner).iterator.drop(1).exists(o => matches(r.copy(owner = o))))

  /** `— use X` for a report, or nothing */
  def hint: String = if use.isEmpty then "" else s" — use $use"

object Rule:
  /** a rule's api as source code names it: `okay.safe.Host$package$#run` -> `okay.safe.run`,
    * `scala.Predef$#println` -> `scala.Predef.println` (top-level definitions' and objects' classes dropped) */
  def sourcePath(api: String): String =
    api.replace('#', '.').split('.').toVector.filterNot(_.endsWith("$package$")).map(_.stripSuffix("$"))
      .mkString(".").stripSuffix(".")

/** a named, reasoned exception for ONE module; an allow nothing matched is reported */
final case class Allow(module: String, api: String, reason: String, owner: String):
  private val rule = Rule(api, reason)
  def matches(module: String, r: Ref): Boolean = this.module == module && rule.matches(r)

/** `layers`: module -> layer. `packages`: module -> package prefix -> layer, for a
  * product that is ONE module by design with business and handlers side by
  * side (okay-watch: `okaywatch.trace` is business, `okaywatch.collect` is
  * handlers, one jar). A class takes the longest matching prefix's layer,
  * else its module's (specs/audit-ready.md, stage 1). */
final case class Boundary(layers: Map[String, Layer], rules: Vector[Rule] = Boundary.Default, allows: Vector[Allow] = Vector.empty,
                          packages: Map[String, Map[String, Layer]] = Map.empty,
                          allowlist: Option[Allowlist] = None, types: Option[Types] = None):
  /** the prefix that classifies `cls`, if any: the longest one it is under */
  def prefixOf(module: String, cls: String): Option[String] =
    packages.getOrElse(module, Map.empty).keys.filter(p => cls == p || cls.startsWith(p + ".")).maxByOption(_.length)
  def layerOf(module: String, cls: String): Layer =
    prefixOf(module, cls).flatMap(p => packages.get(module).flatMap(_.get(p))).getOrElse(layers.getOrElse(module, Layer.Untracked))

  /** the configuration, one line an entry, sorted — what a seal hashes, so a seal names the rules it was checked by */
  def canonical: String =
    (layers.toVector.map((m, l) => s"layer $m ${l.toString.toLowerCase}").sorted ++
      packages.toVector.flatMap((m, ps) => ps.toVector.map((p, l) => s"package $m $p ${l.toString.toLowerCase}")).sorted ++
      rules.map(r => s"rule ${r.api}").sorted ++
      allows.map(a => s"allow ${a.module} ${a.api} ${a.owner}").sorted ++
      allowlist.toVector.flatMap(_.entries.map("allowlist " + _)).sorted ++
      types.toVector.map(_.canonical)).mkString("\n")

/** DENY BY DEFAULT for business code (okay-safe's allowlist, on bytecode):
  * besides what a rule names, a business reference is a finding unless it is
  * to the module's own classes, to a runtime module's, or matches an entry
  * (a rule's spellings: `java.util.function.`, `java.lang.String`,
  * `java.lang.Math#abs`). A reference to a handlers module's class is a
  * finding of its own: business code reaches a handler only through its effect */
final case class Allowlist(entries: Vector[String]):
  private val rules = entries.map(Rule(_, ""))
  def allows(r: Ref): Boolean = rules.exists(_.matches(r))

object Allowlist:
  /** the JDK's and the Scala library's plain computation: what business code needs and what reaches nothing */
  val Default: Allowlist = Allowlist(Vector(
    "java.lang.Object", "java.lang.String", "java.lang.StringBuilder", "java.lang.Boolean", "java.lang.Byte",
    "java.lang.Character", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
    "java.lang.Double", "java.lang.Number", "java.lang.Math", "java.lang.StrictMath", "java.lang.Comparable",
    "java.lang.CharSequence", "java.lang.Iterable", "java.lang.Enum", "java.lang.Record", "java.lang.Void",
    "java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException", "java.lang.Error",
    "java.lang.IllegalArgumentException", "java.lang.IllegalStateException", "java.lang.NullPointerException",
    "java.lang.IndexOutOfBoundsException", "java.lang.UnsupportedOperationException", "java.lang.ArithmeticException",
    "java.lang.ClassCastException", "java.lang.AutoCloseable", "java.lang.Override", "java.lang.FunctionalInterface",
    "java.lang.Class#getName", "java.lang.Class#getSimpleName", "java.lang.Class#desiredAssertionStatus",
    "java.lang.Deprecated", "java.lang.SafeVarargs", "java.lang.SuppressWarnings",
    "java.lang.Runnable", "java.math.", "java.util.function.", "java.util.Objects", "java.util.Optional",
    "java.util.List", "java.util.Map", "java.util.Set", "java.util.Collection", "java.util.Iterator",
    "java.util.Collections", "java.util.Arrays", "java.util.Comparator", "java.util.stream.", "java.util.NoSuchElementException",
    "java.time.", "java.io.Serializable",
    "scala.collection.immutable.", "scala.collection.", "scala.math.", "scala.Option", "scala.Some", "scala.None$",
    "scala.util.Either", "scala.util.Left", "scala.util.Right", "scala.util.hashing.", "scala.util.control.",
    "scala.util.boundary", "scala.util.matching.", "scala.runtime.", "scala.Predef$", "scala.Tuple", "scala.Product",
    "scala.Function", "scala.PartialFunction", "scala.Equals", "scala.Serializable", "scala.MatchError",
    "scala.package$", "scala.reflect.ClassTag", "scala.reflect.Enum", "scala.deriving.", "scala.Array",
    "scala.Int$", "scala.Long$", "scala.Double$", "scala.Char$", "scala.Boolean$", "scala.Unit$",
    "scala.Tuple2$mcII$sp", "scala.NotImplementedError", "scala.annotation.", "scala.CanEqual",
    "scala.LowPriorityImplicits"))

object Boundary:
  /** the three bootstraps every compiler emits — a lambda, a string
    * concatenation, a record's equals/hashCode/toString — are the one
    * hard-coded carve-out, and the report's header names them */
  val Bootstraps: Vector[String] =
    Vector("java.lang.invoke.LambdaMetafactory", "java.lang.invoke.StringConcatFactory", "java.lang.runtime.ObjectMethods")

  /** the types a bootstrap's descriptor names — javac writes them into the
    * pool as bare `CONSTANT_Class` entries (its InnerClasses attribute), so a
    * BARE class reference to one of these is part of the carve-out; a MEMBER
    * reference (`MethodHandles#lookup`) is the escape hatch and stays a finding */
  val BootstrapTypes: Set[String] =
    Set("java.lang.invoke.MethodHandles", "java.lang.invoke.MethodHandles$Lookup", "java.lang.invoke.MethodHandle",
      "java.lang.invoke.MethodType", "java.lang.invoke.CallSite", "java.lang.invoke.TypeDescriptor")

  /** Scala 3's `lazy val` compiles to `MethodHandles.lookup().findVarHandle(...)`
    * and `VarHandle.compareAndSet` in the class's own initializer (measured on
    * the dogfood: every module with a lazy val, 4 references each). A VarHandle
    * reaches MEMORY, not the world, and `lookup()` alone reaches nothing, so
    * these three are carved out; `Lookup#findVirtual`, `#findStatic`,
    * `#unreflect*`, `#defineClass` — the ones that reach code — stay findings */
  val LazyValIdiom: Set[String] =
    Set("java.lang.invoke.MethodHandles#lookup", "java.lang.invoke.MethodHandles$Lookup#findVarHandle")

  def isBootstrap(r: Ref): Boolean =
    Bootstraps.contains(r.owner) || (r.kind == Ref.Kind.Class && BootstrapTypes.contains(r.owner)) ||
      r.owner == "java.lang.invoke.VarHandle" || LazyValIdiom.contains(r.member)

  /** an own `ACC_NATIVE` method: always a finding in a business module — code the JVM cannot see */
  val Native: Rule = Rule("native", "a native method: code neither the JVM nor this check can see")

  /** the default rules: what a business module may not reference directly, each with its why and, where an effect
    * does it, the effect to use (okay.rows.std for Java, okay-scala's okay.scala3.std, okay-safe's okay.safe.std) */
  val Default: Vector[Rule] =
    Presets.rules(Presets.Network) ++ Presets.rules(Presets.Files) ++ Presets.rules(Presets.Databases) ++
      Presets.rules(Presets.Processes) ++ Presets.rules(Presets.TimeAndRandomness) ++ Presets.rules(Presets.Threads) ++
      Presets.rules(Presets.Escapes)

/** a set of rules, named — after a standard effect where one replaces what they forbid */
final case class Preset(name: String, why: String, use: String, apis: Vector[String]):
  def rules: Vector[Rule] = apis.map(Rule(_, why, use))

/** THE PRESETS NAMED AFTER THE STANDARD EFFECTS (okay-java's resumable presets, okay-safe's deny list, moved here):
  * each forbids the ambient way of doing what an effect does, and names the effect. `Presets.rules("clock, fresh,
  * java.lang.Thread")` selects; `pure` is every effect preset, `strict` okay-java's former strict list; an entry that
  * is no preset's name is a rule of its own. */
object Presets:
  val UseState = "a State effect (okay.rows.std.State, okay.scala3.std.State, okay.safe.std.State) or a handler in place"
  val UseReader = "a Reader effect (okay.rows.std.Reader, okay.scala3.std.Reader, okay.safe.std.Reader)"
  val UseWriter = "a Writer effect (okay.rows.std.Writer or okay.rows.std.Trail, okay.scala3.std.Writer, " +
    "okay.safe.std.Writer)"
  val UseClock = "a Clock effect (okay.rows.std.Clock, okay.scala3.std.Clock, okay.safe.std.Clock)"
  val UseFresh = "a Fresh effect (okay.rows.std.Fresh, okay.scala3.std.Fresh or okay.scala3.std.Random, " +
    "okay.safe.std.Fresh)"
  val UseAsync = "the Async effect (okay.rows.std.Async, okay.scala3.std.Async): the runtime schedules, a program forks"
  val UseIo = "an effect of your own, whose handler — in a handlers module — does it"
  val UseNested = "one program: compose the sub-program into it, its handlers inside (okay.rows: Direct.row(..)" +
    ".embed(..)); installing a handler is allowed, running a program to its end is the host's, at its edge"

  // the ambient groups of the default rules, split where the replacing effect differs
  private val net = "network: reached through an effect (Http, Net) whose handler is a Handlers module"
  private val files = "files and console: a File or Console effect, journaled, not a direct write"
  private val db = "databases: a Db effect, so every statement is journaled and replayable"
  private val proc = "processes and the JVM: the host is the runtime's, not a program's"
  private val time = "time and randomness: nondeterminism breaks replay — a Clock or Random effect instead"
  private val threads = "threads: scheduling is the runtime's job (Async), a program forks fibers"
  private val escape = "escape hatch: the way around any static check, so the check forbids it"

  val Network: Vector[Preset] = Vector(Preset("network", net, UseIo,
    Vector("java.net.", "javax.net.", "java.nio.channels.", "java.rmi.", "javax.naming.")))
  val Files: Vector[Preset] = Vector(
    Preset("files", files, UseIo, Vector("java.nio.file.", "java.io.File", "java.io.FileInputStream",
      "java.io.FileOutputStream", "java.io.FileReader", "java.io.FileWriter", "java.io.RandomAccessFile",
      "java.io.Console", "java.lang.System#in", "scala.io.Source")),
    Preset("console", files, UseWriter, Vector("java.lang.System#out", "java.lang.System#err", "scala.Console",
      "java.util.logging.")))
  val Databases: Vector[Preset] = Vector(Preset("databases", db, UseIo, Vector("java.sql.", "javax.sql.")))
  val Processes: Vector[Preset] = Vector(
    Preset("processes", proc, "", Vector("java.lang.ProcessBuilder", "java.lang.Process", "java.lang.Runtime#exec",
      "java.lang.Runtime#exit", "java.lang.Runtime#halt", "java.lang.Runtime#addShutdownHook", "java.lang.System#exit",
      "java.lang.System#load", "java.lang.System#loadLibrary", "scala.sys.process.")),
    Preset("environment", proc, UseReader, Vector("java.lang.System#getenv", "java.lang.System#getProperty",
      "java.lang.System#setProperty", "scala.sys.package$#env")))
  val TimeAndRandomness: Vector[Preset] = Vector(
    Preset("time", time, UseClock, Vector("java.lang.System#currentTimeMillis", "java.lang.System#nanoTime",
      "java.time.Clock#system*", "java.time.Instant#now", "java.time.LocalDate#now", "java.time.LocalDateTime#now",
      "java.time.LocalTime#now", "java.time.ZonedDateTime#now", "java.time.OffsetDateTime#now",
      "java.util.Date#<init>()")),
    Preset("randomness", time, UseFresh, Vector("java.util.Random", "java.util.concurrent.ThreadLocalRandom",
      "java.util.SplittableRandom", "java.security.SecureRandom", "java.util.UUID#randomUUID", "scala.util.Random")))
  val Threads: Vector[Preset] = Vector(Preset("threads", threads, UseAsync, Vector("java.lang.Thread",
    "java.util.concurrent.Executors", "java.util.concurrent.ForkJoinPool",
    "java.util.concurrent.CompletableFuture#runAsync", "java.util.concurrent.CompletableFuture#supplyAsync",
    "scala.concurrent.ExecutionContext$Implicits$")))
  val Escapes: Vector[Preset] = Vector(Preset("escape", escape, "", Vector("java.lang.reflect.", "java.lang.invoke.",
    "java.lang.Class#forName", "java.lang.Class#getMethod", "java.lang.Class#getDeclaredMethod",
    "java.lang.Class#getMethods", "java.lang.Class#getDeclaredMethods", "java.lang.Class#getField",
    "java.lang.Class#getDeclaredField", "java.lang.Class#newInstance", "java.lang.Class#getConstructor",
    "java.lang.Class#getDeclaredConstructor", "java.lang.Class#getConstructors", "java.lang.Class#getDeclaredConstructors",
    "java.lang.ClassLoader", "java.net.URLClassLoader", "java.lang.foreign.", "sun.", "jdk.internal.",
    "java.io.ObjectInputStream", "java.io.ObjectOutputStream")))

  def rules(ps: Vector[Preset]): Vector[Rule] = ps.flatMap(_.rules)

  /** the static-state rule: a static field not final written (refused), read (said); a Scala object's field the same */
  val State: Rule = Rule("state", "state: a static field that is not final, or a field of a Scala object — global " +
    "mutable state outside a handler", UseState)

  private val writing = Vector("java.lang.System#out", "java.lang.System#err", "java.lang.System#getLogger",
    "java.lang.Throwable#printStackTrace", "java.util.logging.", "org.slf4j.", "org.apache.logging.log4j.",
    "org.apache.log4j.", "scala.Console", "scala.Predef$#println", "scala.Predef$#print", "scala.Predef$#printf")

  /** THE PRESETS BY NAME, each named after the effect that replaces what it forbids */
  val all: Vector[Preset] = Vector(
    Preset("state", State.why, UseState, Vector("state")),
    Preset("reader", "reader: the environment and the system properties are read through an effect", UseReader,
      Vector("java.lang.System#getenv", "java.lang.System#getProperty", "java.lang.System#getProperties",
        "java.lang.System#setProperty", "java.lang.Integer#getInteger", "java.lang.Long#getLong",
        "java.lang.Boolean#getBoolean", "scala.sys.package$#env", "scala.sys.package$#props", "scala.util.Properties$",
        "scala.util.PropertiesTrait")),
    Preset("writer", "writer: a log line or console output is told through an effect, journaled", UseWriter, writing),
    Preset("trail", "writer: a log line or console output is told through an effect, journaled", UseWriter, writing),
    Preset("clock", "clock: the time now is nondeterminism — replay breaks", UseClock,
      Vector("java.lang.System#currentTimeMillis", "java.lang.System#nanoTime", "java.time.Instant#now",
        "java.time.LocalDate#now", "java.time.LocalTime#now", "java.time.LocalDateTime#now",
        "java.time.ZonedDateTime#now", "java.time.OffsetDateTime#now", "java.time.OffsetTime#now", "java.time.Year#now",
        "java.time.YearMonth#now", "java.time.MonthDay#now", "java.time.Clock#system*", "java.time.InstantSource#system",
        "java.time.chrono.HijrahDate#now", "java.time.chrono.JapaneseDate#now", "java.time.chrono.MinguoDate#now",
        "java.time.chrono.ThaiBuddhistDate#now", "java.time.chrono.Chronology#dateNow", "java.util.Date#<init>()")),
    Preset("fresh", "fresh: a random number or id is nondeterminism — replay breaks", UseFresh,
      Vector("java.util.UUID#randomUUID", "java.util.Random", "java.util.SplittableRandom",
        "java.util.concurrent.ThreadLocalRandom", "java.security.SecureRandom", "java.util.random.",
        "java.lang.Math#random", "java.lang.StrictMath#random", "scala.util.Random")),
    Preset("async", "async: threads, executors and monitors are the runtime's", UseAsync,
      Vector("java.lang.Thread", "java.lang.ThreadGroup", "java.util.concurrent.ExecutorService",
        "java.util.concurrent.Executors", "java.util.concurrent.ForkJoinPool", "java.util.concurrent.CompletableFuture",
        "java.util.Timer", "java.lang.Object#wait", "java.lang.Object#notify", "java.lang.Object#notifyAll",
        "scala.concurrent.ExecutionContext$Implicits$", "scala.concurrent.Future")),
    Preset("io", "io: files, sockets and databases are reached through an effect whose handler does it", UseIo,
      Vector("java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader",
        "java.io.FileWriter", "java.io.RandomAccessFile", "java.nio.file.", "java.nio.channels.", "java.net.",
        "java.sql.", "javax.sql.", "scala.io.Source")),
    Preset("nested-run", "nested run: a program runs no program to its end — the host runs one, at its edge",
      UseNested, Vector("okay.rows.Effects#run", "okay.rows.Effects#step", "okay.rows.std.Async#start",
        "okay.rows.std.Async#toFuture", "okay.rows.std.Async#runBlocking", "okay.rows.std.Emit#pull",
        "okay.core.Eff#run", "okay.core.Kernel#run", "okay.direct.Effects", "okay.direct.MultiEffects",
        "okay.scala3.Prog$package$#run", "okay.scala3.Cek$#run", "okay.safe.Host$package$#run",
        "okay.safe.Host$package$#step", "okay.safe.Machine$#run", "okay.$bang$#run")),
    Preset("escape", escape, "", Escapes.flatMap(_.apis)))

  /** okay-java's former `strict` list: the network, files, databases, processes, reflection and its escapes */
  val Strict: Vector[Rule] = Vector("java.net.", "java.nio.file.", "java.nio.channels.", "java.io.File",
    "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader", "java.io.FileWriter",
    "java.io.RandomAccessFile", "java.sql.", "javax.sql.", "java.lang.reflect.", "java.lang.invoke.MethodHandles",
    "java.lang.invoke.VarHandle", "sun.misc.Unsafe", "jdk.internal.", "java.lang.ProcessBuilder",
    "java.lang.Runtime#exec", "java.lang.Runtime#halt", "java.lang.Runtime#exit", "java.lang.System#exit",
    "java.lang.System#load", "java.lang.System#loadLibrary", "java.lang.Class#forName", "java.lang.Thread",
    "java.util.concurrent.Executors").map(api => Rule(api, "strict: reaches the world other than by an effect",
      INSTEAD.getOrElse(api, UseIo)))

  private lazy val INSTEAD: Map[String, String] =
    all.reverse.flatMap(p => p.apis.map(_ -> p.use)).toMap

  def byName(name: String): Option[Preset] = all.find(_.name == name)

  /** a list as a build gives it, comma or space separated: preset names, `pure` (every effect preset), `strict`, and
    * entries of its own (okay-java's `java.net.*` and `java.lang.System.exit` spellings are rules' spellings too) */
  def rules(list: String): Vector[Rule] =
    list.split("[,\\s]+").toVector.filter(_.nonEmpty).flatMap {
      case "pure" => all.filter(_.name != "escape").flatMap(_.rules)
      case "strict" => Strict
      case "default" => Boundary.Default
      case name => byName(name).fold(Vector(Rule(name, "named by the build", INSTEAD.getOrElse(name, ""))))(_.rules)
    }.distinctBy(_.api)
