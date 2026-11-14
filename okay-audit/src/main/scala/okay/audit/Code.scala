package okay.audit

/** one instruction of a method under a rule: its class, method, line, what it does, the rule; `said` when it is
  * listed and not failed (a static field read that is not final: it may be a constant nothing writes); `holder` for a
  * held collection changed in place, the field and what it is (`C.f, a static field holding a mutable collection`) */
final case class Hit(cls: String, method: String, descriptor: String, line: Int, site: Site, rule: Rule, said: Boolean,
                     holder: String = ""):
  /** `calls exit of`, `constructs`, `writes count of`, `reads cache of` — as okay-java's messages said it */
  def what: String = site.op match
    case Site.GetStatic | Site.GetField => s"reads ${site.ref.name} of"
    case Site.PutStatic | Site.PutField => s"writes ${site.ref.name} of"
    case Site.New => "constructs"
    case _ => if site.ref.name == "<init>" then "constructs" else s"calls ${site.ref.name} of"

  /** `fixture.Clocky.now (line 4) calls currentTimeMillis of java.lang.System` */
  def where: String = s"$cls.$method (line $line) $what ${site.ref.owner}"

/** THE CHECK ON CODE: the instructions of chosen methods held to rules, with their lines. The same rules as the
  * constant-pool scan, matched up the owner's hierarchy, plus the one rule only code can show — STATE: a static
  * field that is not final written outside its class's initializer, or a field of a Scala `object` written outside
  * its constructor, is a hit; such a field read is said. A HELD COLLECTION is one too: a field of a mutable
  * collection's or cell's type, static or a Scala object's, loaded and then mutated (put, update, +=, …) outside the
  * owner's initializer; an instance field's the same is said. okay-java's resumable transformation calls it on the
  * methods it makes resumable (effectful code); `Audit.run` on every method of a business module. */
