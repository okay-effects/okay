package okay.audit

import java.nio.file.{Files, Path}

/** a reach from where none should be: a business module's reference under a rule. `at`: the method and line of its
  * first site (`run (line 12)`), `class header` where no code makes it; `count`: how many sites (or, for the
  * boundary on types, how many places in the class) make it; `source`: the input (class directory or jar) it was
  * read from */
final case class Finding(module: String, ref: Ref, rule: Rule, at: String = "", count: Int = 1, source: String = ""):
  /** ` in run (line 12)` where the code check found it, ` (×3)` for more than one site, else nothing */
  def place: String = (if at.isEmpty then "" else s" in $at") + (if count > 1 then s" (×$count)" else "")

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
    said: Vector[Finding] = Vector.empty,     // listed, not failed: a non-final static read, an erased Object, ...
    elsewhere: Vector[(String, Layer)] = Vector.empty,   // modules audited in their own report: not repeated here
    suggestions: Vector[(String, Int)] = Vector.empty):  // `types.data` candidates and how many findings name them
  def passed: Boolean = findings.isEmpty

  /** findings read from a JAR (a library under a business row), by jar */
  def jars: Vector[JarRollup] = Report.rollup(findings)
  /** the same of what is said */
  def saidJars: Vector[JarRollup] = Report.rollup(said)

  def text: String =
    val sb = StringBuilder()
    sb ++= (if passed then "audit: PASS — no business module reaches past the boundary\n"
            else s"audit: FAIL — ${findings.size} reach(es) past the boundary\n")
    sb ++= s"carve-out (compiler bootstraps, never a finding): ${Boundary.Bootstraps.mkString(", ")}; bare class " +
      s"references to ${Boundary.BootstrapTypes.toVector.sorted.mkString(", ")}; the lazy-val idiom " +
      s"${Boundary.LazyValIdiom.toVector.sorted.mkString(", ")}, java.lang.invoke.VarHandle; Scala 3.3's lazy val " +
      s"(java.lang.Class#getDeclaredField of an x$$lzyN cell handed to ${Boundary.LazyVals33}); Scala static " +
      s"forwarders (C#m calling C$$#m)" +
      (if Presets.PureNet.nonEmpty then s"; pure values under java.net.: ${Presets.PureNet.mkString(", ")}" else "") +
      "\n\n"
    val jarred = (jars ++ saidJars).map(_.jar).toSet
    if findings.nonEmpty then
      sb ++= "FINDINGS\n"
      findings.filterNot(f => jarred(f.source)).groupBy(_.module).toVector.sortBy(_._1).foreach { (m, fs) =>
        sb ++= s"  $m\n"
        fs.sortBy(f => (f.ref.from, f.ref.member)).foreach { f =>
          sb ++= s"    ${f.ref.from} -> ${f.ref.member}${desc(f.ref)}${f.place}  [${f.rule.api}: ${f.rule.why}" +
            s"${f.rule.hint}]\n"
        }
      }
      Report.libraries(sb, jars)
      sb ++= "\n"
    if said.nonEmpty then
      sb ++= "SAID (listed, not failed)\n"
      said.filterNot(f => jarred(f.source)).sortBy(f => (f.module, f.ref.from, f.ref.member, f.at)).foreach { f =>
        sb ++= s"  ${f.module}: ${f.ref.from} -> ${f.ref.member}${f.place}  [${f.rule.api}: ${f.rule.why}" +
          s"${f.rule.hint}]\n"
      }
      Report.libraries(sb, saidJars)
      sb ++= "\n"
    if suggestions.nonEmpty then
      sb ++= "SUGGESTED types.data (each is final with final fields; add it if you vouch it is an immutable value)\n"
      suggestions.foreach((c, n) => sb ++= s"  $c  ($n findings)\n")
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
    if elsewhere.nonEmpty then
      sb ++= "AUDITED ELSEWHERE (their classes inform this audit; their findings are their own report's)\n"
      elsewhere.sortBy(_._1).foreach((m, l) => sb ++= s"  $m [${l.toString.toLowerCase}]\n")
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
        s""""use":${s(f.rule.use)},"at":${s(f.at)},"count":${f.count},"source":${s(f.source)}}"""
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
    val el = elsewhere.sortBy(_._1).map((m, l) => s"""{"module":${s(m)},"layer":${s(l.toString.toLowerCase)}}""")
    val sg = suggestions.map((c, n) => s"""{"class":${s(c)},"findings":$n}""")
    def roll(js: Vector[JarRollup]) = js.map(j => s"""{"jar":${s(j.jar)},"distinct":${j.distinct},""" +
      s""""modules":${j.modules},"groups":{${j.groups.map((g, k) => s"${s(g)}:$k").mkString(",")}}}""")
    val js = roll(jars)
    val sj = roll(saidJars)
    s"""{"passed":$passed,"bootstraps":[${Boundary.Bootstraps.map(s).mkString(",")}],"findings":[${fs.mkString(",")}],"said":[${sd.mkString(",")}],""" +
      s""""allowed":[${al.mkString(",")}],"unusedAllows":[${un.mkString(",")}],"inventory":[${inv.mkString(",")}],""" +
      s""""elsewhere":[${el.mkString(",")}],"suggestions":[${sg.mkString(",")}],"jars":[${js.mkString(",")}],""" +
      s""""saidJars":[${sj.mkString(",")}],""" +
      s""""untracked":[${untracked.sorted.map(s).mkString(",")}],"inputs":[${ins.mkString(",")}],"jpms":{"modules":[${jm.mkString(",")}],"splits":[${splits.mkString(",")}],"launcherOptions":[${jpms.launcherOptions.map(s).mkString(",")}]}}"""

  private def desc(r: Ref) = if r.kind == Ref.Kind.Native then " (native method)" else ""

