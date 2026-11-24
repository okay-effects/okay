## family-names - okay's facade speaks okay-effects' vocabulary (CONCEPT §3), the old names deprecated

- The author's decision (2026-11-24): okay keeps its own row model (unions keyed by the effect's class, order
  irrelevant); only the NAMES move to the family's. Every old name stays as a `@deprecated(…, "0.2.0")` alias,
  so the repositories that pin okay (okay-watch, okay-chat, okay-fin, nadia, okay-insure) still compile.
- Renamed (c955a8029): `effect(op)` → `perform(op)` (`op.perform` as before); `Handler.answering` →
  `Handler.tail`, `Handler.state` → `Handler.stateful`, `Handler.deep` → `Handler.general`,
  `Handler.aborting` → `Handler.abort`; `p.at[R]` and `Row.up` → `p.into[R]` (`p.plus[G]` stays: it adds
  an effect, not names a row); `p.ensure(fin)` → `p.ensuring(fin)`, also top level so Predef's `Ensuring`
  does not take it; `Throws.either` → `Throws.run`; `Fresh.counter` → `Fresh.run` (beside the runner
  `Fresh.run(p)`). New: `type Emit[W] = Writer % W` with `Emit.send`/`Emit.collect`; okay-std's
  Choice.scala is Choose.scala. okay-core's `Sensitive`, `Audited`, `Audit`, `audit`, `handleAudited`,
  `Pass`, `Interceptor`, `Observer` are on `import okay.*` — `Audit.observe`, not a top-level `observe`,
  which Logic's and Prob's (imported by wildcard) would make ambiguous. okay-syntax's staged blocks read
  `perform` and `into` as they read `effect` and `at`.
- okay's own modules, tests and the okay-std benchmarks moved to the new names (66184eef8): `Test/compile`
  is warning-free under `-deprecation`. Docs, AGENTS.md and the pinned examples follow;
  `TestFamilyNames` (okay-std) checks each new name and, in one `@nowarn` method, each old one.
- Left as it is: `State.run` runs to a value here, unlike the family's handler `State.run`; example lines in
  docs/snippet-debt.txt keep the old names (a line edited there counts as new and unpinned).
- specs/stack-safety-okay.tsv names Stm.sim's `finish`/`loop` (deferred through `attempt`'s `defer`), which the
  gate's recscan found once okay-stm was touched; they were on master unnamed.

Benchmarks (src/jmh of compare, okay-std, okay-syntax, okay-platform, okay-stream and others) on the new names too;
`RowLiftBenchmark` takes the key its walk needs (`using KeyOf[F]`), broken since the keyed root.
