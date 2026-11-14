package fixture.rw.host;

/** a host giving business code a body: as `Bytes` (data), through `Bytes.apply` (a copying factory, by its static
  * forwarder), and as a raw array to `Wire.raw` (a business method of its own) */
public final class Wired {
  public static int give() {
    return fixture.rw.Wire.take(okay.http.Bytes.apply(new byte[1])) + fixture.rw.Wire.raw(new byte[2]);
  }
}
