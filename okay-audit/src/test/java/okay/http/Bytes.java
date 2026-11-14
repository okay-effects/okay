package okay.http;

/** okay-http's `Bytes` in shape (its real class is Scala, in okay-http): a final class over a private array, copied in
  * and out — on the default `types.data` list since okay-audit 0.1.3 */
public final class Bytes {
  private final byte[] a;
  private Bytes(byte[] a) { this.a = a; }
  public static Bytes of(byte[] a) { return new Bytes(a.clone()); }
  public byte[] toArray() { return a.clone(); }
}
