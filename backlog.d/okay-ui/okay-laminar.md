- [ ] okay-laminar — okay in Laminar (https://laminar.dev): a page written
      in Laminar uses okay's effects and okay-ui's views, and an okay
      program drives a Laminar page, with no hand-written glue between them.
      ASKED 2026-10-18 by the operator ("сделать okay-laminar для
      https://laminar.dev/").
      WHY LAMINAR: it is THE Scala.js UI library, reactive through
      Airstream (`EventStream`, `Signal`, `Var`, `Observer`, an `Owner` per
      mounted element), and the frontend most Scala teams already have.
      okay-ui reaches the browser today through React (`React.elem`,
      `ReactJs.host`) and the DOM host (`okay.ui.Dom`); a Scala.js team on
      Laminar has no seam to come in by.
      THE SHAPE, three pieces, one module `okay-laminar` (Scala.js only):
      1. STREAMS BOTH WAYS. An Airstream `EventStream[A]` / `Signal[A]` as an
         okay `Source[A]`, subscribed when the loop subscribes and released
         when it ends (the bracket shape, like `Terminal.raw` and
         `ui-js-sse-source`); an okay `Source[A]` as an `EventStream[A]`,
         pulled while an `Owner` lives and cancelled on unmount.
      2. EFFECTS IN A PAGE. An okay program run from a Laminar element:
         its result into a `Var`/`EventStream`, its run tied to the
         element's `Owner`, so unmounting CANCELS it (okay-async's
         cancellation, which interrupts what it waits on). Errors as
         values, not exceptions in the console.
      3. OKAY-UI AS A LAMINAR ELEMENT. A `Host` over Laminar: `Ui` rendered
         as Laminar elements, `Patch`es applied through Laminar rather than
         a full re-render, the user's events turned into okay-ui `Event`s,
         so one okay-ui application runs inside a Laminar page beside its
         own Laminar components.
      TESTS: the stream and effect bridges under Laminar's own test setup
      (jsdom, as Laminar tests itself), mount and unmount checked to
      subscribe and cancel exactly once; the Host against the DOM battery
      okay-ui's other hosts pass; ONE end-to-end page in
      `okay-demo-e2e-browser`.
      NOT HERE: routing, styling, server-side rendering. Laminar's own
      `Waypoint` and CSS stay Laminar's.
      CHECK FIRST: Laminar's current version and the Scala 3 version it is
      published for (a library on our Scala 3 reads it, not the other way
      round); how `ReactJs.host` and `Dom.host` take the browser's globals
      as parameters, to do the same; whether `Ui.run`'s `external` merges
      a `Source[Event]` or a `Source[Msg]` (the same question as
      ui-js-sse-source).
      TRIGGER: a Scala.js team on Laminar wanting okay's effects, audit or
      okay-ui views in its pages; or okay-watch's coinpath page if it is
      written in Laminar.
