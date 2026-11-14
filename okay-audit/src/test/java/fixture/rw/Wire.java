package fixture.rw;

/** business methods taking a body: okay-http's `Bytes` (data by default) and a raw array (never data) */
public final class Wire {
  public static int take(okay.http.Bytes b) { return b.toArray().length; }
  public static int raw(byte[] b) { return b.length; }
}
