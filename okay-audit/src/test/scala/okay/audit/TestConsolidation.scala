package okay.audit

import java.nio.file.{Files, Path, Paths}

/** specs/okay-audit.md, "Consolidation": what okay-java's guard and okay-safe's whole check and seal brought */
class TestConsolidation extends munit.FunSuite with okay.testkit.Munit.Diagnosed:
  val classesDir: Path = Paths.get(getClass.getResource("/fixture/Pure.class").toURI).getParent.getParent

  /** the classes of `fixture` named, and every class of the subpackages named (`"biz/"`), in a fresh directory */
  def only(names: String*): Path =
    val dir = Files.createTempDirectory("audit-fixture")
    Files.createDirectories(dir.resolve("fixture"))
    names.foreach { n =>
      if n.endsWith("/") then
        val sub = n.dropRight(1)
        Files.createDirectories(dir.resolve("fixture").resolve(sub))
        Files.list(classesDir.resolve("fixture").resolve(sub)).forEach(p =>
          Files.copy(p, dir.resolve("fixture").resolve(sub).resolve(p.getFileName.toString)): Unit)
      else
        Files.list(classesDir.resolve("fixture")).filter(p => p.getFileName.toString.matches(s"$n(\\$$.*)?\\.class"))
          .forEach(p => Files.copy(p, dir.resolve("fixture").resolve(p.getFileName.toString)): Unit)
    }
    dir

  def business(rules: Vector[Rule], paths: Path*): Report =
    Audit.run(Boundary(Map("biz" -> Layer.Business), rules = rules), Map("biz" -> paths))

  test("a finding names the effect to use instead, in text and json; every default rule with a replacement has one"):
    val r = business(Boundary.Default, only("Clocky"))
    note(r.text)
    val now = r.findings.find(_.ref.member == "java.lang.System#currentTimeMillis").getOrElse(fail(r.text))
    assertEquals(now.rule.use, Presets.UseClock)
    assert(r.text.contains("— use a Clock effect (okay.rows.std.Clock, okay.scala3.std.Clock, okay.safe.std.Clock)"))
    val random = r.findings.find(_.ref.member == "java.util.Random#<init>").getOrElse(fail(r.text))
    assert(random.rule.use.contains("okay.rows.std.Fresh"), random.toString)
    assert(r.json.contains("\"use\":\"a Clock effect"), r.json)
    val bare = Boundary.Default.filter(_.use.isEmpty).map(_.api).toSet
    assert(bare.forall(a => Presets.Escapes.flatMap(_.apis).contains(a) || Presets.Processes.head.apis.contains(a)), bare)

  test("presets by name: pure is every effect's, strict okay-java's list, an entry of its own is a rule; spellings"):
    val pure = Presets.rules("pure").map(_.api).toSet
    for p <- Vector("state", "reader", "writer", "clock", "fresh", "async", "io") do
      assert(Presets.byName(p).get.apis.forall(pure), p)
    // nested-run is a design rule of its own, selected by name (specs/okay-audit.md, "The first real-world run")
    assert(!Presets.byName("nested-run").get.apis.exists(pure), "nested-run is not in pure")
    assert(!pure.contains("java.lang.reflect."), "escape is not an effect")
    val mixed = Presets.rules("clock, java.lang.Thread java.net.*")
    assert(mixed.exists(_.api == "java.time.Instant#now"))
    assertEquals(mixed.find(_.api == "java.lang.Thread").map(_.use), Some(Presets.UseAsync))
    def ref(owner: String, name: String = "") =
      Ref("x.Y", if name.isEmpty then Ref.Kind.Class else Ref.Kind.Method, owner, name, "()V")
    assert(Rule("java.net.*", "").matches(ref("java.net.Socket")))
    assert(Rule("java.lang.System.exit", "").matches(ref("java.lang.System", "exit")))
    assert(!Rule("java.lang.System.exit", "").matches(ref("java.lang.System", "exit0")))
    assertEquals(Presets.rules("strict"), Presets.Strict)
    assertEquals(Presets.Strict.find(_.api == "java.lang.Thread").map(_.use), Some(Presets.UseAsync))

  test("a class or member rule matches up the hierarchy: a library's Thread subclass, String#wait as Object#wait"):
    val lib = only("lib/")
    val biz = only("Starts")
    val r = Audit.run(Boundary(Map("biz" -> Layer.Business, "lib" -> Layer.Handlers), rules = Presets.rules("async")),
      Map("biz" -> Seq(biz), "lib" -> Seq(lib)))
    note(r.text)
    val members = r.findings.map(_.ref.member).toSet
    assert(members.contains("fixture.lib.Spinner#<init>"), members.toString)
    assert(members.contains("java.lang.Object#wait"), members.toString)
    assert(r.findings.forall(_.rule.use == Presets.UseAsync), r.text)
    // the same rules without the hierarchy do not see the library's Thread: Starts names no Thread itself
    assert(Scan.directory(biz).filter(_.owner.startsWith("fixture.lib")).forall(x =>
      !Presets.rules("async").exists(_.matches(x))))
    def wait(owner: String) = Ref("x.Y", Ref.Kind.Method, owner, "wait", "()V")
    val classes = Classes(Seq(classesDir))
    assert(Presets.rules("async").exists(_.matchesUp(wait("java.lang.String"), classes.supers)))
    assert(!Presets.rules("async").exists(_.matches(wait("java.lang.String"))))
    classes.close()

  test("the nested-run rule: a business call to a top-level runner is a finding naming what to do instead"):
    val rules = Presets.rules("nested-run")
    def call(owner: String, name: String) = Ref("biz.P", Ref.Kind.Method, owner, name, "()Ljava/lang/Object;")
    for (o, n) <- Vector("okay.rows.Effects" -> "run", "okay.scala3.Prog$package$" -> "run",
        "okay.safe.Host$package$" -> "step", "okay.direct.Effects" -> "handle") do
      val rule = rules.find(_.matches(call(o, n))).getOrElse(fail(s"$o#$n"))
      assert(rule.use.contains("compose the sub-program"), rule.toString)
    assert(!rules.exists(_.matches(call("okay.rows.Effects", "perform"))))
    assertEquals(Rule.sourcePath("okay.safe.Host$package$#run"), "okay.safe.run")
    assertEquals(Rule.sourcePath("scala.Predef$#println"), "scala.Predef.println")

  test("the state rule: a static written is a finding with its method and line, a non-final static read is said"):
    val r = business(Presets.rules("state"), only("Statics"))
    note(r.text)
    val written = r.findings.find(_.ref.member == "fixture.Statics#counter").getOrElse(fail(r.text))
    assertEquals(written.at, "bump (line 10)")
    assert(r.said.exists(f => f.ref.member == "fixture.Statics#counter" && f.at.startsWith("bump")), r.text)
    assert(!r.said.exists(_.ref.member == "fixture.Statics#NAMES"), "a final static is no state: " + r.text)
    assert(r.text.contains("SAID (listed, not failed)"))
    assert(r.json.contains("\"said\":[{"), r.json)
    // the class's own initializer writes its statics: not state
    assert(!r.findings.exists(_.at.startsWith("<clinit>")), r.text)

  test("the state rule: a Scala object's var written is a finding; a lazy val's storage is not"):
    val r = business(Presets.rules("state"), only("Counter", "Lazy"))
    note(r.text)
    assert(r.findings.exists(f => f.ref.member == "fixture.Counter$#n"), r.text)
    assert(!r.findings.exists(_.ref.from.startsWith("fixture.Lazy")), r.text)

  test("Code.check: chosen methods only, with lines — what okay-java's transformation calls"):
    val info = Scan.parse(Files.readAllBytes(classesDir.resolve("fixture/Clocky.class")))
    val classes = Classes(Seq(classesDir))
    val hits = Code.check(info, _.name == "now", Boundary.Default, classes)
    assertEquals(hits.map(_.site.ref.member).toSet, Set("java.lang.System#currentTimeMillis", "java.util.Date#<init>"))
    assert(hits.forall(h => h.method == "now" && h.line == 4), hits.toString)
    assertEquals(hits.find(_.site.ref.name == "currentTimeMillis").map(_.where),
      Some("fixture.Clocky.now (line 4) calls currentTimeMillis of java.lang.System"))
    classes.close()

  test("the allowlist: deny by default — own and runtime classes pass, a handler's class is named, the rest refused"):
    val pure = only("Pure")
    assert(Audit.run(Boundary(Map("biz" -> Layer.Business), allowlist = Some(Allowlist.Default)),
      Map("biz" -> Seq(pure))).passed)
    val strict = Audit.run(Boundary(Map("biz" -> Layer.Business), allowlist = Some(Allowlist(Vector("java.lang.")))),
      Map("biz" -> Seq(pure)))
    assert(strict.findings.exists(f => f.ref.owner == "java.util.List" && f.rule.api == "allowlist"), strict.text)
    val r = Audit.run(Boundary(Map("biz" -> Layer.Business, "host" -> Layer.Handlers),
      allowlist = Some(Allowlist.Default)), Map("biz" -> Seq(only("biz/")), "host" -> Seq(only("host/"))))
    note(r.text)
    assert(r.findings.exists(f => f.ref.owner == "fixture.host.Client" && f.rule.api == "handlers"), r.text)
    assert(!r.findings.exists(_.ref.owner == "fixture.biz.Amount"), r.text)

  test("the boundary on types: what the host gives a program is data, an answer is data, the host's types unnamed"):
    val boundary = Boundary(Map("biz" -> Layer.Business, "host" -> Layer.Handlers),
      types = Some(Types(answers = Map("fixture.biz.Op" -> 0))))
    val r = Audit.run(boundary, Map("biz" -> Seq(only("biz/")), "host" -> Seq(only("host/"))))
    note(r.text)
    val gives = r.findings.filter(f => f.module == "host" && f.rule.api == "boundary").map(f => f.ref.name -> f.rule.why)
      .toMap
    assertEquals(gives.keySet, Set("withClient", "withFn", "withAny", "withList", "withOpen"))
    assert(gives("withClient").contains("fixture.host.Client is the host's"), gives.toString)
    assert(gives("withFn").contains("a function, java.util.function.Supplier<java.lang.String>"), gives.toString)
    assert(gives("withAny").contains("java.lang.Object says nothing"), gives.toString)
    assert(gives("withList").contains("java.util.List is not data: a class of a library"), gives.toString)
    assert(gives("withOpen").contains("fixture.host.Sub, of the host's, extends it"), gives.toString)
    val answers = r.findings.filter(_.rule.why.startsWith("the answer of"))
    assertEquals(answers.map(_.ref.from), Vector("fixture.biz.GetClient"))
    val named = r.findings.filter(_.rule.why.startsWith("business code names the host's type")).map(_.ref.from).toSet
    assertEquals(named, Set("fixture.biz.Program", "fixture.biz.GetClient"))
    // a list of data is data when the list says so
    val listed = Audit.run(boundary.copy(types = Some(Types(data = Types.Data :+ "java.util.List"))),
      Map("biz" -> Seq(only("biz/")), "host" -> Seq(only("host/"))))
    assert(!listed.findings.exists(_.ref.name == "withList"), listed.text)

  test("signatures: generic arguments, nested classes, type variables, arrays"):
    val (ps, res) = Signatures.method("<T:Ljava/lang/Object;>(Ljava/util/List<+TT;>;[ILa/B<Lc/D;>.E<*>;)TT;")
    assertEquals(ps.map(_.show), Vector("java.util.List<? extends T>", "int[]", "a.B$E<c.D, ?>"))
    assertEquals(res, JType.Var("T"))
    assertEquals(Signatures.supers("<X:Ljava/lang/Object;>Lfixture/biz/Op<Ljava/lang/String;>;Ljava/io/Serializable;")
      .map(_.show), Vector("fixture.biz.Op<java.lang.String>", "java.io.Serializable"))

  test("a seal: written of a passed report, verified; a file added, changed or missing, a failure, a JVM option absent"):
    val dir = only("Pure")
    val boundary = Boundary(Map("biz" -> Layer.Business))
    val report = Audit.run(boundary, Map("biz" -> Seq(dir)))
    val files = Seal.files(dir)
    val seal = Seal.parse(Seal.of(report, boundary, files, Vector("entry" -> "fixture.Pure"),
      Vector("--illegal-native-access=deny")).text)
    note(seal.text)
    assertEquals(seal("entry"), Right("fixture.Pure"))
    assertEquals(Seal.verify(seal, files, Vector("--illegal-native-access=deny", "-Xmx1g")), Right(()))
    assert(Seal.verify(seal, files, Vector.empty).left.exists(_.contains("--illegal-native-access=deny")))
    val added = files :+ ("fixture/Evil.class" -> "00")
    assert(Seal.verifyFiles(seal, added).left.exists(_.contains("added after the check")))
    val changed = files.map((n, _) => n -> "00")
    assert(Seal.verifyFiles(seal, changed).left.exists(_.contains("changed after the check")))
    assert(Seal.verifyFiles(seal, Vector.empty).left.exists(_.contains("is not here")))
    assert(Seal.verifyFiles(seal, files, n => Option.when(n.startsWith("fixture/"))(s"$n is trusted"))
      .left.exists(_.contains("is trusted")))
    val failed = Seal.of(business(Boundary.Default, only("Sockets")), boundary, files)
    assert(Seal.verifyResult(failed).left.exists(_.startsWith("the check did not pass")))
    assertNotEquals(seal("config"), Seal.of(report, boundary.copy(rules = Presets.rules("pure")), files)("config"))

  test("Main: the JSON manifest names rules by preset, the allowlist and the boundary on types"):
    val biz = only("biz/")
    val host = only("host/")
    val root = Files.createTempDirectory("audit-consolidated")
    val manifest = root.resolve("audit.json")
    Files.writeString(manifest, s"""{"modules":[{"name":"biz","layer":"business","paths":["$biz"]},
      {"name":"host","layer":"handlers","paths":["$host"]}],
      "rules":"default, state, nested-run","allowlist":"default",
      "types":{"data":["java.util.List"],"answers":{"fixture.biz.Op":"0"}}}""")
    val input = Manifest.read(manifest)
    assert(input.rules.exists(_.api == "state") && input.rules.exists(_.api == "okay.rows.Effects#run"))
    assertEquals(input.allowlist, Some(Allowlist.Default))
    assertEquals(input.types.map(_.answers), Some(Map("fixture.biz.Op" -> 0)))
    val out = root.resolve("report")
    assertEquals(Main.run(Array("--manifest", manifest.toString, "--report", out.toString)), 1)
    val text = Files.readString(out.resolve("report.txt"))
    assert(text.contains("the answer of fixture.biz.GetClient"), text)
    assert(!text.contains("withList"), text)
    Files.writeString(manifest, s"""{"modules":[{"name":"biz","layer":"business","paths":["$biz"]}],
      "types":{"answers":{"fixture.biz.Op":"first"}}}""")
    assertEquals(Main.run(Array("--manifest", manifest.toString, "--report", out.toString)), 2)

  test("Code.check for a Java caller: methods by name and descriptor, rules by name, classes by internal name"):
    val bytes = Files.readAllBytes(classesDir.resolve("fixture/Statics.class"))
    val classes = Classes.byInternalName(n => Classes.system(n.replace('/', '.')).orElse {
      val f = classesDir.resolve(n + ".class"); Option.when(Files.exists(f))(Files.readAllBytes(f))
    }.orNull)
    val hits = Code.check(bytes, java.util.List.of("bump()I"), "state", classes)
    note(hits.toString)
    assertEquals(hits.size, 3)
    assertEquals(hits.stream.filter(!_.said).count, 1L)
    assert(Code.check(bytes, java.util.List.of("names()I"), "state", classes).isEmpty)
