- [ ] okay-laminar — okay in Laminar (https://laminar.dev) and in Airstream
      (https://github.com/raquo/Airstream), its reactive layer, and in
      Waypoint (https://github.com/raquo/Waypoint), its router, with the
      vocabulary of scala-dom-types (https://github.com/raquo/scala-dom-types): a page written
      in Laminar uses okay's effects and okay-ui's views, an okay program
      drives a Laminar page, and Airstream's streams and okay's streams cross
      both ways, with no hand-written glue between them.
      ASKED 2026-10-18 by the operator ("сделать okay-laminar для
      https://laminar.dev/"; then "https://github.com/raquo/Airstream тоже
      туда же в этот же проект"; then Waypoint and scala-dom-types, the same
      day).
      WHY LAMINAR: it is THE Scala.js UI library, reactive through
      Airstream (`EventStream`, `Signal`, `Var`, `Observer`, an `Owner` per
      mounted element), and the frontend most Scala teams already have.
      okay-ui reaches the browser today through React (`React.elem`,
      `ReactJs.host`) and the DOM host (`okay.ui.Dom`); a Scala.js team on
      Laminar has no seam to come in by.
      THE SHAPE, five pieces, one module `okay-laminar` (Scala.js only), three
      artifacts: `okay-airstream` (piece 1, depending on Airstream alone, for
      the projects that use Airstream without Laminar), `okay-laminar`
      (pieces 2 and 3, over `okay-airstream`) and `okay-waypoint` (piece 4):
      1. STREAMS BOTH WAYS (okay-airstream). An Airstream `EventStream[A]` / `Signal[A]` as an
         okay `Source[A]`, subscribed when the loop subscribes and released
         when it ends (the bracket shape, like `Terminal.raw` and
         `ui-js-sse-source`); an okay `Source[A]` as an `EventStream[A]`,
         pulled while an `Owner` lives and cancelled on unmount. Also a `Var`
         as an okay `State` effect's handler (reads and writes of the
         program go to the `Var`, so the page sees them as they happen), and
         Airstream's `Observer` as the sink of an okay stream.
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
      4. ROUTES AS AN EFFECT (okay-waypoint). Navigation is an operation a
         program performs (`Navigate(page)`, `Back`), and Waypoint's
         `Router` is its handler: `pushState`/`replaceState`, the URL
         encoded by the router's routes. The current page, Waypoint's
         `currentPageSignal`, is an okay `Source`, so an okay-ui app or an
         okay program reacts to it like any other source. A program that
         navigates is then tested with a handler that records the pages it
         asked for, no browser history, and audited like any other effect.
      5. A TYPED DOM VOCABULARY (scala-dom-types). scala-dom-types is the
         listing of HTML and SVG tags, attributes, properties, CSS and DOM
         events, each with its value type, that Laminar generates its own
         API from. okay-ui's `Ui` names its elements, attributes and events
         by strings today; generated from the same listing, okay-ui gets
         typed tags, attributes and events (`onClick`, `value`, `disabled`
         with their types), checked by the compiler, and the Laminar host of
         piece 3 maps okay-ui's names to Laminar's by the same listing, not
         by a hand-kept table. The generation runs at build time, as
         Laminar's does; it adds no runtime dependency, so it serves every
         okay-ui host (DOM, React, Laminar), not only this module.
      TESTS: okay-airstream's bridges with Airstream alone (no DOM): a
      stream crossed twice is the same stream, a `Signal`'s current value is
      the first element, release on loop end; the effect bridge under Laminar's own test setup
      (jsdom, as Laminar tests itself), mount and unmount checked to
      subscribe and cancel exactly once; the Host against the DOM battery
      okay-ui's other hosts pass; ONE end-to-end page in
      `okay-demo-e2e-browser`.
      NOT HERE: styling and server-side rendering; Waypoint's route syntax
      (url-dsl) is used as it is, not wrapped.
      CHECK FIRST: Laminar's, Airstream's and Waypoint's current versions
      (Airstream is versioned with Laminar), what Waypoint itself depends on
      (Airstream only, or Laminar too: it decides what okay-waypoint sits on);
      how scala-dom-types' generator is driven (its `DomDefsGenerator` and
      code-gen hooks, as Laminar uses them) and how okay-ui's `Ui` keys
      attributes and events now, to keep the existing hosts working and the Scala 3 version they are published for (a library on our Scala 3 reads it, not the other way
      round); how `ReactJs.host` and `Dom.host` take the browser's globals
      as parameters, to do the same; whether `Ui.run`'s `external` merges
      a `Source[Event]` or a `Source[Msg]` (the same question as
      ui-js-sse-source).
      TRIGGER: a Scala.js team on Laminar wanting okay's effects, audit or
      okay-ui views in its pages; or okay-watch's coinpath page if it is
      written in Laminar.
