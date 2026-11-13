package fixture.rw;

/** a value with a Scala 3.3 lazy cell (`v$lzy1`, volatile, not final): the compiler's, not a var */
public final class Cached {
  public final int a;
  private volatile Object v$lzy1;
  public Cached(int a) { this.a = a; }
}
