package okay.audit

import java.nio.file.{Files, Path, Paths}

/** specs/okay-audit.md, "The first real-world run": each false positive of the 20-repo run reproduced, and beside it
  * the real violation it must still catch */
class TestRealWorld extends munit.FunSuite with okay.testkit.Munit.Diagnosed:
  val classesDir: Path = Paths.get(getClass.getResource("/fixture/Pure.class").toURI).getParent.getParent

  /** the classes named (`rw/Seeded` with its `$` companions and nested classes), in a fresh directory */
  def cls(names: String*): Path =
    val dir = Files.createTempDirectory("audit-rw")
    names.foreach { n =>
      val (sub, simple) = n.lastIndexOf('/') match
        case -1 => ("", n)
        case i => (n.substring(0, i), n.substring(i + 1))
      val from = classesDir.resolve("fixture").resolve(sub)
      val to = Files.createDirectories(dir.resolve("fixture").resolve(sub))
      Files.list(from).filter(p => p.getFileName.toString.matches(s"\\Q$simple\\E(\\$$.*)?\\.class"))
        .forEach(p => Files.copy(p, to.resolve(p.getFileName.toString)): Unit)
    }
    dir

  /** okay.http.Bytes (with its `Bytes$` module) copied into `dir`: on the business row, as okay-http's jar is in the
    * real manifests */
  def withBytes(dir: Path): Unit =
    val http = Files.createDirectories(dir.resolve("okay/http"))
    Files.list(classesDir.resolve("okay/http")).filter(_.getFileName.toString.matches("""Bytes(\$.*)?\.class"""))
      .forEach(p => Files.copy(p, http.resolve(p.getFileName.toString)): Unit)

  def business(rules: Vector[Rule], paths: Path*): Report =
    Audit.run(Boundary(Map("biz" -> Layer.Business), rules = rules), Map("biz" -> paths))

  test("Scala 3.3's lazy val (getDeclaredField of x$lzyN to getOffsetStatic) is the idiom; other reflection is not"):
    // a class compiled by Scala 3.3.8 (okay-simple's Shape$Deferred), kept as bytes: the real false positive
    val real = Paths.get(getClass.getResource("/scala33/okay/effects/shape/Shape$Deferred.class").toURI)
      .getParent.getParent.getParent.getParent
    assert(Scan.directory(real).exists(_.member == "java.lang.Class#getDeclaredField"), "the idiom is in the class")
    val r = business(Boundary.Default ++ Presets.rules("pure"), real)
    note(r.text)
    assert(!r.findings.exists(_.ref.name == "getDeclaredField"), r.text)
    assert(r.text.contains("Scala 3.3's lazy val"), "the carve-out is listed in the header")
    // a lazy cell's name used otherwise, another name handed to getOffsetStatic: reflection, both sites counted
    val neg = business(Boundary.Default, cls("rw/Reflective"))
    note(neg.text)
    val f = neg.findings.find(_.ref.member == "java.lang.Class#getDeclaredField").getOrElse(fail(neg.text))
    assertEquals(f.count, 2)
    assert(f.at.startsWith("peek (line "), f.at)

  test("the type rule: a lazy cell is not a var, a real var is; a Java Object is a finding; Bytes is suggested"):
    val biz = cls("rw/Sink", "rw/Cached", "rw/Counted", "rw/Bytes", "rw/Erased")
    val host = cls("rw/host/Giver")
    val r = Audit.run(Boundary(Map("biz" -> Layer.Business, "host" -> Layer.Handlers), rules = Vector.empty,
      types = Some(Types())), Map("biz" -> Seq(biz), "host" -> Seq(host)))
    note(r.text)
    assert(!r.findings.exists(_.rule.why.contains("lzy")), r.text)
    val counted = r.findings.find(_.rule.why.contains("fixture.rw.Counted has a var, n")).getOrElse(fail(r.text))
    assertEquals(counted.count, 2, "one finding per (class, target, reason), its sites counted")
    assert(counted.at.nonEmpty && counted.at != "class header", counted.at)
    assert(r.findings.exists(f => f.ref.name == "keep" && f.rule.why.contains("java.lang.Object says nothing")), r.text)
    assertEquals(r.suggestions, Vector("fixture.rw.Bytes" -> 2))
    assert(r.text.contains("SUGGESTED types.data"), r.text)

  test("the type rule on Scala: unapply/copy/$init$ are not the host giving; programs per config; erased Object said"):
    val biz = cls("rw/Note", "rw/Entry", "rw/Outward", "rw/Business")
    val rt = cls("rw/Prog")
    val host = cls("rw/host/Desk")
    def run(types: Types) = Audit.run(Boundary(Map("biz" -> Layer.Business, "rt" -> Layer.Runtime,
      "host" -> Layer.Handlers), rules = Vector.empty, types = Some(types)),
      Map("biz" -> Seq(biz), "rt" -> Seq(rt), "host" -> Seq(host)))
    val r = run(Types())
    note(r.text)
    val targets = r.findings.map(_.ref.name).toSet
    for n <- Vector("unapply", "copy", "$init$", "name$") do assert(!targets(n), s"$n: ${r.text}")
    // the real one: the host hands business code its own lambda
    assert(r.findings.exists(f => f.ref.name == "make" && f.rule.why.contains("a function")), r.text)
    // a program holds a function by design: without the config it reads as one, with it it is a program
    assert(r.findings.exists(f => f.ref.name == "within" && f.rule.why.contains("a function")), r.text)
    val programs = run(Types(programs = Types.Programs :+ "fixture.rw.Prog"))
    assert(!programs.findings.exists(_.ref.name == "within"), programs.text)
    // a union is Object in bytecode: unknown, said, not failed
    assert(!r.findings.exists(_.ref.name == "record"), r.text)
    assert(r.said.exists(f => f.ref.name == "record" && f.rule.why.contains("unknown (erased)")), r.text)

  test("okay-http's Bytes is data by default: a handler giving it is no finding; a raw byte[] to business still is"):
    val biz = cls("rw/Wire")
    withBytes(biz)
    val host = cls("rw/host/Wired")
    def run(types: Types) = Audit.run(Boundary(Map("biz" -> Layer.Business, "host" -> Layer.Handlers),
      rules = Vector.empty, types = Some(types)), Map("biz" -> Seq(biz), "host" -> Seq(host)))
    assert(Types.Data.contains("okay.http.Bytes"))
    val r = run(Types())
    note(r.text)
    assert(!r.findings.exists(_.ref.name == "take"), r.text)
    assert(r.findings.exists(f => f.ref.name == "raw" && f.rule.why.contains("an array, byte[]")), r.text)
    assert(!r.suggestions.exists(_._1 == "okay.http.Bytes"), r.text)
    // without the default the wrapper's array is a finding again, and suggested
    val bare = run(Types(data = Types.Data.filterNot(_ == "okay.http.Bytes")))
    assert(bare.findings.exists(f => f.ref.name == "take" && f.rule.why.contains("okay.http.Bytes.a")), bare.text)

  test("a listed copying factory given a raw byte[] is no finding; an unlisted method still is; unlisted, a finding"):
    val biz = cls("rw/Wire")
    withBytes(biz)
    val host = cls("rw/host/Copied", "rw/host/Wired")
    def run(types: Types) = Audit.run(Boundary(Map("biz" -> Layer.Business, "host" -> Layer.Handlers),
      rules = Vector.empty, types = Some(types)), Map("biz" -> Seq(biz), "host" -> Seq(host)))
    assertEquals(Types.Factories, Vector("okay.http.Bytes$#apply", "okay.http.Bytes$#body", "okay.http.Bytes#apply",
      "okay.http.Bytes#body"))
    val r = run(Types())
    note(r.text)
    // the Scala host's module calls and the Java host's static forwarder: trusted to copy
    for n <- Vector("apply", "body") do assert(!r.findings.exists(_.ref.name == n), s"$n: ${r.text}")
    // a business method of its own given the same array: judged as before
    for h <- Vector("Copied", "Wired") do assert(r.findings.exists(f => f.ref.from.contains(h) && f.ref.name == "raw" &&
      f.rule.why.contains("an array, byte[]")), s"$h: ${r.text}")
    // a seal names the list it trusted
    assert(Boundary(Map.empty, types = Some(Types())).canonical.contains("factory okay.http.Bytes$#apply"))
    // the trust is the list: a factory taken off it is a finding again, the others stay trusted
    val off = run(Types(factories = Types.Factories.filterNot(_ == "okay.http.Bytes$#apply")))
    note(off.text)
    assert(off.findings.exists(f => f.ref.owner == "okay.http.Bytes$" && f.ref.name == "apply" &&
      f.rule.why.contains("the host gives okay.http.Bytes$#apply what is not data: an array, byte[]")), off.text)
    assert(!off.findings.exists(f => f.ref.name == "body" || f.ref.owner == "okay.http.Bytes"), off.text)

  test("row evidence is data, its type arguments phantom: In[Fetch, R] no finding; a function, an effect value are"):
    val biz = cls("rw/Fetching")
    // okay-scala's places and okay-http's Fetch on the business row, as the family's jars are in the real manifests
    for (pkg, names) <- Vector("okay/scala3" -> """(In|Place)(\$.*)?\.class""",
                               "okay/http" -> """Fetch(\$.*)?\.class""") do
      val to = Files.createDirectories(biz.resolve(pkg))
      Files.list(classesDir.resolve(pkg)).filter(_.getFileName.toString.matches(names))
        .forEach(p => Files.copy(p, to.resolve(p.getFileName.toString)): Unit)
    val host = cls("rw/host/Teller")
    def run(types: Types) = Audit.run(Boundary(Map("biz" -> Layer.Business, "host" -> Layer.Handlers),
      rules = Vector.empty, types = Some(types)), Map("biz" -> Seq(biz), "host" -> Seq(host)))
    assertEquals(Types.Evidence, Vector("okay.scala3.Place", "okay.scala3.At", "okay.scala3.Sub", "okay.safe.In",
      "okay.safe.Split", "okay.safe.Place", "okay.safe.Sub"))
    val r = run(Types())
    note(r.text)
    // the place: no function crosses, though Fetch's case holds one
    assert(!r.findings.exists(_.ref.name == "get"), r.text)
    assert(!r.said.exists(_.ref.name == "get"), r.text)
    // a real function, and an effect value holding a continuation: findings as before
    assert(r.findings.exists(f => f.ref.name == "mapped" && f.rule.why.contains("a function, scala.Function1")),
      r.text)
    assert(r.findings.exists(f => f.ref.name == "asked" &&
      f.rule.why.contains("okay.http.Fetch$Call.answer: a function")), r.text)
    // a seal names the list it trusted
    assert(Boundary(Map.empty, types = Some(Types())).canonical.contains("evidence okay.scala3.Place"))
    // the trust is the list: Place taken off it, the place's Fetch is judged again
    val off = run(Types(evidence = Types.Evidence.filterNot(_ == "okay.scala3.Place")))
    note(off.text)
    assert(off.findings.exists(f => f.ref.name == "get" &&
      f.rule.why.contains("okay.http.Fetch$Call.answer: a function")), off.text)

  test("a packages key names a class: its companion, nested and anonymous classes with it, not a longer name"):
    assert(Boundary.under("okay.http.JdkHttp$JdkClient", "okay.http.JdkHttp"))
    assert(Boundary.under("okay.http.JdkHttp", "okay.http.JdkHttp"))
    assert(Boundary.under("okay.http.x.Y", "okay.http"))
    assert(!Boundary.under("okay.http.JdkHttpX", "okay.http.JdkHttp"))
    val dir = cls("rw/Edge", "rw/EdgeX")
    val r = Audit.run(Boundary(Map("biz" -> Layer.Business),
      packages = Map("biz" -> Map("fixture.rw.Edge" -> Layer.Handlers))), Map("biz" -> Seq(dir)))
    note(r.text)
    assert(r.findings.nonEmpty && r.findings.forall(_.ref.from == "fixture.rw.EdgeX"), r.text)
    val inv = r.inventory.find(_.module == "biz:fixture.rw.Edge").getOrElse(fail(r.text))
    assert(inv.byProvider.contains("java.net"), inv.toString)

  test("allowlist: another business module's and the runtime's classes are allowed; a handler's named; else listed"):
    val a = cls("rw/Sink")
    val b = cls("rw/Cached", "rw/Counted", "rw/Bytes", "rw/Erased")
    def run(layer: Layer) = Audit.run(Boundary(Map("a" -> Layer.Business, "b" -> layer), rules = Boundary.Default,
      allowlist = Some(Allowlist.Default)), Map("a" -> Seq(a), "b" -> Seq(b)))
    assert(run(Layer.Business).passed, run(Layer.Business).text)
    assert(run(Layer.Runtime).passed, run(Layer.Runtime).text)
    val handlers = run(Layer.Handlers)
    assert(handlers.findings.nonEmpty && handlers.findings.forall(_.rule == Audit.NamesHandler), handlers.text)
    val unknown = run(Layer.Untracked)
    assert(unknown.findings.exists(f => f.rule == Audit.NotAllowed && f.ref.owner == "fixture.rw.Cached"), unknown.text)

  test("randomness: seeded generators and calls on them pass; unseeded, global, secure and thread-local do not"):
    assert(business(Boundary.Default ++ Presets.rules("fresh"), cls("rw/Seeded")).passed)
    val r = business(Boundary.Default, cls("rw/Unseeded"))
    note(r.text)
    val members = r.findings.map(_.ref.member).toSet
    for m <- Vector("java.util.Random#<init>", "scala.util.Random$#MODULE$", "java.security.SecureRandom#<init>",
        "java.util.concurrent.ThreadLocalRandom#current") do assert(members(m), s"$m: $members")
    assert(r.findings.forall(f => f.at.nonEmpty), "every finding is placed: " + r.text)
    val scala = business(Presets.rules("fresh"), cls("rw/Dice"))
    note(scala.text)
    assert(scala.findings.nonEmpty && scala.findings.forall(_.at.startsWith("global")), scala.text)

  test("java.net: URLEncoder, URLDecoder and URI are values; URI#toURL and sockets still reach"):
    for rules <- Vector(Boundary.Default, Presets.rules("io"), Presets.Strict) do
      val r = business(rules, cls("rw/Links"))
      assertEquals(r.findings.map(_.ref.member).toSet, Set("java.net.URI#toURL"), r.text)
    assert(!business(Boundary.Default, cls("Sockets")).passed)
    val al = Audit.run(Boundary(Map("biz" -> Layer.Business), allowlist = Some(Allowlist.Default)),
      Map("biz" -> Seq(cls("rw/Links"))))
    assert(!al.findings.exists(f => Presets.PureNet.contains(f.ref.owner) && f.ref.name != "toURL"),
      "on the default allowlist: " + al.text)
    val net = Boundary.Default.find(_.api == "java.net.").get
    assertEquals(net.except, Presets.PureNet)
    assert(Boundary(Map.empty).canonical.contains("rule java.net. except java.net.URLEncoder"))

  test("nested-run is its own preset, not pure's; runIsolated and stepIsolated never match it"):
    val rules = Presets.rules("nested-run")
    def call(owner: String, name: String) = Ref("biz.P", Ref.Kind.Method, owner, name, "()Ljava/lang/Object;")
    for (o, n) <- Vector("okay.scala3.Prog$package$" -> "runIsolated", "okay.scala3.Cek$" -> "runIsolated",
        "okay.rows.Effects" -> "runIsolated", "okay.rows.Effects" -> "stepIsolated", "okay.core.Eff" -> "runIsolated")
    do assert(!rules.exists(_.matches(call(o, n))), s"$o#$n")
    assert(rules.exists(_.matches(call("okay.scala3.Prog$package$", "run"))))
    val pure = Presets.rules("pure")
    assert(!pure.exists(_.matches(call("okay.scala3.Prog$package$", "run"))), "pure has no nested-run")
    assert(Presets.UseNested.contains("runIsolated"))

  test("a static forwarder is not a second finding: the module class's caller is the one"):
    val dir = cls("rw/Kernel", "rw/User")
    assert(Scan.directory(dir).exists(r => r.from == "fixture.rw.Kernel" && r.member == "fixture.rw.Kernel$#run"),
      "the forwarder is there")
    val r = business(Vector(Rule("fixture.rw.Kernel$#run", "nested run: test")), dir)
    note(r.text)
    assertEquals(r.findings.map(_.ref.from).toSet, Set("fixture.rw.User$"), r.text)

  test("held collections: a static or object field of a mutable type changed through a method; Promise is async"):
    val r = business(Presets.rules("state"), cls("rw/Cache", "rw/Registry", "rw/Guard"))
    note(r.text)
    def held(field: String) = r.findings.filter(_.rule.why.contains(s".$field holds a mutable"))
    assert(held("SEEN").exists(_.at.startsWith("mark")), r.text)
    assert(held("LAST").exists(_.at.startsWith("last")), r.text)
    assert(held("seen").exists(f => f.ref.from == "fixture.rw.Registry$" && f.at.startsWith("mark")), r.text)
    assert(held("TABLE").isEmpty && held("table").isEmpty, "filled in the initializer, then read: " + r.text)
    assert(r.said.exists(f => f.rule.why.contains(".inFlight holds a mutable") && f.at.startsWith("start")), r.text)
    assert(!r.findings.exists(_.rule.why.contains("inFlight")), "an instance's map is said, not failed")
    val promise = Ref("x.Y", Ref.Kind.Method, "scala.concurrent.Promise$", "apply", "()Lscala/concurrent/Promise;")
    assert(Presets.rules("async").exists(_.matches(promise)))

  test("held collections follow the receiver: the loaded value itself, not another of its type"):
    val r = business(Presets.rules("state"), cls("rw/Held", "rw/Drawn"))
    note(r.text)
    def held(field: String) = r.findings.filter(_.rule.why.contains(s".$field holds a mutable"))
    // okay-java's Ambient.clean: NAMES read with size(), a local list added to — nothing; the same after L read
    assert(!r.findings.exists(f => f.at.startsWith("clean") || f.at.startsWith("other")), r.text)
    assert(held("NAMES").isEmpty, r.text)
    assertEquals(held("L").map(_.count).sum, 3, r.text)               // direct, local, loop: one finding, three sites
    assert(held("M").exists(_.at.startsWith("branch")), r.text)
    // okay-mall's Drawn.kept, in a closure under synchronized; `+=` on an object's buffer; a local buffer is not
    assert(held("kept").exists(f => f.ref.from == "fixture.rw.Drawn$" && f.at.startsWith("of")), r.text)
    assert(held("names").exists(_.at.startsWith("note")), r.text)
    assertEquals(held("names").map(_.count).sum, 1, r.text)            // note only: local's += is its own buffer's
    // what okay-java words the finding with
    val classes = Classes(Seq(classesDir))
    val hits = Code.check(Scan.parse(Files.readAllBytes(classesDir.resolve("fixture/rw/Held.class"))), _ => true,
      Presets.rules("state"), classes)
    assert(hits.exists(h => h.method == "direct" && h.holder == "fixture.rw.Held.L, a static field holding a " +
      "mutable collection"), hits.mkString("\n"))
    assertEquals(hits.filter(_.holder.nonEmpty).map(_.method).toSet, Set("direct", "local", "loop", "branch"),
      hits.mkString("\n"))
    val drawn = Code.check(Scan.parse(Files.readAllBytes(classesDir.resolve("fixture/rw/Drawn$.class"))), _ => true,
      Presets.rules("state"), classes)
    assert(drawn.exists(_.holder == "fixture.rw.Drawn$.kept, a field of a Scala object holding a mutable collection"),
      drawn.mkString("\n"))

  test("audited elsewhere: classes inform, findings are not repeated; jars roll up in the text, not in the json"):
    val lib = cls("rw/Edge")
    val r = Audit.run(Boundary(Map("biz" -> Layer.Business, "lib" -> Layer.Business), elsewhere = Set("lib")),
      Map("biz" -> Seq(cls("Pure")), "lib" -> Seq(lib)))
    assert(r.passed, r.text)
    assertEquals(r.elsewhere, Vector("lib" -> Layer.Business))
    assert(r.text.contains("AUDITED ELSEWHERE") && r.json.contains(""""elsewhere":[{"module":"lib""""), r.text)
    // the allowlist reads an elsewhere module's layer: business code may name its classes
    val al = Audit.run(Boundary(Map("a" -> Layer.Business, "b" -> Layer.Business), allowlist = Some(Allowlist.Default),
      elsewhere = Set("b")), Map("a" -> Seq(cls("rw/Sink")), "b" -> Seq(cls("rw/Cached", "rw/Counted", "rw/Bytes",
      "rw/Erased"))))
    assert(al.passed, al.text)
    // a library's findings, by jar
    val jar = Files.createTempDirectory("audit-jar").resolve("lib.jar")
    val src = cls("rw/Unseeded", "rw/Links")
    val out = java.util.jar.JarOutputStream(Files.newOutputStream(jar))
    try Files.walk(src).filter(Files.isRegularFile(_)).sorted.forEach { p =>
      out.putNextEntry(java.util.jar.JarEntry(src.relativize(p).toString.replace('\\', '/')))
      out.write(Files.readAllBytes(p)); out.closeEntry()
    }
    finally out.close()
    val lr = business(Boundary.Default, cls("Clocky"), jar)
    note(lr.text)
    val j = lr.jars.headOption.getOrElse(fail(lr.text))
    assertEquals(j.jar, "lib.jar")
    assertEquals(j.distinct, lr.findings.count(_.source == "lib.jar"))
    assertEquals(j.modules, 1)
    assert(j.groups.exists(_._1 == "time and randomness") && j.groups.exists(_._1 == "network"), j.toString)
    assert(lr.text.contains("in libraries") && lr.text.contains("lib.jar: "), lr.text)
    assert(!lr.text.contains("fixture.rw.Unseeded ->"), "a jar's findings are rolled up: " + lr.text)
    assert(lr.text.contains("fixture.Clocky ->"), "a directory's are listed: " + lr.text)
    assert(lr.json.contains(""""source":"lib.jar""""), "the json keeps every finding with its source")

  test("the manifest: audited elsewhere, types.programs, types.factories, types.evidence"):
    val dir = Files.createTempDirectory("audit-manifest")
    val manifest = dir.resolve("audit.json")
    Files.writeString(manifest, s"""{"types": {"programs": ["com.acme.Prog"],
      "factories": ["com.acme.Blob$$#apply"], "evidence": ["com.acme.Row"]}, "modules": [
      {"name": "biz", "layer": "business", "paths": ["${cls("Pure")}"]},
      {"name": "lib", "layer": "business", "paths": ["${cls("Sockets")}"], "audited": "elsewhere"}]}"""): Unit
    val m = Manifest.read(manifest)
    assertEquals(m.elsewhere, Set("lib"))
    assert(m.types.exists(t => t.programs.contains("com.acme.Prog") && t.programs.contains("okay.scala3.Prog")))
    assert(m.types.exists(t => t.factories == Types.Factories :+ "com.acme.Blob$#apply"), m.types.toString)
    assert(m.types.exists(t => t.evidence == Types.Evidence :+ "com.acme.Row"), m.types.toString)
    val report = Audit.run(m.boundary, m.modules)
    assert(report.passed, report.text)
    Files.writeString(manifest, s"""{"modules": [{"name": "lib", "layer": "business", "paths": ["${cls("Pure")}"],
      "audited": "somewhere"}]}"""): Unit
    assert(intercept[Audit.Refused](Manifest.read(manifest)).getMessage.contains("not elsewhere or here"))

  test("Export: reads sbt's projects and export lines, refuses a count that does not match, writes a skeleton"):
    val listing = Vector("[info] welcome to sbt 1.11.7", "[info] In file:/tmp/b/", "[info] \t   a", "[info] \t * root")
    assertEquals(Export.projects(listing), Vector("a", "root"))
    val dirA = Files.createTempDirectory("export-a").toString
    val dirRoot = Files.createTempDirectory("export-root").toString
    val printed = Vector("[info] Defining Global / aggregate", dirA, "[info] compiling 1 Scala source",
      s"$dirA:/c/scala3-library_3-3.3.8.jar:/c/upickle_3-4.1.0.jar", dirRoot, s"$dirRoot:/c/scala3-library_3-3.3.8.jar")
    assertEquals(Export.commands(Vector("a")), Vector("set Global / aggregate := false",
      "export a/Compile/classDirectory", "export a/Runtime/fullClasspath"))
    val ps = Export.read(Vector("a", "root"), Export.values(printed))
    assertEquals(ps.map(_.name), Vector("a", "root"))
    assertEquals(ps.head.classpath, Vector(dirA, "/c/scala3-library_3-3.3.8.jar", "/c/upickle_3-4.1.0.jar"))
    val json = Export.skeleton(ps)
    assert(json.contains(s""""name": "a", "layer": "untracked", "paths": ["$dirA"]"""), json)
    assert(json.contains(""""name": "dependencies", "layer": "untracked", "paths": ["/c/upickle_3-4.1.0.jar"]"""), json)
    assert(json.contains(""""name": "scala-library", "layer": "runtime""""), json)
    assert(intercept[Audit.Refused](Export.read(Vector("a", "root"), Export.values(printed).take(3))).getMessage
      .contains("need 4 values"))
    assertEquals(Export.Project("js", "", Vector("/c/scalajs-library_2.13-1.19.0.jar")).platform, "js")
