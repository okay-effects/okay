## audit-real-world - okay-audit learns from its first real-world run (20 okay-effects repositories)

- Twelve lessons of the read-only run over the okay-effects family (~/work/my/okay-audit-report/REPORT.md),
  specs/okay-audit.md "The first real-world run": Scala 3.3's lazy val carved out (code and type rule); `packages`
  keys name a class with its `Cls$…`; the allowlist passes other business and runtime modules (and the pure
  `java.net` values); seeded `Random`s are not reaches; `URLEncoder`/`URLDecoder`/`URI` are values (`Rule.except`);
  the type rule skips `unapply`/`copy`/mixin calls, reads `types.programs` as programs, says an erased Scala `Object`,
  counts per (class, target, reason), suggests `types.data`; `nested-run` leaves `pure` (decision in the spec;
  `runIsolated`/`stepIsolated` never match); static forwarders deduplicated; every finding has `at`, `count`,
  `source`; held mutable collections are state, `Promise` is async; `"audited": "elsewhere"` rows; per-jar
  roll-up; `okay.audit.Export` (inputs and a skeleton manifest from any sbt build). TestRealWorld (13), 45 in all.
- Re-run on the same inputs (REPORT-2.md there): own findings 1374 → 715, allowlist mode 25 354 → 4 867, the libs
  report 60 584 → 5 290 lines; every false-positive class at 0 but a real thunk and `okay.http.Bytes` (suggested).
- scripts/worktree-remove.sh: land.sh's step 5 deinits a clean okay-core submodule (refusing local changes or
  unpushed commits) before `git worktree remove`, which refused any worktree with it initialised.
- Commits: 4daed3cb7 (spec), a1d864013, and this lane's follow-ups.
