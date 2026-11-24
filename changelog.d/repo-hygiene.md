## repo-hygiene — the repository put in order: claims, worktrees, the old board, stray trees, clashing module names

The author's ask of 2026-11-24, in five commits.

- 943b7b9ac: the stale `handler-single-pass-rest` claim (2026-10-05) released; `spark-warehouse/` ignored.
  Outside git: 15 clean, fully merged worktrees removed with their branches; four kept with unmerged
  work (cluster-pool-numbers 1, freer-two-part-row 4, jpms-boundary 27, pinned/okay-chat 1) and
  `.work/pages` (gh-pages); target-only leftovers of okay-cont, okay-docs-*, okay-leads deleted.
- d04bb079a: BACKLOG.md is a pointer to backlog.d/ like SPRINT.md; twenty items found in neither
  backlog.d/ nor the archive (all landed since) went to BACKLOG-ARCHIVE.md verbatim.
- fa2b29144: jdk22/ and jdk25/ live under their hosts, okay-freer/jdk22/ and okay-platform/jdk25/.
- c96ada141: okay2/, okay-compose/, okay-swift/, tools/, upstream/ and docs/okay2.md moved to the
  private repository okay-effects/okay-archive (`okay/`); tag `archive/2026-11-24` keeps their history.
  okay-chat, okay-agent, okay-http, okay-llm, okay-mcp and okay-ui stay: modules in the build depend on
  each. The okay2 job in .github/workflows/ci.yml waits on branch feature/ci-drop-okay2 (the push
  token had no `workflow` scope; `gh auth refresh -s workflow`, then fast-forward and push it).
- The renames, against the sibling repositories of the same names: okay-kernel → okay-plugins
  (`okayPlugins`), okay-java → okay-jdk (`okayJdk`, package `okay.java` → `okay.jdk`), okay-rust →
  okay-native (`okayNative`), okay-direct → okay-syntax (`okaySyntax`). Packages `okay.kernel` (okay-watch
  imports it) and `okay.rust` (`native` is a Java keyword) and okay-syntax's `okay` stay. Downstream builds
  that name the old project ids (okay-watch's `okayKernelJVM`) change when they move their okay pin.
