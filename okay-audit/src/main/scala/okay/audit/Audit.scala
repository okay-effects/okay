package okay.audit

import java.nio.file.Path

/** a reach from where none should be: a business module's reference under a rule */
final case class Finding(module: String, ref: Ref, rule: Rule, at: String = ""):
  /** ` in run (line 12)` where the code check found it, else nothing */
  def place: String = if at.isEmpty then "" else s" in $at"

/** what a handler or runtime module touches, by provider — the owner's top
  * package (`java.sql`, `com.zaxxer.hikari`): the DORA Art. 8 inventory line */
final case class Inventory(module: String, layer: Layer, byProvider: Map[String, Vector[Ref]])

final case class Report(
    inputs: Vector[(String, Path, String)],   // module, path, sha-256
    findings: Vector[Finding],                // business modules only; empty is the pass
    inventory: Vector[Inventory],             // handlers and runtime
    untracked: Vector[String],                // modules with no layer
    unusedAllows: Vector[Allow],
    allowed: Vector[(Allow, Finding)],        // what the allows swallowed, so nothing is silent
    jpms: JpmsEvidence = JpmsEvidence(Vector.empty, Vector.empty, Vector.empty),
    said: Vector[Finding] = Vector.empty):    // listed, not failed: a non-final static read (state rule)
  def passed: Boolean = findings.isEmpty

  def text: String =
    val sb = StringBuilder()
    sb ++= (if passed then "audit: PASS — no business module reaches past the boundary\n"
            else s"audit: FAIL — ${findings.size} reach(es) past the boundary\n")
    sb ++= s"carve-out (compiler bootstraps, never a finding): ${Boundary.Bootstraps.mkString(", ")}; bare class references to ${Boundary.BootstrapTypes.toVector.sorted.mkString(", ")}; the lazy-val idiom ${Boundary.LazyValIdiom.toVector.sorted.mkString(", ")}, java.lang.invoke.VarHandle\n\n"
    if findings.nonEmpty then
      sb ++= "FINDINGS\n"
      findings.groupBy(_.module).toVector.sortBy(_._1).foreach { (m, fs) =>
        sb ++= s"  $m\n"
        fs.sortBy(f => (f.ref.from, f.ref.member)).foreach { f =>
          sb ++= s"    ${f.ref.from} -> ${f.ref.member}${desc(f.ref)}${f.place}  [${f.rule.api}: ${f.rule.why}${f.rule.hint}]\n"
        }
      }
      sb ++= "\n"
    if said.nonEmpty then
      sb ++= "SAID (listed, not failed)\n"
      said.sortBy(f => (f.module, f.ref.from, f.ref.member, f.at)).foreach { f =>
        sb ++= s"  ${f.module}: ${f.ref.from} -> ${f.ref.member}${f.place}  [${f.rule.api}: ${f.rule.why}${f.rule.hint}]\n"
      }
      sb ++= "\n"
    if allowed.nonEmpty then
      sb ++= "ALLOWED (named exceptions)\n"
      allowed.sortBy((a, f) => (a.module, f.ref.member)).foreach { (a, f) =>
        sb ++= s"  ${a.module}: ${f.ref.from} -> ${f.ref.member}  allowed by ${a.owner}: ${a.reason}\n"
      }
      sb ++= "\n"
    if unusedAllows.nonEmpty then
      sb ++= "UNUSED ALLOWS\n"
      unusedAllows.foreach(a => sb ++= s"  ${a.module}: ${a.api} (${a.owner})\n")
      sb ++= "\n"
    sb ++= "INVENTORY (what the handlers and the runtime touch, by provider)\n"
    inventory.sortBy(i => (i.layer.ordinal, i.module)).foreach { inv =>
      sb ++= s"  ${inv.module} [${inv.layer.toString.toLowerCase}]\n"
      inv.byProvider.toVector.sortBy(_._1).foreach { (prov, refs) =>
        sb ++= s"    $prov: ${refs.map(_.member).distinct.sorted.mkString(", ")}\n"
      }
    }
    if untracked.nonEmpty then sb ++= s"\nUNTRACKED (no layer declared): ${untracked.sorted.mkString(", ")}\n"
    sb ++= "\nJPMS (descriptor evidence; enforcement requires named-module deployment)\n"
    jpms.modules.foreach { m =>
      sb ++= s"  ${m.module}  ${m.path}  ${m.name.getOrElse("unnamed")}\n"
      if m.requires.nonEmpty then sb ++= s"    requires: ${m.requires.mkString(", ")}\n"
      sb ++= s"    enforcement: ${m.enforcement.map((r, e) => s"$r=${e.text}").mkString(", ")}\n"
    }
    jpms.splits.foreach(x => sb ++= s"  split package ${x.name}: ${x.inputs.mkString(", ")}\n")
    if jpms.launcherOptions.nonEmpty then sb ++= s"  launcher: ${jpms.launcherOptions.mkString(" ")}\n"
    sb ++= "\nINPUTS\n"
    inputs.sortBy(i => (i._1, i._2.toString)).foreach((m, p, h) => sb ++= s"  $m  $p  sha256:$h\n")
    sb.result()

  def json: String =
    def s(x: String) = "\"" + x.flatMap {
      case '"' => "\\\""; case '\\' => "\\\\"; case '\n' => "\\n"; case c if c < ' ' => f"\\u${c.toInt}%04x"; case c => c.toString
    } + "\""
    def ref(r: Ref) = s"""{"from":${s(r.from)},"kind":${s(r.kind.toString)},"owner":${s(r.owner)},"name":${s(r.name)},"descriptor":${s(r.descriptor)}}"""
    def finding(f: Finding) =
      s"""{"module":${s(f.module)},"ref":${ref(f.ref)},"rule":${s(f.rule.api)},"why":${s(f.rule.why)},""" +
        s""""use":${s(f.rule.use)},"at":${s(f.at)}}"""
    val fs = findings.sortBy(f => (f.module, f.ref.from, f.ref.member, f.at)).map(finding)
    val sd = said.sortBy(f => (f.module, f.ref.from, f.ref.member, f.at)).map(finding)
    val inv = inventory.sortBy(i => (i.layer.ordinal, i.module)).map { i =>
      val provs = i.byProvider.toVector.sortBy(_._1).map((p, rs) =>
        s"""${s(p)}:[${rs.map(_.member).distinct.sorted.map(s).mkString(",")}]""")
      s"""{"module":${s(i.module)},"layer":${s(i.layer.toString.toLowerCase)},"providers":{${provs.mkString(",")}}}"""
    }
    val ins = inputs.sortBy(i => (i._1, i._2.toString)).map((m, p, h) => s"""{"module":${s(m)},"path":${s(p.toString)},"sha256":${s(h)}}""")
    val al = allowed.sortBy((a, f) => (a.module, f.ref.member)).map((a, f) =>
      s"""{"module":${s(a.module)},"api":${s(a.api)},"owner":${s(a.owner)},"reason":${s(a.reason)},"ref":${ref(f.ref)}}""")
    val un = unusedAllows.map(a => s"""{"module":${s(a.module)},"api":${s(a.api)},"owner":${s(a.owner)}}""")
    val jm = jpms.modules.map(m => s"""{"module":${s(m.module)},"path":${s(m.path.toString)},"name":${m.name.map(s).getOrElse("null")},"requires":[${m.requires.map(s).mkString(",")}],"enforcement":{${m.enforcement.map((r, e) => s"${s(r)}:${s(e.text)}").mkString(",")}}}""")
    val splits = jpms.splits.map(x => s"""{"package":${s(x.name)},"inputs":[${x.inputs.map(s).mkString(",") }]}""")
    s"""{"passed":$passed,"bootstraps":[${Boundary.Bootstraps.map(s).mkString(",")}],"findings":[${fs.mkString(",")}],"said":[${sd.mkString(",")}],""" +
      s""""allowed":[${al.mkString(",")}],"unusedAllows":[${un.mkString(",")}],"inventory":[${inv.mkString(",")}],""" +
      s""""untracked":[${untracked.sorted.map(s).mkString(",")}],"inputs":[${ins.mkString(",")}],"jpms":{"modules":[${jm.mkString(",")}],"splits":[${splits.mkString(",")}],"launcherOptions":[${jpms.launcherOptions.map(s).mkString(",")}]}}"""

  private def desc(r: Ref) = if r.kind == Ref.Kind.Native then " (native method)" else ""

