package fixture.host;

import fixture.biz.Amount;
import fixture.biz.Program;
import java.util.List;

/** the host's edge: what it gives the program's methods, data or not */
public final class Host {
  public static int all(Client c) {
    return Program.pay("bob", 10) + Program.withAmount(new Amount(3)) + Program.withClient(c)
        + Program.withFn(() -> c.call("x")) + Program.withAny(c) + Program.withList(List.of("a"))
        + Program.withOpen(new Sub());
  }
}
