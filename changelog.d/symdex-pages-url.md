## symdex-pages-url - sbt-symdex resolved from okay-effects.github.io, where symdex now lives

symdex moved to the GitHub organization okay-effects (2026-10-18), and its Pages with it: the old
`sergey-scherbina.github.io/symdex` answers 404, so a fresh machine could not resolve `sbt-symdex` and okay's build
would not load. The resolver in `project/plugins.sbt` is `https://okay-effects.github.io/symdex` (its
`io.github.sergey-scherbina/sbt-symdex/.../0.5.2/ivys/ivy.xml` answers 200 there); `scripts/symdex-mcp.sh` downloads
releases from `github.com/okay-effects/symdex`, and AGENTS.md links it. The plugin keeps its groupId
`io.github.sergey-scherbina`: its published artifacts are under it.