object Audit:
  def runtime(): RuntimeEvidence = Jpms.runtime()
  /** a refusal before any scan: an allow without its reason or owner */
  final class Refused(msg: String) extends IllegalArgumentException(msg)

  /** the owner's top package: `java.sql.Connection` -> `java.sql`; a bare name -> itself */
  def provider(owner: String): String =
    val parts = owner.split('.')
    if parts.length <= 2 then parts.dropRight(1).mkString(".") match { case "" => owner; case p => p }
    else
      // two segments, or three for the `com.x.y` / `org.x.y` / `io.x.y` shape
      val n = if Set("com", "org", "io", "net", "dev").contains(parts(0)) && parts.length > 3 then 3 else 2
      parts.take(n).mkString(".")

  /** the rules that name a reach (a business finding, an inventory line): every rule but `state`, which is code's */
  private def reaching(boundary: Boundary): Vector[Rule] = boundary.rules.filter(_.api != "state")

  /** `modules`: name -> the class directories and jars that ARE that module
    * (its own classes and its classpath; a jar inherits the module's layer) */
  def run(boundary: Boundary, modules: Map[String, Seq[Path]], launcherOptions: Vector[String] = Vector.empty): Report =
    boundary.allows.find(a => a.reason.trim.isEmpty || a.owner.trim.isEmpty).foreach { a =>
      throw Refused(s"allow ${a.module}: ${a.api} has no ${if a.reason.trim.isEmpty then "reason" else "owner"} — an exception is a named decision")
    }
    val inputs = Vector.newBuilder[(String, Path, String)]
    val findings = Vector.newBuilder[Finding]
    val said = Vector.newBuilder[Finding]
    val inventory = Vector.newBuilder[Inventory]
    val untracked = Vector.newBuilder[String]
    val allowed = Vector.newBuilder[(Allow, Finding)]
    val used = scala.collection.mutable.Set.empty[Allow]
    val jpmsInputs = modules.toVector.sortBy(_._1).filter { (module, _) =>
      boundary.layers.getOrElse(module, Layer.Untracked) != Layer.Untracked || boundary.packages.get(module).exists(_.nonEmpty)
    }.flatMap((module, paths) => paths.map(module -> _))
    val jpms = Jpms.evidence(jpmsInputs, launcherOptions)
    val classes = Classes(modules.toVector.sortBy(_._1).flatMap(_._2).distinct)
    val rules = reaching(boundary)
    val state = boundary.rules.exists(_.api == "state")
    // the code of business classes is read when a rule needs it (state) or the boundary on types does
    val code = state || boundary.types.nonEmpty
    // which layer each scanned class is on (business wins where a class is on several rows): the allowlist and the
    // boundary on types ask it of a referenced class
    val wide = boundary.allowlist.nonEmpty || boundary.types.nonEmpty
    val classLayer: Map[String, Layer] =
      if !wide then Map.empty
      else modules.toVector.flatMap { (module, paths) =>
        paths.flatMap(p => Scan.names(p)).map(c => c -> boundary.layerOf(module, c))
      }.groupMapReduce(_._1)(_._2)((a, b) => if a == Layer.Business || b == Layer.Business then Layer.Business
        else if a == Layer.Handlers || b == Layer.Handlers then Layer.Handlers else if a == Layer.Runtime then a else b)
    // what each row holds, for the boundary on types: business classes with their code, host classes' headers
    val business = Vector.newBuilder[(String, ClassInfo)]
    val hosts = Vector.newBuilder[(String, ClassInfo)]
    def report(module: String, f: Finding): Unit =
      boundary.allows.find(_.matches(module, f.ref)) match
        case Some(a) => used += a; allowed += ((a, f))
        case None => findings += f
    modules.toVector.sortBy(_._1).foreach { (module, paths) =>
      val layer = boundary.layers.getOrElse(module, Layer.Untracked)
      // untracked: no layer of its own, and nothing in `packages` could cover it
      if layer == Layer.Untracked && boundary.packages.get(module).forall(_.isEmpty) then untracked += module
      else
        val infos = paths.toVector.flatMap { p =>
          inputs += ((module, p, Scan.digest(p)))
          Scan.classes(p, code)
        }
        // a class under a package prefix belongs to that prefix's layer and is
        // reported under `module:prefix`; the rest of the module under its own
        val byRow: Map[(String, Layer), Vector[ClassInfo]] = infos.groupBy { c =>
          boundary.prefixOf(module, c.name) match
            case Some(p) => (s"$module:$p", boundary.packages(module)(p))
            case None => (module, layer)
        }
        // a class no prefix covers, in a module with no layer of its own, is untracked
        if layer == Layer.Untracked && (byRow.isEmpty || byRow.contains((module, Layer.Untracked))) then untracked += module
        byRow.toVector.sortBy(_._1._1).foreach { case ((row, rowLayer), cs) =>
          val rs = cs.flatMap(_.refs).filterNot(Boundary.isBootstrap)
          rowLayer match
            case Layer.Untracked =>
            case Layer.Business =>
              val own = cs.iterator.map(_.name).toSet
              rs.foreach { r =>
                val rule =
                  if r.kind == Ref.Kind.Native then Some(Boundary.Native)
                  else rules.find(_.matchesUp(r, classes.supers)).orElse(boundary.allowlist.flatMap { list =>
                    if own(r.owner) then None
                    else classLayer.get(r.owner) match
                      case Some(Layer.Runtime) => None
                      case Some(Layer.Handlers) => Some(Audit.NamesHandler)
                      case _ => if list.allows(r) then None else Some(Audit.NotAllowed)
                  })
                rule.foreach(rule => report(module, Finding(row, r, rule)))
              }
              if state then cs.foreach { c =>
                Code.check(c, _ => true, Vector(Presets.State), classes).foreach { h =>
                  val f = Finding(row, h.site.ref.copy(from = c.name), h.rule, s"${h.method} (line ${h.line})")
                  if h.said then said += f else report(module, f)
                }
              }
              if boundary.types.nonEmpty then business ++= cs.map(row -> _)
            case _ =>
              // the inventory lists what the row touches OUTSIDE itself and the JDK's pure core:
              // every reference under a rule is a reach; the rest is plain computation
              val reaches = rs.filter(r => r.kind == Ref.Kind.Native || rules.exists(_.matches(r)))
              inventory += Inventory(row, rowLayer, reaches.groupBy(r => provider(r.owner)))
              if boundary.types.nonEmpty then hosts ++= cs.map(row -> _)
        }
    }
    boundary.types.foreach { types =>
      typeBoundary(types, business.result(), hosts.result(), classLayer, classes).foreach((m, f) => report(m, f))
    }
    classes.close()
    val scanned = inputs.result()
    Report(scanned, findings.result(), inventory.result(), untracked.result(),
      boundary.allows.filterNot(used.contains), allowed.result(), jpms, said.result())

  /** a business reference to a handlers module's class (allowlist mode, and the boundary on types) */
  val NamesHandler: Rule = Rule("handlers", "names a handler's class: business code reaches a handler only through " +
    "its effect", "perform the effect; the host installs its handler")
  /** a business reference no rule names and no allowlist entry allows (allowlist mode) */
  val NotAllowed: Rule = Rule("allowlist", "not on the allowlist: business code names only what is allowed (deny by " +
    "default)")
  /** the boundary rule on types */
  def boundaryRule(why: String): Rule = Rule("boundary", why, "first-order data only: a value of the program's " +
    "own, or of the data list — what reaches the world stays in a handler, the program holds ids")

  /** THE BOUNDARY RULE ON TYPES over the rows read: what the host gives a business method, an operation's answer,
    * and a business class naming the host's type. Findings by module (the row's module), for `report` */
  private def typeBoundary(types: Types, business: Vector[(String, ClassInfo)], hosts: Vector[(String, ClassInfo)],
                           classLayer: Map[String, Layer], classes: Classes): Vector[(String, Finding)] =
    val all = (business ++ hosts).map(_._2)
    val subclassCache = scala.collection.mutable.HashMap.empty[String, Vector[String]]
    def subclasses(name: String): Vector[String] = subclassCache.getOrElseUpdate(name,
      all.filter(c => c.name != name && classes.supers(c.name).contains(name)).map(_.name).sorted)
    val judge = Judge(types, classes, classLayer.get, subclasses)
    val out = Vector.newBuilder[(String, Finding)]
    def module(row: String) = row.takeWhile(_ != ':')
    val byName = business.map((_, c) => c.name -> c).toMap
    // the host gives a business method or constructor its arguments: data only
    for (row, h) <- hosts; r <- h.refs.distinct
        if (r.kind == Ref.Kind.Method || r.kind == Ref.Kind.InterfaceMethod) && byName.contains(r.owner) &&
          !classLayer.get(h.name).contains(Layer.Business) do
      val target = byName(r.owner).methods.find(m => m.name == r.name && m.descriptor == r.descriptor)
      val (params, _) = Signatures.method(target.flatMap(_.signature).getOrElse(r.descriptor))
      params.iterator.flatMap(judge.data(_)).nextOption().foreach { why =>
        out += module(row) -> Finding(row, r, boundaryRule(s"the host gives ${r.owner}#${r.name} what is not data: $why"))
      }
    for (row, c) <- business do
      // an operation's answer
      for sig <- c.signature; sup <- Signatures.supers(sig) do sup match
        case JType.Cls(effect, args) if types.answers.contains(effect) && args.nonEmpty =>
          val i = types.answers(effect)
          args.lift(if i < 0 then args.size + i else i).foreach {
            case JArg.Exact(answer) => judge.data(answer).foreach { why =>
              out += module(row) -> Finding(row, Ref(c.name, Ref.Kind.Class, effect, "", ""),
                boundaryRule(s"the answer of ${c.name}, an operation of $effect, must be first-order data: $why"))
            }
            case _ =>
          }
        case _ =>
      // the host's types named by business code: in its supertypes, fields, methods and references
      val named = (c.supers ++ c.signature.toVector.flatMap(s => Signatures.supers(s).flatMap(Signatures.classes)) ++
        c.fields.flatMap(f => Signatures.classes(Signatures.field(f.signature.getOrElse(f.descriptor)))) ++
        c.methods.flatMap { m =>
          val (ps, res) = Signatures.method(m.signature.getOrElse(m.descriptor))
          (ps :+ res).flatMap(Signatures.classes)
        } ++ c.refs.map(_.owner)).distinct
      named.filter(n => classLayer.get(n).contains(Layer.Handlers)).sorted.foreach { n =>
        out += module(row) -> Finding(row, Ref(c.name, Ref.Kind.Class, n, "", ""),
          boundaryRule(s"business code names the host's type $n"))
      }
    out.result()
