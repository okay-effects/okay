## cats-js-oncancel-law — a one-operation program is exactly its IO: the cats-effect law green again

TestCatsEffectLaws "Async[A ! CatsFx + Async]: async.onCancel associates over uncancelable boundary"
failed on the JVM and JS every run from 2026-10-16, and since it reproduced on master before any lane, the
ci-runner left master red and pushed no source. The cause was the one specs/cats-effect-instances.md had
named: the kernel walk (`Walk`, cf9c3aa2d) follows the last operation with a `flatMap` into `IO.pure`, a
cancellation point outside the operation's mask. `Walking.toIO` now answers a program that is one
operation — the kernel's `Perform` with no rest, which every combinator of the instance builds — as exactly
that operation's IO, unwalked. The laws: JVM 110 and JS 109, green, run twice.
