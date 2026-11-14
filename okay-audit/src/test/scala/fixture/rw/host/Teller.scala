package fixture.rw.host

import fixture.rw.Fetching
import okay.http.Fetch
import okay.scala3.In

/** a handler giving business code row evidence (a place: no finding), a function and an effect value holding a
  * continuation (findings) */
object Teller:
  def give[R[_]](at: In[Fetch, R]): Int =
    Fetching.get("u", at) + Fetching.mapped(_.length) + Fetching.asked(Fetch.Call("u", _.length))
