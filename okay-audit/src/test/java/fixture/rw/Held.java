package fixture.rw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** the receiver decides: a static list only read beside a local list changed is nothing (okay-java's GuardTest
 * `Ambient.clean`); a static list or map changed directly, through a local, in a loop, with a branch in the
 * argument is a finding */
public final class Held {
  static final List<String> NAMES = List.of("a");
  static final List<Integer> L = new ArrayList<>();
  static final Map<String, Integer> M = new HashMap<>();

  public static int clean(int x) {
    List<Integer> l = new ArrayList<>(NAMES.size());
    l.add(x);
    return Math.max(l.size(), 2);
  }
  public static int other(int x) {
    int s = L.size();
    List<Integer> l = new ArrayList<>();
    l.add(x);
    return s + l.size();
  }
  public static void direct(int x) { L.add(x); }
  public static void local(int x) {
    List<Integer> l = L;
    l.add(x);
  }
  public static void loop(int n) {
    List<Integer> l = L;
    for (int i = 0; i < n; i++) l.add(i);
  }
  public static void branch(String k, Integer v) { M.put(k, v == null ? 0 : v); }
}
