package okay.audit

/** one instruction of a method under a rule: its class, method, line, what it does, the rule; `said` when it is
  * listed and not failed (a static field read that is not final: it may be a constant nothing writes) */
final case class Hit(cls: String, method: String, descriptor: String, line: Int, site: Site, rule: Rule, said: Boolean):
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
  * its constructor, is a hit; such a field read is said. okay-java's resumable transformation calls it on the
  * methods it makes resumable (effectful code); `Audit.run` on every method of a business module. */
object Code:
  /** the storage of a Scala lazy val (`x$lzy1`, `bitmap$0`): the compiler's idiom, written once, not state */
  def lazyStorage(name: String): Boolean = name.contains("$lzy") || name.startsWith("bitmap$") ||
    name.startsWith("0bitmap$")

  def check(info: ClassInfo, inScope: MethodInfo => Boolean, rules: Vector[Rule], classes: Classes): Vector[Hit] =
    val state = rules.exists(_.api == "state")
    val stateRule = rules.find(_.api == "state").getOrElse(Presets.State)
    val apis = rules.filter(r => r.api != "state")
    val out = Vector.newBuilder[Hit]
    for m <- info.methods if inScope(m); s <- m.sites if !Boundary.isBootstrap(s.ref) do
      def hit(rule: Rule, said: Boolean) = out += Hit(info.name, m.name, m.descriptor, s.line, s, rule, said)
      apis.find(_.matchesUp(s.ref, classes.supers)) match
        case Some(rule) => hit(rule, said = false)
        case None if state =>
          val owner = s.ref.owner
          lazy val module = classes.get(owner).exists(_.isModule)
          lazy val field = classes.field(owner, s.ref.name)
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
        case None =>
    out.result()

/** what the boundary rule on types decides of one type: None when it is first-order data, else why not.
  * `layer`: the layer a scanned class belongs to (business wins where a class is on several rows); `subclasses`:
  * every scanned class that extends `c`, directly or not. Recursion: bounded by the type's own nesting and, through
  * classes' fields, by `seen` — a class is entered once per path */
final class Judge(types: Types, classes: Classes, layer: String => Option[Layer], subclasses: String => Vector[String]):
  def data(t: JType, seen: Set[String] = Set.empty): Option[String] = t match
    case JType.Base(_) | JType.Var(_) => None
    case JType.Arr(of) => Some(s"an array, ${t.show}: shared and mutable, not a value")
    case JType.Cls(name, args) =>
      if Types.Anything(name) then Some(s"$name says nothing of what a value is: its toString, equals and hashCode " +
        "are the value's own — a host's, run by the program")
      else if Types.function(name) then Some(s"a function, ${t.show}: code of whoever made it — a host's lambda is " +
        "IO in disguise, with what it captured")
      else if seen(name) then None
      else if seen.size >= Judge.MaxClasses then Some(s"$name: data nested through more than ${Judge.MaxClasses} classes")
      else
        val own: Option[String] =
          if Types.listed(types.data, name) then None
          else layer(name) match
            case Some(Layer.Handlers) => Some(s"$name is the host's: a handlers module's class")
            case Some(Layer.Business) | Some(Layer.Runtime) => closed(name, seen + name)
            case _ => Some(s"$name is not data: a class of a library")
        own.orElse(args.iterator.flatMap {
          case JArg.Star => Some(s"${t.show}: an unbounded wildcard says nothing of what a value is")
          case JArg.Exact(x) => data(x, seen); case JArg.Extends(x) => data(x, seen); case JArg.Super(x) => data(x, seen)
        }.nextOption())

  /** a business or runtime class: final, or closed — nothing of the host's extends it, and what does extend it is
    * data —, its fields final and data */
  private def closed(name: String, seen: Set[String]): Option[String] =
    classes.get(name) match
      case None => Some(s"$name is not data: its class file was not found")
      case Some(c) =>
        val subs = if c.isFinal then Vector.empty else subclasses(name)
        val open = subs.find(s => layer(s).contains(Layer.Handlers))
          .map(h => s"$name is neither final nor closed: $h, of the host's, extends it")
          .orElse(subs.filterNot(seen).iterator.flatMap(s => closed(s, seen + s)).nextOption())
        open.orElse(c.fields.filterNot(_.isStatic).iterator.flatMap { f =>
          if !f.isFinal then Some(s"$name has a var, ${f.name}")
          else data(Signatures.field(f.signature.getOrElse(f.descriptor)), seen).map(why => s"$name.${f.name}: $why")
        }.nextOption())

object Judge:
  /** the deepest path of classes the judge follows through fields and subclasses: past it, not data */
  val MaxClasses = 256
