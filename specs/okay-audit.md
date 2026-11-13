# okay-audit — the effect boundary, checked at every build

## Overview

okay's type `A ! Db + Payments` names the effects a program DECLARES. On the
JVM nothing stops the body from doing `new java.net.Socket(...)` as well: the
type system tracks declared effects, it does not forbid undeclared ones.
Haskell has `unsafePerformIO`, Rust has `unsafe`; the honest claim is the same
in all three — not "a function without the effect cannot do it", but **"every
reach to the outside world sits in a small, listed set of handlers, and each
build checks that nothing else reaches"**. A small trusted base plus a
boundary check is the control; this module is the check.

The check reads BYTECODE, not Scala source: it then covers the Java facade
(specs/java-effects.md, specs/java-capabilities.md), Kotlin or Clojure
callers, and every third-party jar on the business classpath — which a
compiler plugin never sees. A class file's constant pool lists every class,
field and method it refers to (JVMS §4.4); that list, per module, against a
rule set, is the whole job.

The report has a second reader. For DORA (Regulation (EU) 2022/2554, Art. 8:
inventory of ICT assets and their dependencies) the handler layer's list —
which module touches which external API — IS the dependency inventory,
generated from the code and current at every build. One scanner gives both
the proof of the boundary and the inventory (operator ask, 2026-10-05; the
business side is `~/work/my/jobs/biz/regulatory-mapping.md`, section 2a).

## Interface

```scala
package okay.audit

/** one reference a class file makes: owner class, member name, descriptor;
  * `kind` is Class | Field | Method | InterfaceMethod | MethodHandle |
  * Native (an own `ACC_NATIVE` method, owner = the class itself) */
final case class Ref(from: String, kind: Ref.Kind, owner: String, name: String, descriptor: String)

object Scan:
  def classFile(bytes: Array[Byte]): Vector[Ref]          // one class, JVMS §4 constant pool
  def directory(dir: Path): Vector[Ref]                   // every .class under it
  def jar(jar: Path): Vector[Ref]                         // every .class entry
  def classpath(entries: Seq[Path]): Map[Path, Vector[Ref]]

enum Layer:
  case Business   // may touch the outside world only through okay effects
  case Handlers   // the trusted base: listed, never failed
  case Runtime    // okay core and its platform: listed as the runtime, never failed

/** a rule names an API prefix (`java.net.`) or one member (`java.lang.System#currentTimeMillis`) and WHY it is outside the boundary */
final case class Rule(api: String, why: String)

/** an allow is a named, reasoned exception for ONE module; unused allows are reported */
final case class Allow(module: String, api: String, reason: String, owner: String)

final case class Boundary(layers: Map[String, Layer], rules: Vector[Rule] = Boundary.Default, allows: Vector[Allow] = Vector.empty)
object Boundary:
  val Default: Vector[Rule]   // the list below

final case class Finding(module: String, ref: Ref, rule: Rule)
final case class Inventory(module: String, layer: Layer, byProvider: Map[String, Vector[Ref]])   // provider = the owner's top package (`java.sql`, `com.zaxxer.hikari`)
final case class Report(inputs: Vector[(Path, String)],   // path and sha-256 of every scanned jar/dir
                        findings: Vector[Finding],         // business modules only; empty is the pass
                        inventory: Vector[Inventory],      // handlers and runtime
                        unusedAllows: Vector[Allow]):
  def passed: Boolean = findings.isEmpty
  def text: String                                         // for people: findings first, then the inventory
  def json: Json                                           // for the evidence pack (okay-codec)

object Audit:
  def run(boundary: Boundary, modules: Map[String, Seq[Path]]): Report
