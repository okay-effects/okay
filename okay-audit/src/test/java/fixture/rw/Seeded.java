package fixture.rw;

/** seeded generators: deterministic, replay-safe — and calls on them */
public final class Seeded {
  public static int roll() { return new java.util.Random(42L).nextInt(6) + new scala.util.Random(7L).nextInt(6); }
  public static long split() { return new java.util.SplittableRandom(1L).nextLong(); }
}
