package fixture.rw;

/** `getDeclaredField` NOT in the Scala 3.3 lazy-val idiom: a lazy cell's name used for something else, and another
  * name handed to the same `getOffsetStatic` — both reflection, both findings */
public final class Reflective {
  public static Object peek(Object o) throws Exception {
    java.lang.reflect.Field f = o.getClass().getDeclaredField("x$lzy1");
    return f.getName();
  }
  public static long offset() throws Exception {
    return scala.runtime.LazyVals$.MODULE$.getOffsetStatic(Reflective.class.getDeclaredField("secret"));
  }
}
