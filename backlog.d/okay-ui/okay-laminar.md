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
      MOVED IN PART, 2026-10-18 (the operator): pieces 1, 2 and 4 below —
      Airstream's streams both ways, programs tied to an element's Owner,
      routes as an effect over Waypoint — are okay-simple's now, as modules
      `ui/airstream`, `ui/laminar`, `ui/waypoint` (okay-simple BACKLOG.md,
      "ui: Laminar, Airstream, Waypoint"). What stays HERE is okay-ui's own
      side, which okay-simple does not have:
      3. OKAY-UI AS A LAMINAR ELEMENT. A `Host` over Laminar: `Ui` rendered
         as Laminar elements, `Patch`es applied through Laminar rather than
         a full re-render, the user's events turned into okay-ui `Event`s,
         so one okay-ui application runs inside a Laminar page beside its
         own Laminar components. Built on okay-simple's ui/laminar for the
         lifecycle once that exists.
      5. A TYPED DOM VOCABULARY (scala-dom-types). The listing of HTML and SVG
         tags, attributes, properties, CSS and DOM events with their value
         types, that Laminar generates its API from. okay-ui's `Ui` names
         its elements, attributes and events by strings today; generated
         from the same listing at build time, it gets typed tags, attributes
         and events checked by the compiler, for every okay-ui host (DOM,
         React, Laminar), and piece 3 maps names to Laminar's by it.
      TESTS: the Host against the DOM battery okay-ui's other hosts pass,
      under jsdom; the generated vocabulary compiles the existing okay-ui
      programs unchanged; ONE end-to-end page in `okay-demo-e2e-browser`.
      CHECK FIRST: how scala-dom-types' generator is driven (its
      `DomDefsGenerator`, as Laminar uses it) and how okay-ui's `Ui` keys
      attributes and events now; Laminar 17.2.1 (Scala 3 on 3.3.1, and
      2.13) against okay's Scala 3.
      TRIGGER: a Scala.js team on Laminar wanting okay-ui views in its
      pages.
