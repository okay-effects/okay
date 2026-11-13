package fixture.rw;

/** a business class whose name starts with a handler class's: not covered by the `fixture.rw.Edge` key */
public final class EdgeX {
  public static void open() throws Exception { new java.net.Socket().close(); }
}
