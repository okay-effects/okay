package okay.http

import java.nio.charset.StandardCharsets.UTF_8

/** okay-http's `Bytes` as it is (okay-http `1cc7d09`, core/src/main/scala/okay/http/Bytes.scala): a final class over
  * a private array, copied in by `apply`/`body` and out by `toArray` — on the default `types.data` since okay-audit
  * 0.1.3, its `apply`/`body` on the default `types.factories` since 0.1.4 */
final class Bytes private (private val a: Array[Byte]) {
  def size: Int = a.length
  def isEmpty: Boolean = a.isEmpty
  def toArray: Array[Byte] = a.clone()
  def text: String = new String(a, UTF_8)
  def slice(from: Int, until: Int): Bytes = new Bytes(a.slice(from, until))
  override def equals(o: Any): Boolean = o match {
    case b: Bytes => java.util.Arrays.equals(a, b.a)
    case _ => false
  }
  override def hashCode: Int = java.util.Arrays.hashCode(a)
  override def toString: String = s"Bytes(${a.length})"
}

object Bytes {
  val empty: Bytes = new Bytes(Array.emptyByteArray)
  def apply(a: Array[Byte]): Bytes = new Bytes(a.clone())
  def of(text: String): Bytes = new Bytes(text.getBytes(UTF_8))

  def body(raw: Array[Byte]): (String, Option[Bytes]) = {
    val text = new String(raw, UTF_8)
    if (java.util.Arrays.equals(text.getBytes(UTF_8), raw)) (text, None) else ("", Some(new Bytes(raw.clone())))
  }
}
