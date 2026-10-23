## ui-run-ready — `Ui.runReady` / `Ui.runCmdReady`: the loop where nothing may block, for Scala.js

`run` and `runCmd` join the host's events and the world with the buffered
`merge`, which needs `CanBlock` — absent on Scala.js by design — so a page
in the browser could not run the loop and folded by hand (okay-watch's
Coinpath page). The same loop now has a readiness pair: the sources joined
by `mergeReady` (the program's own thread of control, the given `Wait`),
asking only `Scheduler, Wait, Pause`. One private fold under both pairs:
same frames, commands and ending. specs/ui.md "The loop on Scala.js";
TestDom on Scala.js runs it on the DOM host with no `CanBlock`.
