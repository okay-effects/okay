## audit-held-receiver-warnings - okay-audit Scan: the four warnings audit-held-receiver landed with

- ci-runner's whole build (2026-11-14) was RED on four -Wall warnings in `Scan.sites` (discarded `pop()` and
  `pending.remove` values): a lane gate whose first compile failed a test reports the test, not the warnings, and
  every later gate was incremental over Scan.scala. Fixed with `drop(1)` and one `pending.remove` per instruction;
  `rm -rf okay-audit/target` then `okayAudit/test`: no warnings. okay-audit 0.1.2's jar is the same code but these
  statements.
