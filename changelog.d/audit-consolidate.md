## audit-consolidate - okay-audit is the one checker: okay-java's forbid presets and okay-safe's whole check and seal moved onto it

- okay-audit gains presets named after the standard effects, each rule's `use` (the effect to perform instead) in
  every finding, matching up a class's hierarchy, the nested-run and state rules, a code walk with methods, lines and
  `Signature`s (`Code`, `Scan.parse`), an allowlist (deny-by-default) mode, the boundary rule on types (first-order
  data only from handlers/runtime into business), and `Seal` with its verifier; the JSON manifest takes `rules`,
  `allowlist`, `types`. `Automatic-Module-Name: okay.audit`. TestConsolidation (13); TestAudit unchanged and green.
- okay-java (resumable `64cf488`) delegates `forbid` to it, keeping the guard and the monitor refusal; okay-safe
  (`cafe28e`) takes its ambient APIs, data list and seal from it, its gate adding okay-audit's bytecode check; what
  stays on TASTy and why is in specs/okay-audit.md, "Consolidation".
- The dogfood passes; a jar a runtime project brings is the runtime's row (the core's JMH jars had made 3670
  findings in business projects' rows, none in our classes) — filed: backlog.d/okay-audit/core-jmh-in-compile.md.
- Open: okay-audit-release (both repositories resolve it from `~/.m2`).
