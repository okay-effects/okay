package fixture;

/** names no Thread itself: it starts a library's subclass of one, and waits on a string's monitor */
public final class Starts {
  public static void go() { new fixture.lib.Spinner().spin(); }
  public static void hold(String s) throws InterruptedException { s.wait(); }
}
