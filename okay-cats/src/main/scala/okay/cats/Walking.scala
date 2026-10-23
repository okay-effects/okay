package okay.cats

import okay.{!, +, Async, Walk}
import _root_.cats.effect.IO

/**
 * A PROGRAM OF `E` AND `Async` AS ONE IO, walked (okay's `Walk`, okay-freer's `resume` on the kernel): `E` walked
 * among the program's effects, the `Async` operations met on the way walked outside it — each operation its IO,
 * the next step inside IO's own `flatMap`, so the walk is as stack-safe as IO's loop.
 *
 * The walk has no "last operation" form: the last operation is followed by one `flatMap` into `IO.pure`, where
 * okay-freer's walk ended on the operation itself — so a program that IS one operation is not walked (`single`).
 */
private[cats] object Walking:
  def toIO[E[_], A](p: A ! E + Async)(step: [X] => E[X] => IO[X])(using k: okay.KeyOf[E]): IO[A] =
    single(p, step).getOrElse(outer(Walk.view(Walk.next[E, A, E + Async](p)), step))

  /**
   * A PROGRAM THAT IS ONE OPERATION, as exactly its IO (cats-js-oncancel-law): `effect(op)` is the kernel's `Perform`
   * with no rest, and a walk would follow it with one `flatMap` into `IO.pure` — a cancellation point after the
   * operation, outside its mask. cats-effect's "onCancel associates over uncancelable boundary" fails on exactly
   * that (specs/cats-effect-instances.md): `onCancel(uncancelable(fa), fin)` saw a cancel between the mask and its
   * `pure` that `uncancelable(onCancel(fa, fin))` cannot. The instance's every combinator is one such operation.
   */
  private def single[E[_], A](p: A ! E + Async, step: [X] => E[X] => IO[X])(using k: okay.KeyOf[E]): Option[IO[A]] =
    okay.Run.kernel[A, E + Async, okay.one.row.Pure](p) match
      case pf: okay.one.Cont.Perform[?, ?, ?, ?] =>
        // THE CASTS, and why they are sound: a `Perform` with no rest answers its operation's value, `A`; its place
        // says whose operation it is — `E`'s by `E`'s key, else the row's other effect, `Async`
        if Walk.own(pf.in, k.ref) then Some(step(pf.op.asInstanceOf[E[A]]))
        else if Walk.own(pf.in, summon[okay.KeyOf[Async]].ref) then Some(CatsEffect.asyncIO(pf.op.asInstanceOf[Async[A]]))
        else None
      case _ => None

  private def outer[E[_], A](s: Walk.Step[Async, Walk.Step[E, A, E + Async], Async], step: [X] => E[X] => IO[X])
      (using okay.KeyOf[E]): IO[A] =
    s match
      case Walk.Done(st) => st match
        case Walk.Done(a) => IO.pure(a)
        case o: Walk.Op[E, x, A, E + Async] => step(o.op).flatMap(x => toIO(o.resume(x))(step))
      case o: Walk.Op[Async, x, Walk.Step[E, A, E + Async], Async] =>
        CatsEffect.asyncIO(o.op).flatMap(x => outer(Walk.view(o.resume(x)), step))
