- [ ] audit-real-world-left — what the 20-repo re-run (audit-real-world, 2026-11-13) left open:
      (1) an erased `Object` in a Scala signature is said, not judged — reading TASTy for the real type
      (a union, an opaque type, `Any`) would judge it; okay-safe reads TASTy already, the bytecode check does
      not. (2) Held collections are found by "a load, then a mutating call on its type in the same method",
      not data flow (since audit-held-receiver the call's receiver must be the load's value, an operand-stack
      origin; a value passed through a call or a field is lost); arrays held in statics and mutated by `*astore` are
      not seen. (3) A Java caller of a
      Scala object's static forwarder (`Cek.run`) does not match a rule written on `Cek$#run`. (4) okay-java's
      `Transformer` javadoc: fixed in its repository with the move to okay-audit 0.1.2.
      (5) `okay.http.Bytes`: decided, data by default since 0.1.3 (audit-data-bytes, 5ad28468e); its copying
      factories `apply`/`body` trusted since 0.1.4 (audit-copying-factories, `types.factories`).
      (6) `Export` reads only the root build's projects (not a `ProjectRef`'s other builds).
