- [ ] audit-held-receiver — okay-audit 0.1.1's held-collection rule paired "a static load" with "a later mutating
      call on that type" in one method, so okay-java's GuardTest `Ambient.clean` (`NAMES.size()` read, a local
      `ArrayList` added to) was reported. The mutating call's RECEIVER must be the value the load produced: a
      small operand-stack tracking in the scanner. Then okay-audit 0.1.2 on okay's Pages maven.
