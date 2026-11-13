package fixture;

import java.util.List;

/** global state: a static field written outside the initializer; a non-final one read; a final one read */
public final class Statics {
  static int counter;
  static final List<String> NAMES = List.of("a");
  static int bump() {
    counter = counter + 1;
    return counter;
  }
  static int names() { return NAMES.size(); }
}
