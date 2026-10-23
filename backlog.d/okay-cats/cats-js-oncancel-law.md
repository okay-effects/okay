- [ ] cats-js-oncancel-law — found 2026-10-16 (okay-caps-rule's gate, master
      at the lane's base): TestCatsEffectLaws "Async[A ! CatsFx + Async]:
      async.onCancel associates over uncancelable boundary" fails on JS,
      reproduced alone (`okayCatsJS/testOnly okay.cats.TestCatsEffectLaws`:
      109 run, 1 failed); the same suite green on JVM (110). Not touched by
      that lane (okay-tx tests and docs only). To find: whether the JS
      Async run cancels across an uncancelable region differently.
      LATER THE SAME DAY it fails on the JVM too, alone and every time
      (`okayCatsJVM/testOnly okay.cats.TestCatsEffectLaws`: 110 run, 1
      failed), on master's code with okay-core at f0e680f and at 21a4447 —
      not the kernel pin, not fit-left-linked: something landed in okay
      between the two runs (found by fit-left-linked's gate).
      2026-10-23: IT NOW BLOCKS EVERY PUSH OF SOURCE: ci-runner reproduces it alone on
      master before any lane (08ce74300) and leaves master red, unpushed — ui-run-ready
      (60a6fbfb7) waits behind it; okay-watch pins 8b34c8948 through the branch
      `okay-watch-pin` meanwhile. The cause the spec names is back: Walking.toIO (cf9c3aa2d)
      says itself "the walk has no 'last operation' form: the last operation is followed by
      one flatMap into IO.pure" — the bind after the mask came off that
      specs/cats-effect-instances.md found the law failing on. To do: a last-operation form
      in `Walk` (or a peek that the rest is done), so the last op is exactly its IO.
