package fixture.rw;

/** business methods a host calls with values */
public final class Sink {
  public static int take(Cached c) { return c.a; }
  public static int count(Counted c) { return c.n; }
  public static int send(Bytes b) { return b.a.length; }
  public static int store(Bytes b) { return b.a.length; }
  public static int keep(Erased e) { return 0; }
}
