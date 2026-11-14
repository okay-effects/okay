package fixture.rw.host;

/** a host giving business code a body: as `Bytes` (data), and as a raw array, to `Wire.raw` and to `Bytes.of` */
public final class Wired {
  public static int give() {
    return fixture.rw.Wire.take(okay.http.Bytes.of(new byte[1])) + fixture.rw.Wire.raw(new byte[2]);
  }
}
