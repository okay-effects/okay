- [ ] audit-consolidate — okay-audit absorbs the two overlapping checkers built
      2026-11-13 (operator approved): okay-java's resumable guard/forbid
      (presets state/reader/writer/clock/fresh/async/io/nested-run/pure, each
      naming the replacement effect; subclass matching; PUTSTATIC refused,
      GETSTATIC said) and okay-safe's whole-program check (deny-by-default
      names, the boundary rule on types) and its seal + Launch verifier.
      okay-audit gains: (a) an allowlist (deny-by-default) mode for business;
      (b) `use` per rule, shown in findings, presets named after the standard
      effects; (c) the nested-run rule; (d) a code scan (methods, lines,
      Signature attributes, hierarchy) with the state rule and the boundary
      rule on types (first-order data only from handlers/runtime into
      business); (e) a seal and its verifier. Then okay-java's plugins and
      okay-safe's whole/gate delegate to it (their repos). Spec:
      specs/okay-audit.md, section "Consolidation".
