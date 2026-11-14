# okay-audit — the effect boundary, checked at every build

Reports include JPMS descriptor names, requires, per-rule enforcement,
split packages and optional manifest-relative `jvmOptions` evidence.
`jvm-enforced` is conditional on named-module deployment; classpath inputs
and unresolved dependency graphs remain `scan-only`.
See [JPMS evidence](../specs/jpms-boundary.md). `Audit.runtime()` supplies
boot-layer modules and launcher arguments for application journals.

The type `A ! Db + Payments` says what a program *declares*. On the JVM
nothing stops its body from opening a socket as well. This module checks
the bytecode: a `business` module may reference none of the APIs that reach
past the boundary (network, files, sql, processes, time, randomness,
threads, reflection, class loading, native code); `handlers` and `runtime`
modules are listed, by provider — and that listing is the dependency
inventory DORA asks for (Regulation (EU) 2022/2554, Art. 8).

- Spec and results: [`specs/okay-audit.md`](../specs/okay-audit.md)
- The self-report over this repository's own build:
  [`dogfood/report.txt`](dogfood/report.txt) — regenerate with `sbt audit`
  (`target/audit/report.{txt,json}`)
- What is left: [`backlog.d/okay-audit/`](../backlog.d/okay-audit/)
  (CLI for Maven/Gradle, launcher-flags check, hermetic replay). The first
  real findings are resolved in [`specs/audit-ready.md`](../specs/audit-ready.md).

Zero dependencies. The report says what the check chose not to count
(compiler bootstraps, the lazy-val idiom) in its header, every run. It is
evidence for an auditor to read, not a compliance claim.

## One checker for the family (2026-11-13)

okay-audit is also what okay-java's resumable plugins (`forbid`) and okay-safe's
whole-program check and seal use: presets named after the standard effects
(`state`, `reader`, `writer`, `clock`, `fresh`, `async`, `io`, `nested-run`,
`escape`; `pure` — every effect preset but `nested-run` —, `strict`), each rule's `use` — the effect to perform
instead, shown in every finding —, matching up a class's hierarchy, the
state rule on code (method and line), an allowlist (deny by default) mode,
the boundary rule on types (first-order data from handlers/runtime into
business, read from descriptors and `Signature`s), and a seal with its
verifier for a launcher (`okay.audit.Seal`). What moved from where:
[`specs/okay-audit.md`](../specs/okay-audit.md), "Consolidation".

## What the first real-world run changed (2026-11-13)

Run read-only over twenty okay-effects repositories, it taught the check (specs/okay-audit.md, "The first
real-world run"): Scala 3.3's lazy val is the compiler's idiom; a `packages` key may name a class with its
nested ones; the allowlist passes other business and runtime modules; a seeded generator and the pure `java.net`
values (`URLEncoder`, `URLDecoder`, `URI`) are not reaches; the type rule leaves `unapply`/`copy`/mixin calls
alone, reads the kernels' program types as programs (`types.programs`), says an erased Scala `Object` instead of
failing on it, counts per (class, target, reason) and suggests `types.data` entries; `nested-run` is a preset of its
own, not `pure`'s (`runIsolated` never matches); static forwarders add nothing; every finding has its method,
line and count; a mutable collection held in a static or object field and changed is state (the call's receiver
must be the field's own value, since 0.1.2: a local list of the same type is not the field); a module may be
`"audited": "elsewhere"`; a library's findings roll up per jar. `okay.audit.Export <sbt-root> <out>` writes the
class directories, classpaths and a skeleton manifest of any sbt build
([`specs/audit-cli-standalone.md`](../specs/audit-cli-standalone.md), "Getting the inputs").

Since 0.1.3 the default `types.data` lists okay-http's `okay.http.Bytes` beside the JDK's and Scala's data (as the
default `types.programs` lists the kernels' program types): it is immutable — copied in, copied out —, so a
handler giving business code a `Bytes` or a `Request`/`Response` holding one is no finding; a raw `byte[]` still is
([`specs/okay-audit.md`](../specs/okay-audit.md), "okay-http's `Bytes` is data by default").

Since 0.1.4 a call to a COPYING FACTORY of a data type is not "the host gives": `types.factories` names members
(`owner#name`), and the default lists okay-http's `okay.http.Bytes$#apply` and `okay.http.Bytes$#body` (and their static
forwarders `okay.http.Bytes#apply`, `#body`, a Java caller's spelling), so a handler building a `Bytes` from a raw
`byte[]` is no finding. The rule TRUSTS THE LIST that the factory copies — it does not read the factory's body; each
default entry cites the test that proves the copy (okay-http `1cc7d09`, `HttpSuite`, "Bytes is immutable data: copied
in, copied out, no alias of a caller's array"). A manifest's `"types": {"factories": [...]}` adds to the list, and
whoever adds an entry vouches for it the same way. Anything not listed is judged as before: a raw `byte[]` given to
any other business method is still a finding ([`specs/okay-audit.md`](../specs/okay-audit.md), "Copying factories of
data types are trusted by name").

## Maven and Gradle

Build the `okay-audit` artifact once, then invoke its JVM main class from a
Maven `exec` goal or a Gradle `JavaExec` task; the audited project needs no
SBT classes or settings:

```sh
java -cp okay-audit.jar:scala3-library.jar:scala-library.jar okay.audit.Main \
  --manifest audit.json --report build/audit
```

`audit.json` names the built class directories and dependency jars under each
layer, package-prefix handler exceptions, and named allow decisions. Its
complete schema and exit-code contract are in
[`specs/audit-cli-standalone.md`](../specs/audit-cli-standalone.md).
