package fixture.host;

/** the host's subclass of a program's open class: whatever it overrides runs inside the program */
public class Sub extends fixture.biz.Open {
  @Override public String name() { return "host"; }
}
