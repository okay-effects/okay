package fixture.rw.host;

/** a host giving business code values: the lazy-cell one is data, the var one is not, Bytes twice (a suggestion),
  * a Java Object field (a finding) */
public final class Giver {
  public static int give() {
    return fixture.rw.Sink.take(new fixture.rw.Cached(1)) + fixture.rw.Sink.count(new fixture.rw.Counted()) +
      fixture.rw.Sink.send(new fixture.rw.Bytes(new byte[1])) + fixture.rw.Sink.store(new fixture.rw.Bytes(new byte[2]))
      + fixture.rw.Sink.keep(new fixture.rw.Erased("x"));
  }
  public static int again() { return fixture.rw.Sink.take(new fixture.rw.Cached(2)) + fixture.rw.Sink.count(null); }
}
