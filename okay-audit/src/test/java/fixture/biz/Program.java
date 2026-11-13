package fixture.biz;

import java.util.List;
import java.util.function.Supplier;

/** the program's functions the host calls */
public final class Program {
  public static int pay(String to, long amount) { return to.length() + (int) amount; }
  public static int withAmount(Amount a) { return (int) a.cents; }
  public static int withClient(fixture.host.Client c) { return 1; }
  public static int withFn(Supplier<String> s) { return 2; }
  public static int withAny(Object o) { return 3; }
  public static int withList(List<String> xs) { return xs.size(); }
  public static int withOpen(Open o) { return o.name().length(); }
  public static String ask(fixture.host.Client c) { return c.call("q"); }
}
