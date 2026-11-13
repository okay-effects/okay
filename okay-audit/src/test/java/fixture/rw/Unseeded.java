package fixture.rw;

/** randomness that breaks replay: unseeded, global, secure, thread-local */
public final class Unseeded {
  public static int roll() { return new java.util.Random().nextInt(6); }
  public static int global() { return scala.util.Random$.MODULE$.nextInt(); }
  public static byte[] secure() { byte[] b = new byte[8]; new java.security.SecureRandom().nextBytes(b); return b; }
  public static int local() { return java.util.concurrent.ThreadLocalRandom.current().nextInt(); }
}
