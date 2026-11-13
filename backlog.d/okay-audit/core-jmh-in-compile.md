- [ ] core-jmh-in-compile — found by audit-consolidate's dogfood (2026-11-13):
      `okay.jvm`'s Compile external classpath carries jmh-core, the JMH
      generators, asm 9.0, commons-math3 and jopt-simple — `JmhPlugin` puts
      them there — so every project depending on the core inherits them at
      compile scope (and a published POM would name them). okay-audit now
      lists them under the runtime's row instead of failing each business
      project, which is honest about where they come from but is not the fix:
      move them to the `Jmh` configuration (sbt-jmh's settings) and check the
      released POMs (project/ReleaseWave.scala's third-party listing).
