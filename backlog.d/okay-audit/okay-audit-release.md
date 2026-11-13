- [ ] okay-audit-release — okay-java's resumable plugins and okay-safe
      resolve `io.github.okay-effects:okay-audit_3:0.2.0-SNAPSHOT` from
      `~/.m2` (`scripts/gate.sh "set okayAudit / publish / skip := false;
      okayAudit/publishM2"`, audit-consolidate 2026-11-13). Publish it where
      a user's build finds it — okay's Pages Maven repository
      (scripts/pages-publish.sh, which needs okay-audit in
      project/ReleaseWave.scala) or its own release — and point both
      repositories' resolvers at it. Zero dependencies but the Scala library,
      so the wave stays closed.
