package fixture.rw

/** an object whose `run` a rule names: Scala writes a static forwarder `Kernel.run` into the mirror class */
object Kernel:
  def run(x: Int): Int = x + 1

/** the one real caller */
object User:
  def go(): Int = Kernel.run(1)
