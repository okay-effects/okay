package fixture.rw

import okay.http.Fetch
import okay.scala3.In

/** business methods: one taking row evidence (`In[Fetch, R]`, as okay-serverless' `Fetch.apply` does), one a function,
  * one an effect value (a case holding a continuation) */
object Fetching:
  def get[R[_]](url: String, at: In[Fetch, R]): Int = url.length + at.ordinal
  def mapped(f: String => Int): Int = f("x")
  def asked(op: Fetch[Int]): Int = op.ordinal
