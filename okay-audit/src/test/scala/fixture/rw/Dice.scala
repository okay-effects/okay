package fixture.rw

/** scala.util.Random seeded (deterministic) beside the global object (not) */
object Dice:
  def seeded(): Int = scala.util.Random(42L).nextInt(6)
  def global(): Int = scala.util.Random.nextInt(6)
