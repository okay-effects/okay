package fixture.rw

/** okay-mall's `Drawn.kept`: a Scala object's map updated inside `synchronized`, in a `getOrElse` closure; a buffer
  * appended to with `+=`; a local buffer changed beside the object's only read is nothing */
final case class Drawn(id: String, n: Int)

object Drawn:
  private val kept = scala.collection.mutable.Map.empty[String, Drawn]
  private val names = scala.collection.mutable.ListBuffer.empty[String]
  def of(id: String, n: Int): Drawn = synchronized {
    kept.get(id).filter(_.n == n).getOrElse {
      val d = Drawn(id, n)
      kept(id) = d
      d
    }
  }
  def note(s: String): Unit = names += s
  def local(s: String): Int =
    val n = names.size
    val mine = scala.collection.mutable.ListBuffer.empty[String]
    mine += s
    n + mine.size
