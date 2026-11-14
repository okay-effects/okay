## audit-data-bytes - okay-audit: okay-http's Bytes is data by default (0.1.3)

- `Types.Data` lists `okay.http.Bytes`: the operator's decision after REPORT-2/REPORT-3 found it "not data" (its
  private array) in nine repositories, 40 of okay-serverless' 48 findings. okay-http `1cc7d09` pins that it is
  immutable (copied in by `Bytes(array)`/`Bytes.body`, copied out by `toArray`) and says so in its README/AGENTS.md.
- `TestRealWorld`: a handler giving `Bytes` is no finding; a raw `byte[]` to a business method or to `Bytes.of` still
  is; without the default, `Bytes` is a finding again. specs/okay-audit.md, "okay-http's `Bytes` is data by
  default"; okay-audit/README.md.
