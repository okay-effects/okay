package fixture.rw;

/** an immutable wrapper over an array: final, final fields — a `types.data` candidate the report suggests */
public final class Bytes {
  public final byte[] a;
  public Bytes(byte[] a) { this.a = a.clone(); }
}
