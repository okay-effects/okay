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
`escape`; `pure`, `strict`), each rule's `use` — the effect to perform
instead, shown in every finding —, matching up a class's hierarchy, the
state rule on code (method and line), an allowlist (deny by default) mode,
the boundary rule on types (first-order data from handlers/runtime into
business, read from descriptors and `Signature`s), and a seal with its
verifier for a launcher (`okay.audit.Seal`). What moved from where:
[`specs/okay-audit.md`](../specs/okay-audit.md), "Consolidation".

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
