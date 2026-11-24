package okay.std.tests

import okay.*
import okay.std.*
import scala.annotation.nowarn

/** the family's names (okay-effects CONCEPT §3) and the old ones, kept as deprecated aliases for the repositories
 * that pin okay: each pair answers the same */
class TestFamilyNames extends munit.FunSuite:

  enum Counter[+A] derives Effect:
    case Tick extends Counter[Int]

  private val twice: (Int, Int) ! Counter = for a <- perform(Counter.Tick); b <- Counter.Tick.perform yield (a, b)
  private val step: [X] => (Int, Counter[X]) => (Int, X) = [X] => (n: Int, e: Counter[X]) => e match
    case Counter.Tick => (n + 1, n)

  test("the new names: perform, Handler.stateful/tail/general/abort, into, ensuring, Throws.run, Fresh.run, Emit"):
    assertEquals(twice.handle(Handler.stateful[Counter, Int](5)(step)).run, (7, (5, 6)))
    assertEquals(twice.handle(Handler.tail[Counter]([X] => (e: Counter[X]) => e match
      case Counter.Tick => 1)).run, (1, 1))
    val both = Handler.general[Counter, Seq]([A] => (a: A) => Seq(a))(
      [A, X] => (e: Counter[X], k: X => Seq[A] ! Pure) => e match
        case Counter.Tick => k(1).flatMap(x => k(2).map(y => x ++ y)))
    assertEquals(twice.handle(both).run, Seq((1, 1), (1, 2), (2, 1), (2, 2)))
    val stop = Handler.abort[Counter, Option]([A] => (a: A) => Some(a))([A, X] => (_: Counter[X]) => None)
    assertEquals(twice.handle(stop).run, None)
    val wide: (Int, Int) ! Counter + Writer % String = twice.into[Counter + Writer % String]
    val fin = wide.ensuring(Emit.send("end").into[Counter + Emit[String]])
    assertEquals(Emit.collect(fin.handle(Handler.stateful[Counter, Int](0)(step))).run, (Seq("end"), (2, (0, 1))))
    assertEquals(raise[String, Int]("no").handle(Throws.run[String]).run, Left("no"))
    assertEquals((for a <- Fresh.next; b <- Fresh.next yield (a, b)).handle(Fresh.run).run, (0L, 1L))

  test("the old names still compile and answer the same")(oldNames())

  // the one place the deprecated names are used on purpose
  @nowarn("cat=deprecation")
  private def oldNames(): Unit =
    val p: (Int, Int) ! Counter = for a <- effect(Counter.Tick); b <- effect(Counter.Tick) yield (a, b)
    assertEquals(p.handle(Handler.state[Counter, Int](5)(step)).run, (7, (5, 6)))
    assertEquals(p.handle(Handler.answering[Counter]([X] => (e: Counter[X]) => e match
      case Counter.Tick => 1)).run, (1, 1))
    val both = Handler.deep[Counter, Seq]([A] => (a: A) => Seq(a))(
      [A, X] => (e: Counter[X], k: X => Seq[A] ! Pure) => e match
        case Counter.Tick => k(1).flatMap(x => k(2).map(y => x ++ y)))
    assertEquals(p.handle(both).run.size, 4)
    val stop = Handler.aborting[Counter, Option]([A] => (a: A) => Some(a))([A, X] => (_: Counter[X]) => None)
    assertEquals(p.handle(stop).run, None)
    val wide: (Int, Int) ! Counter + Writer % String = p.at[Counter + Writer % String]
    val up: (Int, Int) ! Counter + Writer % String = Row.up(p)[Counter + Writer % String]
    val fin = wide.ensure(Writer.tell("end").into[Counter + Writer % String])
    assertEquals(Writer.run(fin.handle(Handler.state[Counter, Int](0)(step))).run, (Seq("end"), (2, (0, 1))))
    assertEquals(Writer.run(up.handle(Handler.state[Counter, Int](0)(step))).run, (Seq.empty, (2, (0, 1))))
    assertEquals(raise[String, Int]("no").handle(Throws.either[String]).run, Left("no"))
    assertEquals((for a <- Fresh.next; b <- Fresh.next yield (a, b)).handle(Fresh.counter).run, (0L, 1L))
