package fixture.rw

/** a business case class with a function field: the host matching it or copying it gives nothing */
final case class Note(text: String, f: Int => Int)

/** a value whose field is a union: `Object` in bytecode, with no Signature — erased, unknown */
final case class Entry(ms: Int | String)

/** a program type of a kernel's shape: a deferred program holds a function */
sealed abstract class Prog
object Prog:
  final case class Done(n: Int) extends Prog
  final case class Defer(p: () => Prog) extends Prog

/** a business trait a host's class mixes in */
trait Outward:
  def name: String = "out"

object Business:
  def within(p: Prog): Int = 0
  def record(e: Entry): Int = 0
  def make(text: String, f: Int => Int): Note = Note(text, f)