object Code:
  /** for a Java caller (okay-java's transformation): a class file, the methods in scope as `name + descriptor`, the
    * rules as a build names them (`Presets.rules`) */
  def check(bytes: Array[Byte], methods: java.util.Collection[String], rules: String, classes: Classes): java.util.List[Hit] =
    import scala.jdk.CollectionConverters.*
    val keys = methods.asScala.toSet
    check(Scan.parse(bytes), m => keys(m.name + m.descriptor), Presets.rules(rules), classes).asJava

  /** the storage of a Scala lazy val (`x$lzy1`, `bitmap$0`): the compiler's idiom, written once, not state */
  def lazyStorage(name: String): Boolean = name.contains("$lzy") || name.startsWith("bitmap$") ||
    name.startsWith("0bitmap$")

  def check(info: ClassInfo, inScope: MethodInfo => Boolean, rules: Vector[Rule], classes: Classes): Vector[Hit] =
    val state = rules.exists(_.api == "state")
    val stateRule = rules.find(_.api == "state").getOrElse(Presets.State)
    val apis = rules.filter(r => r.api != "state")
    val out = Vector.newBuilder[Hit]
    // a `new` is followed by its constructor's call, which names the same class: the call is the one counted; the
    // other class-naming sites (checkcast, instanceof, ldc) only place a reference for a report
    for m <- info.methods if inScope(m) do
      // the held-collection rule: a field load's (or an object's accessor's) site index -> (the field's owner, name,
      // type, what it is); a mutating call whose receiver (`Site.recv`) is one of them is the finding
      val held = scala.collection.mutable.Map.empty[Int, (String, String, String, Holder)]
      for (s, i) <- m.sites.zipWithIndex
          if s.isMember && !Boundary.isBootstrap(s.ref) && !Boundary.excused(info, m, i) do
        def hit(rule: Rule, said: Boolean): Unit = out += Hit(info.name, m.name, m.descriptor, s.line, s, rule, said)
        apis.find(_.matchesUp(s.ref, classes.supers)) match
          case Some(rule) => hit(rule, said = false)
          case None if state =>
            val owner = s.ref.owner
            lazy val module = classes.get(owner).exists(_.isModule)
            lazy val field = classes.field(owner, s.ref.name)
            lazy val initializing = (m.name == "<clinit>" || m.name == "<init>") && owner == info.name
            s.op match
              case Site.PutStatic if !(m.name == "<clinit>" && owner == info.name) && !lazyStorage(s.ref.name) =>
                hit(stateRule, said = false)
              case Site.GetStatic if !field.exists(_.isFinal) && !lazyStorage(s.ref.name) =>
                hit(stateRule, said = true)
              case Site.PutField if !(m.name == "<init>" && owner == info.name) && !lazyStorage(s.ref.name) && module =>
                hit(stateRule, said = false)
              case Site.GetField if !lazyStorage(s.ref.name) && module && !field.exists(_.isFinal) =>
                hit(stateRule, said = true)
              case _ =>
            // HELD COLLECTIONS: a field of a mutable collection's type loaded (or read through an object's accessor),
            // then a mutating call on that very value (the call's receiver), outside the owner's initializer
            s.op match
              case Site.GetStatic | Site.GetField if !lazyStorage(s.ref.name) && s.ref.name != "MODULE$" =>
                if !initializing then field.map(f => typeName(f.descriptor)).filter(mutable(_, classes)).foreach { t =>
                  val holder = if module then Holder.Module
                    else if s.op == Site.GetStatic then Holder.Static else Holder.Instance
                  held(i) = (owner, s.ref.name, t, holder)
                }
              case _ if s.ref.descriptor.startsWith("()") && module && !initializing =>
                classes.field(owner, s.ref.name).filterNot(_.isStatic).map(f => typeName(f.descriptor))
                  .filter(mutable(_, classes)).foreach(t => held(i) = (owner, s.ref.name, t, Holder.Module))
              case _ if Mutating(s.ref.name) && mutable(owner, classes) =>
                // the call is on the held field's type (or a supertype of it): another collection's call is not its
                held.remove(s.recv).filter((_, _, t, _) => classes.supers(t).contains(owner)).foreach { (o, f, t, h) =>
                  out += Hit(info.name, m.name, m.descriptor, s.line, s, Presets.held(stateRule, o, f, t, h.global),
                    said = !h.global, holder = s"$o.$f, ${h.text}")
                }
              case _ =>
          case None =>
    out.result()

  /** a field descriptor's class (`Lscala/collection/mutable/Map;` -> `scala.collection.mutable.Map`), else "" */
  def typeName(descriptor: String): String =
    if descriptor.startsWith("L") && descriptor.endsWith(";") then descriptor.substring(1, descriptor.length - 1)
      .replace('/', '.') else ""

  /** the JDK's and Scala's mutable collections and cells: a type that is one, or extends one */
  private val MutableJdk = Set("Map", "List", "Set", "Collection", "Deque", "Queue", "HashMap", "ArrayList",
    "LinkedList", "HashSet", "TreeMap", "TreeSet", "LinkedHashMap", "LinkedHashSet", "ArrayDeque", "PriorityQueue",
    "IdentityHashMap", "WeakHashMap", "EnumMap", "EnumSet", "Vector", "Stack", "Hashtable", "Properties", "BitSet",
    "SortedMap", "NavigableMap", "SortedSet", "NavigableSet").map("java.util." + _)
  private val MutablePrefixes = Vector("scala.collection.mutable.", "scala.collection.concurrent.",
    "java.util.concurrent.")

  def mutable(name: String, classes: Classes): Boolean =
    name.nonEmpty && !name.endsWith("$") &&   // a companion object (`mutable.Map$`) is a factory, not a collection
      classes.supers(name).exists(n => MutableJdk(n) || MutablePrefixes.exists(n.startsWith))

  /** the methods that change a collection or a cell in place */
  val Mutating: Set[String] = Set("put", "putAll", "putIfAbsent", "remove", "removeIf", "removeAll", "retainAll", "add",
    "addAll", "addFirst", "addLast", "offer", "offerFirst", "offerLast", "poll", "pollFirst", "pollLast", "push",
    "pop", "clear", "set", "update", "updateWith", "getOrElseUpdate", "addOne", "subtractOne", "subtractAll",
    "$plus$eq", "$plus$plus$eq", "$minus$eq", "$minus$minus$eq", "$plus$eq$colon", "$plus$plus$eq$colon", "prepend",
    "append", "appendAll", "prependAll", "insert", "insertAll", "compute", "computeIfAbsent", "computeIfPresent",
    "merge", "replace", "replaceAll", "filterInPlace", "mapValuesInPlace", "sortInPlace", "getAndSet",
    "compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile", "incrementAndGet",
    "decrementAndGet", "getAndIncrement", "getAndDecrement", "addAndGet", "getAndAdd", "updateAndGet", "getAndUpdate",
    "accumulateAndGet", "getAndAccumulate", "lazySet", "setPlain", "setRelease", "setOpaque", "enqueue", "dequeue",
    "dequeueAll", "trimStart", "trimEnd", "put$", "patchInPlace", "flipInPlace")

/** why a type is not first-order data: `text`; `erased` when the only trouble is a `java.lang.Object` of a Scala
  * class (an erased union, opaque type, boxed type argument or `Any`: unknown — said, not failed); `suspect` the
  * innermost business or runtime class, final with final fields, whose own field is the leaf that failed (an array,
  * `Object`, a library's class) — a candidate for `types.data` if its author vouches it is an immutable value */
final case class Why(text: String, erased: Boolean = false, suspect: Option[String] = None, leaf: Boolean = false)

