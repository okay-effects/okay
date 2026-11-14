## audit-copying-factories - okay-audit: calls to listed copying factories of data types are not findings (0.1.4)

- `Types.factories` (default `Types.Factories`: okay-http's `okay.http.Bytes$#apply`, `Bytes$#body` and their static
  forwarders) and the manifest's `"types": {"factories": [...]}` (adds to the defaults): the host giving a listed
  factory its arguments is not judged. The rule trusts the list that the factory copies; each default cites the test
  that proves it (okay-http `1cc7d09`, `HttpSuite`'s immutability test). The operator's decision after REPORT-3's
  "after, 0.1.3": 5 of okay-serverless' 19 findings were a raw `byte[]` handed to `Bytes$#apply`.
- `TestRealWorld`: a handler's `Bytes(array)`/`Bytes.body(array)`/`Bytes.apply` (Java) no finding; the same array to
  an unlisted business method still a finding; a factory taken off the list a finding again; the test's `Bytes` is
  okay-http's own source now. specs/okay-audit.md, "Copying factories of data types are trusted by name";
  okay-audit/README.md.
