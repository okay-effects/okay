- [ ] audit-real-world-left — what the 20-repo re-run (audit-real-world, 2026-11-13) left open:
      (1) an erased `Object` in a Scala signature is said, not judged — reading TASTy for the real type
      (a union, an opaque type, `Any`) would judge it; okay-safe reads TASTy already, the bytecode check does
      not. (2) Held collections are found by "a load, then a mutating call on its type in the same method",
      not data flow; arrays held in statics and mutated by `*astore` are not seen. (3) A Java caller of a
      Scala object's static forwarder (`Cek.run`) does not match a rule written on `Cek$#run`. (4) okay-java's
      `Transformer` javadoc still says `pure` is every preset including `nested-run` (its repository).
      (5) `okay.http.Bytes` is suggested as `types.data` in 9 repositories — a decision for okay-http's owner.
      (6) `Export` reads only the root build's projects (not a `ProjectRef`'s other builds).
