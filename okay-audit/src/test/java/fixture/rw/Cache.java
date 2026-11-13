package fixture.rw;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** global mutable state held in static final fields and changed through methods; a table filled once is not */
public final class Cache {
  static final Map<String, Integer> SEEN = new HashMap<>();
  static final AtomicReference<String> LAST = new AtomicReference<>("");
  static final Map<String, Integer> TABLE = new HashMap<>();
  static { TABLE.put("one", 1); }
  public static void mark(String k) { SEEN.put(k, 1); }
  public static void last(String k) { LAST.set(k); }
  public static Integer read(String k) { return TABLE.get(k); }
}