/** a library's findings (or said lines) in one line: `distinct` reaches (a jar on five business rows is scanned five
  * times; a reach is counted once), the `modules` it was scanned under, and the distinct ones by the rule's group */
final case class JarRollup(jar: String, distinct: Int, modules: Int, groups: Vector[(String, Int)])

object Report:
  def rollup(fs: Vector[Finding]): Vector[JarRollup] =
    fs.filter(_.source.endsWith(".jar")).groupBy(_.source).toVector.sortBy(_._1).map { (jar, xs) =>
      val one = xs.distinctBy(f => (f.ref.from, f.ref.member, f.ref.descriptor, f.rule.api, f.rule.why))
      JarRollup(jar, one.size, xs.map(_.module).distinct.size,
        one.groupBy(f => Audit.group(f.rule)).view.mapValues(_.size).toVector.sortBy(g => (-g._2, g._1)))
    }

  private[audit] def libraries(sb: StringBuilder, js: Vector[JarRollup]): Unit =
    if js.nonEmpty then
      sb ++= "  in libraries (by jar, each reach once; every line is in report.json)\n"
      js.foreach { j =>
        val in = if j.modules > 1 then s" (on ${j.modules} business rows)" else ""
        sb ++= s"    ${j.jar}: ${j.distinct}$in — ${j.groups.map((g, k) => s"$g $k").mkString(", ")}\n"
      }

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

  /** a rule's group for a roll-up: its why up to the colon (`network`, `escape hatch`, `state`, ...) */
  def group(rule: Rule): String = rule.why.takeWhile(_ != ':')

  /** the rules that name a reach (a business finding, an inventory line): every rule but `state`, which is code's */
  private def reaching(boundary: Boundary): Vector[Rule] = boundary.rules.filter(_.api != "state")

  /** where a class's reference is made: the first site the check counts, as `method (line N)`, and how many; None
    * when every site is excused (the 3.3 lazy-val idiom, a static forwarder); `class header` when no code makes it */
  private def placed(c: ClassInfo, r: Ref, sites: Map[(String, String, String), Vector[(MethodInfo, Int)]])
      : Option[(String, Int)] =
    if r.kind == Ref.Kind.Native then Some((s"${r.name} (native method)", 1))
    else sites.get((r.owner, r.name, r.descriptor)) match
      case None => Some(("class header", 1))
      case Some(all) =>
        val counted = all.filterNot((m, i) => Boundary.excused(c, m, i))
        counted.headOption.map((m, i) => (s"${m.name} (line ${m.sites(i).line})", counted.size))

  private def sitesOf(c: ClassInfo): Map[(String, String, String), Vector[(MethodInfo, Int)]] =
    c.methods.flatMap(m => m.sites.indices.map(i => (m, i))).groupBy { (m, i) =>
      val r = m.sites(i).ref
      (r.owner, r.name, r.descriptor)
    }

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
    // a reference is matched once, whichever class makes it: the rules up the owner's hierarchy are the costly part
    val matched = scala.collection.mutable.HashMap.empty[Ref, Option[Rule]]
    def ruleOf(r: Ref): Option[Rule] =
      matched.getOrElseUpdate(r.copy(from = ""), rules.find(_.matchesUp(r, classes.supers)))
    def report(module: String, f: Finding): Unit =
      boundary.allows.find(_.matches(module, f.ref)) match
        case Some(a) => used += a; allowed += ((a, f))
        case None => findings += f
    modules.toVector.sortBy(_._1).foreach { (module, paths) =>
      val layer = boundary.layers.getOrElse(module, Layer.Untracked)
      val elsewhere = boundary.elsewhere(module)
      // untracked: no layer of its own, and nothing in `packages` could cover it
      if layer == Layer.Untracked && boundary.packages.get(module).forall(_.isEmpty) then untracked += module
      else
        // code is read where a finding needs its place (business) or the boundary on types reads the host's calls
        val code = !elsewhere && (layer == Layer.Business || boundary.packages.get(module).exists(_.values.exists(
          _ == Layer.Business)) || boundary.types.nonEmpty)
        val infos: Vector[(String, ClassInfo)] = paths.toVector.flatMap { p =>
          inputs += ((module, p, Scan.digest(p)))
          val source = if Files.isDirectory(p) then p.toString else p.getFileName.toString
          Scan.classes(p, code).map(source -> _)
        }
        // a class under a package prefix belongs to that prefix's layer and is
        // reported under `module:prefix`; the rest of the module under its own
        val byRow: Map[(String, Layer), Vector[(String, ClassInfo)]] = infos.groupBy { (_, c) =>
          boundary.prefixOf(module, c.name) match
            case Some(p) => (s"$module:$p", boundary.packages(module)(p))
            case None => (module, layer)
        }
        // a class no prefix covers, in a module with no layer of its own, is untracked
        if layer == Layer.Untracked && (byRow.isEmpty || byRow.contains((module, Layer.Untracked))) then untracked += module
        byRow.toVector.sortBy(_._1._1).foreach { case ((row, rowLayer), sourced) =>
          val cs = sourced.map(_._2)
          rowLayer match
            case Layer.Untracked =>
            case Layer.Business if elsewhere => if boundary.types.nonEmpty then business ++= cs.map(row -> _)
            case Layer.Business =>
              val own = cs.iterator.map(_.name).toSet
              sourced.foreach { (source, c) =>
                lazy val sites = sitesOf(c)
                // a bare class reference the class also makes members of is carried by them: one reach, not two
                val owners = c.refs.iterator.filter(_.kind != Ref.Kind.Class).map(_.owner).toSet
                val carried = (r: Ref) => r.kind == Ref.Kind.Class && owners(r.owner)
                c.refs.filterNot(r => Boundary.isBootstrap(r) || carried(r)).foreach { r =>
                  val rule =
                    if r.kind == Ref.Kind.Native then Some(Boundary.Native)
                    else ruleOf(r).orElse(boundary.allowlist.flatMap { list =>
                      if own(r.owner) || r.owner.startsWith("[") then None   // an array's own (`clone`)
                      else classLayer.get(r.owner) match
                        // another business module's class (of this build, or a dependency's row) and the runtime's
                        // are code a business module may name; a handler's is reached only through its effect
                        case Some(Layer.Runtime) | Some(Layer.Business) => None
                        case Some(Layer.Handlers) => Some(Audit.NamesHandler)
                        case _ => if list.allows(r) then None else Some(Audit.NotAllowed)
                    })
                  rule.foreach(rule => placed(c, r, sites).foreach((at, n) =>
                    report(module, Finding(row, r, rule, at, n, source))))
                }
                if state then
                  Code.check(c, _ => true, Vector(Presets.State), classes)
                    .groupBy(h => (h.rule, h.site.ref.member, h.said)).toVector.sortBy(_._2.head.line).foreach { (_, hs) =>
                      val h = hs.head
                      val f = Finding(row, h.site.ref.copy(from = c.name), h.rule, s"${h.method} (line ${h.line})",
                        hs.size, source)
                      if h.said then said += f else report(module, f)
                    }
              }
              if boundary.types.nonEmpty then business ++= cs.map(row -> _)
            case _ =>
              // the inventory lists what the row touches OUTSIDE itself and the JDK's pure core:
              // every reference under a rule is a reach; the rest is plain computation
              if !elsewhere then
                val reaches = cs.flatMap(_.refs).filterNot(Boundary.isBootstrap)
                  .filter(r => r.kind == Ref.Kind.Native || rules.exists(_.matches(r)))
                inventory += Inventory(row, rowLayer, reaches.groupBy(r => provider(r.owner)))
              if boundary.types.nonEmpty then hosts ++= cs.map(row -> _)
        }
    }
    val suggested = scala.collection.mutable.Map.empty[String, Int]
    boundary.types.foreach { types =>
      typeBoundary(types, business.result(), hosts.result(), classLayer, classes)
        .filterNot((m, _, _) => boundary.elsewhere(m))
        .foreach { (m, f, why) =>
          why.suspect.foreach(c => suggested(c) = suggested.getOrElse(c, 0) + f.count)
          if why.erased then said += f else report(m, f)
        }
    }
    classes.close()
    val scanned = inputs.result()
    val notElsewhere = (f: Finding) => !boundary.elsewhere(f.module.takeWhile(_ != ':'))
    Report(scanned, findings.result().filter(notElsewhere), inventory.result(), untracked.result(),
      boundary.allows.filterNot(used.contains), allowed.result(), jpms, said.result().filter(notElsewhere),
      modules.keys.toVector.filter(boundary.elsewhere).sorted.map(m => m -> boundary.layers.getOrElse(m, Layer.Untracked)),
      suggested.toVector.filter(_._2 >= 2).sortBy(s => (-s._2, s._1)))

  /** a business reference to a handlers module's class (allowlist mode, and the boundary on types) */
  val NamesHandler: Rule = Rule("handlers", "names a handler's class: business code reaches a handler only through " +
    "its effect", "perform the effect; the host installs its handler")
  /** a business reference no rule names and no allowlist entry allows (allowlist mode) */
  val NotAllowed: Rule = Rule("allowlist", "not on the allowlist: business code names only what is allowed (deny by " +
    "default)")
  /** the boundary rule on types */
  def boundaryRule(why: String): Rule = Rule("boundary", why, "first-order data only: a value of the program's " +
    "own, or of the data list — what reaches the world stays in a handler, the program holds ids")

  /** a business value's own methods: a handler that matches (`unapply`), copies or reads a value the program made
    * gives it nothing — it receives or rebuilds the program's value */
  private val ValueMethods: Set[String] = Set("unapply", "unapplySeq", "copy", "productElement", "productElementName",
    "productArity", "productPrefix", "equals", "hashCode", "toString", "canEqual", "ordinal", "fromProduct")
  def valueMethod(name: String): Boolean =
    ValueMethods(name) || name.startsWith("copy$default$") || name.matches("_\\d+")

  /** the class a finding names to a person: its outermost (`Foo$$anon$1`, `Foo$`, `Foo$Bar` -> `Foo`) */
  private def outer(cls: String): String =
    val simple = cls.lastIndexOf('.')
    val at = cls.indexOf('$', simple + 1)
    if at > simple + 1 then cls.substring(0, at) else cls

  /** THE BOUNDARY RULE ON TYPES over the rows read: what the host gives a business method, an operation's answer,
    * and a business class naming the host's type. Findings by module (the row's module), one per (class, target,
    * reason) with its count, each with the judge's `Why` (erased: said; suspect: a `types.data` suggestion) */
  private def typeBoundary(types: Types, business: Vector[(String, ClassInfo)], hosts: Vector[(String, ClassInfo)],
                           classLayer: Map[String, Layer], classes: Classes): Vector[(String, Finding, Why)] =
    val all = (business ++ hosts).map(_._2)
    val subclassCache = scala.collection.mutable.HashMap.empty[String, Vector[String]]
    def subclasses(name: String): Vector[String] = subclassCache.getOrElseUpdate(name,
      all.filter(c => c.name != name && classes.supers(c.name).contains(name)).map(_.name).sorted)
    val judge = Judge(types, classes, classLayer.get, subclasses)
    val out = Vector.newBuilder[(String, Finding, Why)]
    def module(row: String) = row.takeWhile(_ != ':')
    val byName = business.map((_, c) => c.name -> c).toMap
    // the host gives a business method or constructor its arguments: data only
    for (row, h) <- hosts
        if !classLayer.get(h.name).contains(Layer.Business) do
      lazy val sites = sitesOf(h)
      for r <- h.refs.distinct
          if (r.kind == Ref.Kind.Method || r.kind == Ref.Kind.InterfaceMethod) && byName.contains(r.owner) &&
            !valueMethod(r.name) &&
            // a business trait's own implementation for the host's class itself (`$init$`, a static `m$`)
            !(r.name.endsWith("$") && classes.supers(h.name).contains(r.owner)) do
        val target = byName(r.owner)
        val method = target.methods.find(m => m.name == r.name && m.descriptor == r.descriptor)
        val (params, _) = Signatures.method(method.flatMap(_.signature).getOrElse(r.descriptor))
        Judge.first(params.iterator.flatMap(judge.data(_, scala = target.scala))).foreach { why =>
          val (at, n) = placed(h, r, sites).getOrElse(("class header", 1))
          out += ((module(row), Finding(row, r, boundaryRule(s"the host gives ${r.owner}#${r.name} what is not data: " +
            why.text), at, n), why))
        }
    for (row, c) <- business do
      // an operation's answer
      for sig <- c.signature; sup <- Signatures.supers(sig) do sup match
        case JType.Cls(effect, args) if types.answers.contains(effect) && args.nonEmpty =>
          val i = types.answers(effect)
          args.lift(if i < 0 then args.size + i else i).foreach {
            case JArg.Exact(answer) => judge.data(answer, scala = c.scala).foreach { why =>
              out += ((module(row), Finding(row, Ref(c.name, Ref.Kind.Class, effect, "", ""),
                boundaryRule(s"the answer of ${c.name}, an operation of $effect, must be first-order data: ${why.text}"),
                "class header"), why))
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
        val at = c.methods.iterator.flatMap(m => m.sites.find(_.ref.owner == n).map(s => s"${m.name} (line ${s.line})"))
          .nextOption().orElse(c.fields.find(f => f.descriptor.contains(n.replace('.', '/'))).map(f => s"field ${f.name}"))
          .orElse(c.methods.find(m => (m.descriptor + m.signature.getOrElse("")).contains(n.replace('.', '/')))
            .map(m => s"${m.name} (signature)")).getOrElse("class header")
        out += ((module(row), Finding(row, Ref(c.name, Ref.Kind.Class, n, "", ""),
          boundaryRule(s"business code names the host's type $n"), at), Why(n)))
      }
    // one finding per (row, class, target, reason): the class as a person names it, the occurrences counted
    out.result().groupBy((m, f, _) => (m, f.module, outer(f.ref.from), f.ref.owner, f.ref.name, f.rule.why)).toVector
      .map { (_, xs) =>
        val (m, f, why) = xs.head
        (m, f.copy(count = xs.map(_._2.count).sum), why)
      }.sortBy((m, f, _) => (m, f.module, f.ref.from, f.ref.member))