```

sbt (stage 1): `auditLayer := Layer.Business | Handlers | Runtime` per project,
`audit` on the root scans every project's `Compile / products` and
`Compile / dependencyClasspath` under its layer (a jar inherits the layer of
the project that brings it), writes `target/audit/report.{txt,json}`, and
FAILS the build on a finding. A project without `auditLayer` is reported as
`Untracked` — visible, not failed, so adoption can be gradual; the goal state
is zero untracked.

CLI (stage 2): `okay-audit --business a.jar,b.jar --handlers h.jar --runtime okay-core.jar [--allow allows.conf] --report out/` — the same scanner for Maven and Gradle users of the Java facade.

The concrete stage-2 JSON contract is `specs/audit-cli-standalone.md`; it
replaces the illustrative layer flags above so package layers and named allows
stay structured.

### The default rules (`Boundary.Default`)

API prefixes and members a business module may not reference directly; each
has a one-line `why`. Grouped:

- **network:** `java.net.`, `javax.net.`, `java.nio.channels.`, `java.rmi.`, `javax.naming.`, `java.net.http.`
- **files and console:** `java.nio.file.`, `java.io.File`, `java.io.FileInputStream`, `java.io.FileOutputStream`, `java.io.FileReader`, `java.io.FileWriter`, `java.io.RandomAccessFile`, `java.io.Console`, `java.lang.System#out`, `java.lang.System#err`, `java.lang.System#in`, `scala.Console`, `scala.io.Source`, `java.util.logging.`
- **databases:** `java.sql.`, `javax.sql.`
- **processes and the JVM:** `java.lang.ProcessBuilder`, `java.lang.Process`, `java.lang.Runtime#exec`, `#exit`, `#halt`, `#addShutdownHook`, `java.lang.System#exit`, `#load`, `#loadLibrary`, `#getenv`, `#getProperty`, `#setProperty`, `scala.sys.process.`, `scala.sys.package#env`
- **time and randomness (nondeterminism):** `java.lang.System#currentTimeMillis`, `#nanoTime`, `java.time.Clock#system*`, `java.time.Instant#now`, `java.time.LocalDate#now`, `java.time.LocalDateTime#now`, `java.time.ZonedDateTime#now`, `java.time.OffsetDateTime#now`, `java.util.Date#<init>()`, `java.util.Random`, `java.util.concurrent.ThreadLocalRandom`, `java.util.SplittableRandom`, `java.security.SecureRandom`, `java.util.UUID#randomUUID`, `scala.util.Random`
- **threads (the runtime's job, not a program's):** `java.lang.Thread`, `java.util.concurrent.Executors`, `java.util.concurrent.ForkJoinPool`, `java.util.concurrent.CompletableFuture#runAsync`, `#supplyAsync`, `scala.concurrent.ExecutionContext$Implicits`
- **escape hatches (the ways around any static check):** `java.lang.reflect.`, `java.lang.invoke.` **except the two bootstraps every compiler emits** (`java.lang.invoke.LambdaMetafactory`, `java.lang.invoke.StringConcatFactory`, and `java.lang.runtime.ObjectMethods` for records), `java.lang.Class#forName`, `#getMethod`, `#getDeclaredMethod`, `#getField`, `#getDeclaredField`, `#newInstance`, `#getConstructor`, `#getDeclaredConstructor`, `java.lang.ClassLoader`, `java.net.URLClassLoader`, `java.lang.foreign.`, `sun.`, `jdk.internal.`, `java.io.ObjectInputStream`, `java.io.ObjectOutputStream`, `java.lang.Runtime#getRuntime` (only to reach the members above), and any own method with `ACC_NATIVE`

A business module reaches all of these THROUGH an effect whose handler lives
in a `Handlers` module (`Clock`, `Random`, `Console`, `Db`, `Http`, ...) or
through okay's runtime (`Async`, `Produce`). The rule list is a value: a
buyer's own additions (`com.example.legacy.`) are rules like any other.

## Behavior

- [x] a business class that constructs `java.net.Socket` is a finding naming the class, the member, the API and the rule's `why`; the build fails
- [x] a lambda (`LambdaMetafactory` bootstrap), a string concatenation (`StringConcatFactory`) and a record's `ObjectMethods` in a business class are NOT findings
- [x] `Class.forName`, `Lookup.findStatic`, `ObjectInputStream` and an `ACC_NATIVE` method in a business class are findings — the escape hatches are closed by the same check
- [x] a jar on a business project's classpath is scanned under the business rules and a finding names the jar, so a library that does I/O must move to the handler layer or be allowed by name
- [x] a handler module's references are listed, never failed; the inventory groups them by provider (the owner's top package) and names the module
- [x] okay core and its platform are the `Runtime` layer: listed as such, their thread and socket use is not a finding anywhere
- [x] an `Allow` has a module, an API, a reason and an owner; one without a reason is refused by name before any scan; an allow nothing matched is reported as unused
- [x] the report's inputs carry each jar's and directory's sha-256 so a report ties to one build; `text` and `json` are deterministic (sorted) for the same inputs
- [x] a project without a layer is `Untracked` in the report, not failed
- [x] dogfood: `audit` over okay's own build with `okay`/`okay-async`/platform as `Runtime`, the I/O modules (`okay-http`, `okay-sql`, `okay-persist`, `okay-r`, `okay-py`, ...) as `Handlers`, and `okay-java`'s examples plus one chosen pure module as `Business` passes, and its inventory names every provider those modules touch — the first inventory for the evidence pack
- [x] cost: the whole okay classpath scans under the time of one small test suite (measure and record in Results)

## Out of scope

- **Runtime enforcement** (a `ScopedValue` "inside a handler" plus an agent intercepting I/O constructors): layer 4 of the business document, only if a buyer requires enforcement in production rather than at build. Not this module.
- **JPMS descriptors and JVM flags** (`--illegal-native-access=deny`, no `--add-opens`): stage 3 records them into the report from a launcher config; this module does not generate `module-info.java`.
- **Proving the core**: the `!` type and handlers follow the type-and-effect discipline (Koka, Eff, Frank); the core's evidence is its size and its tests. Scala 3 capture checking is experimental and not relied on.
- **Reflection by string**: a class loaded by a name the scanner cannot see is exactly why reflection and class loading are themselves forbidden in the business layer; the scanner does not follow them.
- **Hermetic replay in CI** (no network, read-only filesystem; the replay must be byte-identical): okay-watch's job, in its own specs.

## Design

