package net.gfxmonk.foperator.internal

import cats.effect.{Concurrent, ContextShift}
import net.gfxmonk.foperator.ReconcilerBuilder
import net.gfxmonk.foperator.types.{Engine, ObjectResource}

// inherited by backend companion objects, to pin IO and C
class BackendCompanion[IO[_], C] {
  def Reconciler[T](implicit
    e: Engine[IO, C, T],
    res: ObjectResource[T],
    io: Concurrent[IO],
    cs: ContextShift[IO],
  ) = new ReconcilerBuilder[IO, C, T]()
}
