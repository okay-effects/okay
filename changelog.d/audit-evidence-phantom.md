## audit-evidence-phantom - okay-audit: row evidence is data, its type arguments phantom (0.1.4, republished)

- `Types.evidence` (default `Types.Evidence`: okay-scala's `okay.scala3.Place` — what `In`, `Split`, `Has` erase to —,
  `okay.scala3.At`, `okay.scala3.Sub`; okay-safe's `okay.safe.In`, `Split`, `Place`, `Sub`; okay.rows has none) and the
  manifest's `"types": {"evidence": [...]}` (adds to the defaults): a value of a listed class, or of one nested in it,
  is data and its type arguments are not judged. The false positive of REPORT-3's finding 5: okay-serverless'
  Telegram handler giving `Fetch$#apply` its `In[Fetch, R]` was reported as "a function" by `Fetch.Call`'s
  continuation, a phantom type argument of the place.
- `TestRealWorld`: the place no finding; a lambda and a `Fetch.Call` value still findings; `Place` off the list a
  finding again. specs/okay-audit.md, "Row evidence's type arguments are phantom"; okay-audit/README.md.
