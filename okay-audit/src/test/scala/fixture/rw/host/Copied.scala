package fixture.rw.host

/** a Scala host handing a raw array to `Bytes`' copying factories (module calls `Bytes$#apply`, `Bytes$#body`) and
  * to a business method of its own (`Wire.raw`) */
object Copied:
  def give(a: Array[Byte]): Int =
    fixture.rw.Wire.take(okay.http.Bytes(a)) + okay.http.Bytes.body(a)._2.fold(0)(_.size) + fixture.rw.Wire.raw(a)