- Zero dependencies, JVM only (the scanner reads jars and directories;
  a Scala.js build has no class files to audit). `okay-codec` for the JSON
  report — in-house, already a dependency of everything that reports.
- The class-file reader is the one `src/test/scala/TestInlineBudget.scala`
  already carries (JVMS §4, one screen, `methods(cls)`), lifted into
  `okay.audit.Scan` and extended from "method code lengths" to "the constant
  pool's references plus each method's access flags". Not
  `java.lang.classfile`: dotty 3.9 cannot load its sealed model types
  (resume-inline-budget-guard, specs/core-gaps.md stage 4).
- A reference is read from `CONSTANT_Class`, `CONSTANT_Fieldref`,
  `CONSTANT_Methodref`, `CONSTANT_InterfaceMethodref`,
  `CONSTANT_MethodHandle` and the bootstrap of every
  `CONSTANT_InvokeDynamic`; plus `ACC_NATIVE` on own methods. Descriptors
  are kept so a rule can name an overload (`Date#<init>()`).
- Matching is by prefix on the owner's binary name with `/` turned to `.`,
  and by `owner#member` for a member rule; the three bootstrap exceptions
  are the only hard-coded carve-out, and they are listed in the report's
  header so a reader sees them.
- The constant pool OVER-approximates: a reference in dead code still counts.
  That is the right direction for a boundary — the check is sound for every
  direct reference and says so; what it cannot see (reflection, class
  loading, native code) it forbids instead.

## Decisions

- **Bytecode, not a Scala compiler plugin or Scalafix rule** — covers Java,
  Kotlin and Clojure callers of the facade and third-party jars; a plugin
  sees only Scala sources of the current build.
- **Own scanner, not ArchUnit** — ArchUnit is a dependency with its own
  classpath model and no inventory output; the rule set here is a value and
  the inventory is half the point. Not `jdeps` — package-level only; it
  cannot name `System#currentTimeMillis` or fail on a member.
- **Three layers, not two** — okay's own core spawns threads and opens
  sockets (Async, the platform); calling it "handlers" would hide it in the
  inventory, calling it "business" would fail it. `Runtime` names it for
  what it is: the trusted base below the handlers.
- **Fail only business** — handlers are supposed to touch the world; their
  job here is to be LISTED. A finding is a reach from where none should be.
- **Allows need an owner and a reason** — the Ack pattern of
  specs/security.md: anything weaker than the default is a named decision.
- **Carve-outs are listed in the report's header, every run** — the
  bootstraps, the bootstrap types, the lazy-val idiom. A reader who
  distrusts the check sees exactly what it chose not to count.

## Stages

1. `okay-audit` module: `Scan`, `Boundary.Default`, `Audit.run`, text report,
   sbt `auditLayer`/`audit`, dogfood on okay's own build. Tests: a fixture
   module of hand-written classes for each behavior item above.
2. JSON report with input hashes, allows, unused allows, inventory by
   provider; the CLI jar for Maven/Gradle users of the Java facade.
3. Launcher check: read a deployment's JVM flags and module descriptors,
   record `--illegal-native-access`, `--add-opens`, `--enable-native-access`
   into the report as configuration evidence.
4. (okay-watch) implemented: Dagger runs `auditDomain`; evidence tests replay
   kept port answers and require byte-identical reports. The Java-only
   `tools/test-evidence-hermetic.sh` additionally proves export under macOS
   OS-denied network access and writes to original evidence in a strict
   jlinked image. Local checks passed; the full Dagger pipeline was not run.

## JPMS — what the JVM's module system adds, and what it cannot (decided 2026-10-05)

The executable Stage-A contract is in `specs/jpms-boundary.md`.

The question: can JDK 9+ modules enforce the boundary this spec checks by
scanning? Partly, and the part it enforces is worth taking; the part it
cannot is why the scanner stays.

**What JPMS enforces at run time, per module, with no scanner:**

| Reach | JPMS mechanism | Covers rule |
|---|---|---|
| `java.sql`, `javax.sql` | not readable without `requires java.sql` | databases |
| `javax.naming`, `java.rmi`, `java.net.http`, `java.util.logging`, `java.management`, `java.scripting`, `java.desktop` | each its own module; unreadable unless required | network (part), console (logging) |
| `sun.misc.Unsafe` | lives in `jdk.unsupported`; unreadable unless required | escape hatch |
| `java.lang.foreign`, JNI `System.load*` | restricted methods (JEP 472): denied unless `--enable-native-access=<module>`; `--illegal-native-access=deny` turns the warning into a refusal | foreign, native |
| deep reflection into the JDK and into other modules | strong encapsulation: fails without `--add-opens` / `opens` | reflection (part) |

**What it cannot enforce, ever:** everything in `java.base` — `java.net`
(sockets), `java.nio.file`, `java.io.File*`, `Thread`, `System.currentTimeMillis`,
`Random`, `Class.forName`, `MethodHandles`, `ClassLoader`. Every module
reads `java.base`. So the business rules that matter most for replay (time,
randomness, files, sockets) are scanner-only; JPMS adds a second,
JVM-enforced witness for the rest, and the launcher flags for native code.

