package okay.scala3

/** okay-scala's row evidence, in the shape of its `core/src/main/scala/okay/scala3/Rows.scala`, cut to what the audit
  * reads: `In` an opaque type over `Place` (so a signature names `okay.scala3.Place<E, R, ?>`), a place a path in the
  * row holding places only — its effect and row arguments phantom */
opaque type In[E[_], R[_]] <: Place[E, R, ?] = Place[E, R, ?]

object In:
  def of[E[_], R[_]](at: Place[E, R, ?]): In[E, R] = at

enum Place[E[_], R[_], Rest[_]]:
  /** the last effect */
  case Last[E[_], R[_]]() extends Place[E, R, R]
  /** one further in */
  case Init[E[_], R[_], Rest[_]](at: Place[E, R, Rest]) extends Place[E, R, Rest]