/** what the boundary rule on types decides of one type: None when it is first-order data, else why not.
  * `layer`: the layer a scanned class belongs to (business wins where a class is on several rows); `subclasses`:
  * every scanned class that extends `c`, directly or not. `scala`: the type is read from a Scala class's descriptor
  * or signature, where `Object` may be erased. A definite reason wins over an erased one. Recursion: bounded by the
  * type's own nesting and, through classes' fields, by `seen` — a class is entered once per path */
final class Judge(types: Types, classes: Classes, layer: String => Option[Layer], subclasses: String => Vector[String]):
  def data(t: JType, seen: Set[String] = Set.empty, scala: Boolean = false): Option[Why] = t match
    case JType.Base(_) | JType.Var(_) => None
    case JType.Arr(of) => Some(Why(s"an array, ${t.show}: shared and mutable, not a value", leaf = true))
    case JType.Cls(name, args) =>
      if name == "java.lang.Object" && scala then Some(Why("unknown (erased): java.lang.Object in a Scala " +
        "signature — a union, an opaque type, a boxed type argument or Any; only TASTy could say which", erased = true))
      else if Types.Anything(name) then Some(Why(s"$name says nothing of what a value is: its toString, equals and " +
        "hashCode are the value's own — a host's, run by the program", leaf = true))
      else if Types.function(name) then Some(Why(s"a function, ${t.show}: code of whoever made it — a host's lambda " +
        "is IO in disguise, with what it captured"))
      // a program is a value the runtime runs; row evidence a place, its type arguments phantom (Types.evidence)
      else if seen(name) || Types.program(types.programs, name) || types.isEvidence(name) then None
      else if seen.size >= Judge.MaxClasses then
        Some(Why(s"$name: data nested through more than ${Judge.MaxClasses} classes"))
      else
        val own: Option[Why] =
          if Types.listed(types.data, name) then None
          else layer(name) match
            case Some(Layer.Handlers) => Some(Why(s"$name is the host's: a handlers module's class"))
            case Some(Layer.Business) | Some(Layer.Runtime) => closed(name, seen + name)
            case _ => Some(Why(s"$name is not data: a class of a library", leaf = true))
        Judge.first(own.iterator ++ args.iterator.flatMap {
          case JArg.Star => Some(Why(s"${t.show}: an unbounded wildcard says nothing of what a value is"))
          case JArg.Exact(x) => data(x, seen, scala); case JArg.Extends(x) => data(x, seen, scala)
          case JArg.Super(x) => data(x, seen, scala)
        })

  /** a business or runtime class: final, or closed — nothing of the host's extends it, and what does extend it is
    * data —, its fields final and data; a lazy val's cell (`x$lzyN`, `bitmap$N`) is the compiler's, not a var */
  private def closed(name: String, seen: Set[String]): Option[Why] =
    classes.get(name) match
      case None => Some(Why(s"$name is not data: its class file was not found"))
      case Some(c) =>
        val subs = if c.isFinal then Vector.empty else subclasses(name)
        val open = subs.find(s => layer(s).contains(Layer.Handlers))
          .map(h => Why(s"$name is neither final nor closed: $h, of the host's, extends it"))
          .orElse(Judge.first(subs.filterNot(seen).iterator.flatMap(s => closed(s, seen + s))))
        val fields = c.fields.filterNot(f => f.isStatic || Code.lazyStorage(f.name))
        val vouchable = c.isFinal && fields.forall(_.isFinal)
        open.orElse(Judge.first(fields.iterator.flatMap { f =>
          if !f.isFinal then Some(Why(s"$name has a var, ${f.name}"))
          else data(Signatures.field(f.signature.getOrElse(f.descriptor)), seen, c.scala).map { why =>
            Why(s"$name.${f.name}: ${why.text}", why.erased,
              why.suspect.orElse(Option.when(why.leaf && vouchable && !why.erased)(name)))
          }
        }))

object Judge:
  /** the deepest path of classes the judge follows through fields and subclasses: past it, not data */
  val MaxClasses = 256

  /** the first definite reason, else the first erased one */
  def first(whys: Iterator[Why]): Option[Why] =
    var erased = Option.empty[Why]
    var definite = Option.empty[Why]
    while definite.isEmpty && whys.hasNext do
      val w = whys.next()
      if w.erased then { if erased.isEmpty then erased = Some(w) } else definite = Some(w)
    definite.orElse(erased)

/** what holds a mutable collection changed in place: a static field or a Scala object's (global state, a finding), an
  * instance's (said) */
enum Holder(val text: String, val global: Boolean):
  case Static extends Holder("a static field holding a mutable collection", true)
  case Module extends Holder("a field of a Scala object holding a mutable collection", true)
  case Instance extends Holder("an instance's field holding a mutable collection", false)
