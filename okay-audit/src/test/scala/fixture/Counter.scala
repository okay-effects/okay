package fixture

/** a Scala object's var: global state, written outside the object's constructor */
object Counter:
  var n: Int = 0
  def bump(): Int =
    n += 1
    n