**What JPMS adds beyond enforcement — the inventory from the live JVM:**
`ModuleLayer.boot().modules()` and each `ModuleDescriptor.requires()` name
what is actually loaded, at run time, not at build. okay-watch already
derives its shipped runtime this way (`jdeps --print-module-deps` → `jlink`,
okay-watch specs/deploy.md): a jlinked image that carries no `java.sql`
module cannot run SQL, whatever the jar contains. That image's module list
is inventory evidence too.

**The obstacle in okay itself: split packages.** okay's modules share the
package `okay` (`okay.Optic` in okay-optics, `okay.Hlc` in okay-data, the
core's `okay.*`), and JPMS forbids one package in two modules — named OR
automatic, on the module path. So okay's modules cannot become named
modules without one package per module, and cannot even sit on the module
path as separate automatic modules. As ONE shaded jar (okay-watch's assembly,
module-info discarded) okay is one automatic module and the rule holds.

**Decision — three stages, none blocking the other:**

- **A. okay-audit reads descriptors (stage 3, cheap, no packaging change).**
  Implemented in `specs/jpms-boundary.md`. `Jpms` reads `module-info.class` where present (`ModuleDescriptor.read`),
  and the report says per rule whether it is *JVM-enforced* for that module
  (named deployment, java.base-only descriptor, absent target and no
  add-reads override) or *scan-only*; unresolved transitive dependencies
  stay scan-only. This is direct-linkage evidence, not a sandbox. It detects
  split packages across inputs ("not JPMS-ready: package `okay` in okay-optics
  and okay-data"); records the launcher flags (`--illegal-native-access`,
  `--add-opens`, `--add-exports`, `--add-reads`, `--enable-native-access`) from a jvm-options
  file; and gains `Audit.runtime()` — a self-check an app calls at startup
  that writes the boot layer's modules, their requires, native-access grants
  and the input arguments into its evidence journal (okay-watch first).
- **B. The deployable as a named module (okay-watch).**
  Implemented as the strict opt-in headless profile in okay-watch's
  `specs/jpms-deployment.md`, not a replacement for desktop/JDBC installs.
  The shipped assembly runs the offline synthetic scenario, retains seven
  providers, and verifies SQL/Unsafe/reflection/native refusals. Its scanner
  manifest now classifies `okaywatch.domain` as business, mixed app code as
  handlers and dependencies as runtime; audit-ready Stage 4 passed 219
  domain classes without findings or allows (okay-watch `b24d2e0`).
  The original deployment goal is okay as one automatic
  module (the assembly), the app's own code (`okaywatch.*`) as a named module
  with an explicit `requires` list, jlinked without `java.sql`/`jdk.unsupported`
  unless a handler needs them, launched with `--illegal-native-access=deny`
  and no `--add-opens`. The JVM then enforces the first table for the app's
  business code; the scanner covers `java.base`. The two reports must agree.
- **C. One package per module, `module-info` everywhere — okay2, not okay.**
  Implemented in `specs/jpms-module-layout.md` (2026-10-05): 21 own
  JVM artifacts, disjoint migrated namespaces, named BlockingDefaults
  service, packaged module-path probe and optional interop bundle.
  `okay2/MODULES.md` records the source/binary migration contract.
  The rename is the price and okay2 is where it is affordable; then
  `uses`/`provides` can state handlers as services ("this module provides
  the Db handler"), and the descriptor itself becomes the effect/handler map.
  Spark (okay-spark) stays out: its own JPMS story is unfinished upstream
  (the `--add-opens` list in build.sbt is Spark's, not ours).

Not chosen: `SecurityManager` (removed in JDK 24); a custom
`ModuleLayer` for business code that excludes `java.sql` etc. — real, but
stage B gives the same with jlink and no runtime machinery of ours.

## Results

Stage 1 (2026-10-05, this lane). `okay-audit`: `Scan`, `Boundary`, `Audit`,
`Report`, `Main`; sbt `auditLayer` per project, root `audit`; `TestAudit`,
12 tests on Java and Scala fixtures.

**Dogfood, 24 JVM projects + the Scala library as one `runtime` row, 53
scanned inputs (class directories and third-party jars), 21 s warm for the
whole task including sbt.** The first run FAILED with 232 findings, and
all of them were right:

- **The lazy-val idiom.** Every Scala 3 module with a `lazy val` reached
  `MethodHandles#lookup`, `Lookup#findVarHandle`, `VarHandle#compareAndSet`
  (okay-optics, okay-lex, okay-crdt: exactly these 4 references each).
  A VarHandle reaches memory, not the world, and `lookup()` alone reaches
  nothing — carved out (`Boundary.LazyValIdiom`); `Lookup#findStatic`,
  `#findVirtual`, `#unreflect*`, `#defineClass` stay findings and the
  `Reflect` fixture calls `findStatic` to prove it.
- **Bootstrap types as bare classes.** javac writes `MethodHandles`,
  `MethodHandles$Lookup`, `MethodType`, `CallSite` into the pool as bare
  `CONSTANT_Class` entries (InnerClasses) for every lambda; a bare class
  reference to one of `Boundary.BootstrapTypes` is part of the carve-out, a
  MEMBER reference is not.
- **Native methods needed their own rule**: an own `ACC_NATIVE` method has
  the class itself as owner, so no API rule matched it — `Boundary.Native`.
- **Four modules were not business.** okay-codec (files, processes, TLS,
  SecureRandom, `sys.env`), okay-bayes (`scala.util.Random` in 50 places,
  a shutdown hook, `Thread`), okay-java (`Files.lines`, `reflect.Array`),
  okay-data (`Hlc` reads `System.currentTimeMillis`, `Uid` reads
  `Random.nextLong`). All four moved to `handlers`; okay-data's two are
  genuine design findings — a clock and a random source in a data module —
  filed in backlog.d/okay-data.
- Business after the first run: okay-optics, okay-parse, okay-lex,
  okay-crdt — PASS. Untracked: none of the 24.
- **The inventory shows what nothing else in the build does**: okay-kafka's
  jars reach JNI (`LZ4JNI`, `XXHashJNI`, snappy's `BitShuffleNative`) and
  `sun.misc.Unsafe`; okay-jetty reaches `javax.naming`, `java.sql.DriverManager`
  and three randoms; the Scala library itself reaches `Class.forName`,
  `ClassLoader`, `URL#openStream`. That is the Art. 8 inventory an auditor
  asks for, from one command.

The self-report is committed as `okay-audit/dogfood/report.txt` (local paths
relativised; `maven2:` for the Coursier cache) so it can be read and linked
without a build; `sbt audit` regenerates it.

Deviation from the Interface above: `Report.json` is written by a
twenty-line writer of its own, not okay-codec's `Json` — the module stays
at zero dependencies so a Maven/Gradle user of the Java facade (stage 2)
can run it alone.

## Consolidation (2026-11-13, audit-consolidate)

Three checkers had grown over the same question — "does code that should only
perform effects reach the world, or receive what is not data?": this module,
okay-java's resumable guard (`--forbid`, presets named after okay.rows.std's
effects, the static-state rule) and okay-safe's whole-program check (a
deny-by-default allowlist, the boundary rule on types, a sealed deployment
verified at launch). The operator approved one checker: okay-audit, on
bytecode, zero dependencies, its report evidence. The other two keep only
what a bytecode check cannot see (okay-java: the transformation's own rules;
okay-safe: what TASTy alone carries) and call this module for the rest.

### Interface (additions)

```scala
final case class Rule(api: String, why: String, use: String = "")   // `use`: the effect to perform instead
object Presets:                       // named after the standard effects
  val all: Vector[Preset]            // state, reader, writer (= trail), clock, fresh, async, io, nested-run, escape
  def rules(list: String): Vector[Rule]  // "clock, fresh, java.lang.Thread" — presets, `pure` (all), `strict`, entries
final case class Allowlist(entries: Vector[String])   // deny by default for business: what it may name
final case class Types(data: Vector[String] = Types.Data, answers: Map[String, Int] = Map.empty)
final case class Boundary(..., allowlist: Option[Allowlist] = None, types: Option[Types] = None)
object Scan:
  def parse(bytes: Array[Byte]): ClassInfo   // + fields, methods, code sites with lines, Signature attributes
object Code:
  def check(info: ClassInfo, methods: MethodInfo => Boolean, rules: Vector[Rule], classes: Classes): Vector[Hit]
object Seal:                           // a build that proves it was checked; verified by a launcher
  def of(report: Report, config: String, files: Vector[(String, String)], fields: Vector[(String, String)]): Seal
  def verify(seal: Seal, present: Vector[(String, String)], ...): Either[String, Unit]
```

### Behavior

- [x] every rule may carry `use`, the effect to perform instead (okay.rows.std
  for Java, okay-scala's `okay.scala3.std`, okay-safe's `okay.safe.std`); a
  finding shows it (`— use …`) in text and json; `Boundary.Default` names one
  for every rule that has a replacement
- [x] presets named after the standard effects — state, reader, writer/trail,
  clock, fresh, async, io, nested-run, escape; `pure` is all of them, `strict`
  okay-java's former list — select rules by name; an entry that is no preset
  is a rule (okay-java's `pkg.*` and `Owner.member` spellings accepted)
- [x] a class or member rule matches up the owner's superclasses and
  interfaces, as far as their class files are found among the inputs and the
  JDK: a subclass of `Thread` is a `Thread`, `String#wait` is `Object#wait`
- [x] the nested-run rule: business code calling a top-level runner
  (okay.rows `Effects.run/step`, okay-scala's `run`, okay-safe's `run`/`step`,
  okay-direct's `Effects`) is a finding naming what to do instead
- [x] the state rule: a business method writing a static field that is not
  final (outside its own class's initializer), or a field of a Scala `object`
  outside its constructor, is a finding; reading a non-final static is SAID
  (listed, not failed) — a static nothing writes may be a constant
- [x] the allowlist mode: with `allowlist` set, a business reference that no
  rule names is still a finding unless it is to the module's own classes, to a
  runtime module's, or matches an entry; a reference to a handlers module's
  class is a finding of its own ("names a handler")
- [x] the boundary rule on types (with `types` set), from descriptors and
  `Signature` attributes, so for Java, Kotlin and Scala alike: what a handlers
  or runtime module passes to a business method or constructor, and an
  operation's answer (`answers`: an effect type and which type argument is its
  answer), must be first-order data — primitives, strings, numbers, the
  `data` list, immutable Scala collections/options/tuples of data, and
  business or runtime classes that are final or closed (no handlers or
  runtime class extends them) with final fields of data; a function, `Object`,
  an array, a library class or a handler's class is a finding saying which
- [x] a business class naming a handlers module's class in a field, a method
  signature or a reference is a finding ("names the host's type") when `types`
  is set
- [x] a seal: the check's version, the configuration's hash, the result, the
  launcher options recorded, extra fields of the caller's, and the SHA-256 of
  every file; `Seal.verify` refuses a file added, changed or missing, a failed
  result, and a JVM started without an option the seal records
- [x] okay-java's resumable plugins and okay-safe's whole check and gate use
  these instead of their own copies (their repositories; Results below)

### Results (2026-11-13)

`TestConsolidation`, 13 tests beside `TestAudit`'s 19, on Java and Scala fixtures: a use hint in text and json; the
presets and both spellings; a library's `Thread` subclass and `Object#wait` matched up the hierarchy, invisible to
the same rules without it; the nested-run rule over okay.rows, okay-scala, okay-safe and okay-direct; a static
written (finding, with method and line), read (said), a final one (nothing), a Scala object's var, a lazy val's
storage (nothing); `Code.check` by method; the allowlist (own classes pass, a handler's class named, the rest
refused); the boundary on types — a client, a `Supplier`, `Object`, a library's `List`, an open class the host
extends refused as arguments the host gives, an `Amount` (final, final primitive field) passed, an operation
answered with the host's client refused, the host's type named in a signature and a reference; signatures with
generic arguments, nested classes, type variables; a seal verified, and refused for a file added, changed, missing,
a failed result, a JVM option absent; the JSON manifest's `rules`, `allowlist` and `types`.

**Where it moved from, and what stayed** (the other repositories' own records say the same):

- okay-java (resumable, `64cf488`): `forbid` and its presets are okay-audit's — `okay.resumable.transform.Audited`
  calls `Code.check` over the methods the transformation rewrites (effectful code = business) and words the hits as
  its refusals always read (method, line, what, the entry in its own spelling, the effect to use). Kept in its
  `Transformer`: the guard (a handler's clause called, an `@Implementation` reached — the transformation's own
  notion of a handler) and the monitor refusal (a `synchronized` method cannot suspend). okay-audit is on the
  transformer's class path only (Gradle: its configuration; Maven: the plugin's dependency), resolved from `~/.m2`
  (`okayAudit/publishM2`) until okay-audit has a release; a list named without it is refused by name. GuardTest's
  messages as before but for the use hint, now okay-audit's (one wording for Java, Scala and the TASTy check); 261
  okay-java tests, the Maven sample and the Gradle sample green (`forbid = "strict, pure"`).
- okay-safe (`cafe28e`): the whole-program check's ambient APIs are okay-audit's presets read as TASTy paths
  (`Rule.sourcePath`), its JDK data list okay-audit's `Types.Data`; its gate runs okay-audit's bytecode check beside
  the TASTy one (a Java class beside the program has no TASTy: refused now by the bytecode witness, SealTest) and
  writes okay-audit's seal; `Launch` verifies with `Seal.verify*` and keeps the kernel's own checks (the flags, the
  whole result, `Unsafe` tried rather than the arguments read, the three modules hashed). Kept on TASTy, because
  bytecode erases them: the allowlist of names with positions (a cast is a `checkcast` the compiler emits anyway; an
  annotation like `@unchecked` or `caps.unsafe` is gone), the boundary rule on Scala types (unions, opaque types,
  `sealed`, capture sets), safe mode imported, the cc and explicit-nulls attributes, `@assumeSafe`, `throw`/`try`.
  205 + 1 tests green; SealTest's added cost measured at ~50–110 ms per sealed build (the audit's own time) after a
  rule's api is read once and a reference matched once per audit.

**The dogfood** (`sbt audit`, 24 projects): PASS. One build change: a jar a RUNTIME project brings is the runtime's
row, not each business project's — the okay-core artifact (in a worktree without the submodule) and the JMH jars
that `JmhPlugin` puts in the core's Compile classpath had made every business project "own" jopt-simple and
jmh-core (3670 findings, all in those jars, none in our classes). The inventory now shows them under the runtime;
that the core's Compile classpath carries JMH at all is filed (backlog.d/okay-audit/core-jmh-in-compile.md).

Decisions:

- **Code sites, not only the pool**: the constant pool stays the sound base of the module check (it
  over-approximates, dead code included); the instruction walk adds what the pool cannot say — which method, which
  line, a read from a write — for the state rule and for a caller that scopes by method (okay-java).
- **Hierarchy from the inputs, then this JVM's resources**: a class file is read as a resource never encapsulated,
  so the JDK's are found without a JDK path; a class nowhere found ends the walk (the rule does not match up it).
- **Closed, not sealed**: bytecode does not carry Scala's `sealed`; "nothing of the host's extends it" is read over
  the scanned classes — whole-program, so sound for what is given, as okay-safe's check is for its TASTy.
- **The seal's JVM options are data the launcher may check by reading the arguments (`verifyJvm`) or by trying the
  effect**: okay-safe tries (`Unsafe` refused), which is stronger and skips `java.management`'s start cost.

## The first real-world run (2026-11-13, audit-real-world)

okay-audit 0.1.0 was run, read-only, over twenty repositories of the okay-effects family
(`~/work/my/okay-audit-report/REPORT.md`, its per-repo `report*/` folders and the scala-cli `tools/` that wrote the
manifests). Only two passed; most of the rest were real reaches, but ten false-positive classes and four limitations
made the reports hard to read and two modes (allowlist, libs) unusable. This section is what the module learned.

### Behavior

- [x] **Scala 3.3's lazy val** (`LazyVals$.getOffsetStatic(C.class.getDeclaredField("x$lzyN"))` in the class's
  initializer) is carved out like 3.8's VarHandle idiom: a `Class#getDeclaredField` site whose string is
  `…$lzyN` and whose next call is `scala.runtime.LazyVals$#getOffsetStatic` is not a reach; any other
  `getDeclaredField` (another name, or the field used for anything else) stays a finding. The type rule does not
  read a lazy val's cell (`x$lzyN`, `bitmap$N`) as "a var" (the state rule already did not)
- [x] a `packages` key names a class as well as a package: `okay.http.JdkHttp` covers `okay.http.JdkHttp$`,
  `okay.http.JdkHttp$JdkClient`, `okay.http.JdkHttp$$anon$1` (`Cls$…`), not `okay.http.JdkHttpX`
- [x] the allowlist mode allows a reference to any class of a business or runtime row (another business module of
  the build, a dependency marked business or runtime); a handlers class is still "names a handler", an untracked
  or unknown one is still checked against the list
- [x] randomness: `java.util.Random#<init>(J)`, `scala.util.Random#<init>(J)` (and `SplittableRandom(J)`) — a
  seeded generator, deterministic — and calls on such an instance are not findings; `new Random()`, the
  `scala.util.Random` object, `ThreadLocalRandom`, `SecureRandom`, `UUID#randomUUID`, `Math#random` are
- [x] `java.net.URLEncoder`, `java.net.URLDecoder` and `java.net.URI` (string codecs and a value) are out of the
  `java.net.` rule, `URI#toURL` is not; a rule carries its exceptions (`Rule.except`) so the carve-out is listed
  with the rule, in the report's header
- [x] the type rule, precision: (a) a host calling `unapply`, `unapplySeq`, `copy`, `copy$default$N`, `_N`,
  `productElement`, `equals`, `hashCode`, `toString`, `canEqual` of a business class, or a business trait's own
  implementation for itself (`$init$`, a static `m$` the host's class inherits), is not "the host gives";
  (b) `types.programs` (default: okay-scala's `okay.scala3.Prog`, okay.rows' `okay.rows.Comp`, okay-safe's
  `okay.safe.Comp`, okay-java's `okay.core.Eff`, nested classes with them) are programs: a value the host gives,
  not a function; (c) a `java.lang.Object` in a SCALA class's descriptor or signature (a union, an opaque type, a
  boxed type argument, `Any` — erased, and only TASTy could say which) is "unknown (erased)": said, not failed; in a
  Java class it stays a finding; (d) one type finding per (class, target, reason) with its occurrence count;
  (e) the report suggests `types.data` entries: a final business or runtime class with final fields that fails only
  through a leaf (an array, `Object`, a library class) and is named in two or more findings
- [x] `nested-run` is its own preset, NOT in `pure` (decision below); `runIsolated` (okay-scala's
  `Prog$package$`/`Cek$`), `okay.rows.Effects#runIsolated`/`#stepIsolated`, okay-java's `okay.core.Eff#runIsolated`
  — the sanctioned run of a closed sub-program — never match it
- [x] a Scala static forwarder (a static method `m` of class `C` calling `C$#m`, `X$package` → `X$package$`) is not
  where a reach is made: its reference is not a finding of `C`, the module class's own is
- [x] every finding carries `at` — the method and the LineNumberTable line of its first site, and how many sites
  (`count`); a reference no code makes (a class header, InnerClasses) says `class header`
- [x] the state rule, held collections: a business method that loads a static field or a Scala object's field whose
  type is a mutable collection (`scala.collection.mutable.`, `scala.collection.concurrent.`, `java.util.` maps,
  lists, sets, queues and their concurrent kinds, `java.util.concurrent.atomic.`) and calls a mutating method on that
  type (put, add, update, remove, clear, `+=`, compareAndSet, …) outside the owner's initializer is a finding; the
  same through an instance field of a business class is said. `scala.concurrent.Promise` is in `async`
- [x] a module may be `"audited": "elsewhere"`: its classes inform the hierarchy, the allowlist and the type rule
  under its layer, its own findings, said lines and inventory are not repeated — it is listed once, by layer
- [x] findings in a jar (a third-party library under a business row) are rolled up in the text report, one line per
  jar: how many, by group (network, files, escape hatch, …); the JSON keeps every finding, with its `source` input
- [x] getting the inputs: `okay-audit/tools/export.scala` (scala-cli, no Python) writes, for any sbt build, one line
  per project — name, class directory, runtime classpath — and a skeleton manifest with every project `untracked`;
  `specs/audit-cli-standalone.md` documents it and the by-hand `export` recipe

### Decisions

- **nested-run out of `pure`, into its own preset.** Whether a run's program is closed (`A ! Pure`) is not
  decidable at the call site from bytecode: the call's descriptor is erased (`Prog`), and the callee's `Signature`
  is the same for every call. In okay-scala `run` exists ONLY for `Prog[Pure, A]` (Cek.scala), so "closed" exempts
  every call there and the kernel itself refuses a nested run while a program runs (`NestedRun`). What a static
  check can tell is only that a run happens in business code — a design rule, worth selecting, not a reach past
  the boundary; the real cases met (proof search in okay-proof, a price table in coinpath) were runs of closed
  computations. `runIsolated` is the kernels' name for the deliberate one and never matches.
- **TASTy is not read for the erased `Object`**: a TASTy reader is a second format the size of the class-file
  reader, and okay-safe already reads TASTy where it is the whole check. The bytecode check says what it cannot
  know instead of failing on it.
- **Held collections by load-then-mutate in one method**, not data flow: a method that loads the field and calls a
  mutating method on its type is counted; it over-approximates (another receiver of the same type in the same
  method) and says so in the finding's why.

### Results (2026-11-13)

`TestRealWorld`, 13 tests beside `TestAudit`'s and `TestConsolidation`'s (45 in all, 4 s): the real Scala 3.3.8 class
file (okay-simple's `Shape$Deferred`, kept as bytes) passes with its `getDeclaredField`, and two other uses of
`getDeclaredField` are one finding counted twice; a lazy cell is not a var while a real one is; a Java `Object`
field is still a finding while a Scala union is said; a final wrapper over an array is suggested; `unapply`,
`copy`, `$init$` and `name$` from a host are not "the host gives" while a host's lambda still is; a program type is
a function without `types.programs` and a program with it; a class key covers `Edge$Client` and `Edge$1` and not
`EdgeX`; the allowlist passes another business or runtime module, names a handler, refuses an untracked class;
seeded `java.util.Random`, `scala.util.Random` and `SplittableRandom` pass while the unseeded, global, secure and
thread-local ones are placed findings; `URLEncoder`/`URLDecoder`/`URI` pass under `default`, `io` and `strict`
while `URI#toURL` and a socket do not; `runIsolated`/`stepIsolated` never match `nested-run`, which `pure` lacks; a
static forwarder adds no finding; held collections in Java statics and a Scala object are findings at their
method, an instance's map is said, a table filled in the initializer is nothing; an elsewhere module is listed and
not repeated; a jar's findings roll up to one line; the manifest's `audited` and `types.programs`; `Export`'s
parsing and skeleton on sbt's real output.

**The re-run over the 20 repositories** (`~/work/my/okay-audit-report/REPORT-2.md`; same inputs, the sha-256 of
every input equal to the first run's): own-module findings 1374 → 715 (direct 475 → 298, type rule 899 → 417),
356 lines said; the allowlist mode 25 354 → 4 867 (okay-mall 4 939 → 120); the libraries run's text 60 584 → 5 290
lines (okay-uplc 29 021 raw findings → 4 180 distinct reaches over 50 jars). By false-positive class, v1 → v2: the
3.3 lazy val 48 → 0, lazy cells as vars 107 → 0, `Object says nothing` 248 → 0 (235 said as erased), program as
a function 25 → 1 (a real thunk returning a program), unapply/copy/mixin 263 → 0, pure `java.net` 18 → 0, seeded
random 18 → 0, nested-run 9 → 0 (now its own preset), bare class beside its members 135 → 25. New: 16 `Promise`
uses in business code, one global held map (okay-mall `Drawn.kept`), 120 instance-held collections said. Still
findings by design: `okay.http.Bytes` (52 → 37) until a manifest vouches for it — every repository using it now
gets the suggestion. The dogfood (`sbt audit`) still passes; its inventory no longer lists seeded generators.

Decisions taken while measuring:

- **A bare class reference is carried by the class's member references to the same owner** — a class rule matches
  both, so the bare one only doubled every line (`java.util.Random` beside `java.util.Random#<init>`); kept where
  no member names the owner (a `checkcast`, a header's type).
- **Held collections read the field's own type**: the first cut counted `getstatic Stack$.MODULE$` (a companion,
  under `scala.collection.mutable.`) and then any mutating call — okay-datalog's local stacks; the field must not be
  `MODULE$`, its type no companion, and the mutating call on that type or a supertype of it.
- **A jar on several business rows is one reach per (class, member, rule)**: the libs manifests put a dependency
  under every business project that has it (okay-uplc: five), which multiplied each library's count by five.
