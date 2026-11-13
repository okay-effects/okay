package fixture.rw.host

import fixture.rw.*

/** a host: matches and copies a business value, mixes in a business trait, gives a program, an erased union, and
  * — the real one — a function */
final class Desk extends Outward:
  def read(n: Note): String = Note.unapply(n)._1
  def again(n: Note): Note = n.copy(text = "again")
  def run(): Int = Business.within(Prog.Defer(() => Prog.Done(1)))
  def log(): Int = Business.record(Entry(1))
  def give(): Note = Business.make("x", _ + 1)
