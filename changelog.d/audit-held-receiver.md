## audit-held-receiver - okay-audit 0.1.2: a held collection's mutating call must be on the field's own value

- okay-audit 0.1.1 paired a field load with the next mutating call on the field's type in the same method;
  okay-java's GuardTest `Ambient.clean` (`new ArrayList<>(NAMES.size())`, then `l.add(x)`) was refused for it.
  `Scan` now tracks the operand stack's origins (every opcode's effect in slots, dup/swap, forward joins, locals —
  a slot stored once kept after its store) and a call's `Site.recv` names the site whose value is its receiver;
  the held-collection finding requires it to be the load. `Hit.holder` says what holds it (a static field, a
  Scala object's field, an instance's), so okay-java no longer words a final field "not final".
- Fixtures `rw/Held` (Java) and `rw/Drawn` (okay-mall's shape); okay-mall's real `Drawn$` still found; dogfood passes.
- Released as okay-audit 0.1.2 on okay's Pages maven (tag `okay-audit-v0.1.2`).
