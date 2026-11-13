package fixture.rw;

/** a handler file in a business package: the class, its nested and its anonymous classes reach the network */
public final class Edge {
  public static int port() throws Exception { return new Client().port(); }
  public static Runnable later() { return new Runnable() { public void run() { new java.net.Socket(); } }; }
  public static final class Client {
    int port() throws Exception { try (java.net.Socket s = new java.net.Socket("localhost", 80)) { return 1; } }
  }
}
