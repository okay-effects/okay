package fixture.rw

/** global mutable state: a Scala object holding a mutable map, changed through a method; a table filled in the
  * object's constructor and only read is not; an instance's held map is said */
object Registry:
  private val seen = scala.collection.mutable.Map.empty[String, Int]
  private val table = scala.collection.mutable.Map("one" -> 1)
  table("two") = 2
  def mark(k: String): Unit = seen(k) = 1
  def read(k: String): Option[Int] = table.get(k)

final class Guard:
  private val inFlight = scala.collection.mutable.Map.empty[String, Int]
  def start(id: String): Unit = inFlight(id) = 1
