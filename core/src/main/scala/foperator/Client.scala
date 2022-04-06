package foperator

import cats.effect.Async
import foperator.types.{Engine, ObjectResource}

trait Client[IO[_], C] {
  def apply[T](implicit e: Engine[IO, C, T], res: ObjectResource[T]): Operations[IO, C, T]
}

object Client {
  // inherited by backend companion objects, to pin IO and C
  class Companion[IO[_], C] {
    // type aliases with pinned IO and C
    type OperationsFor[T] = foperator.Operations[IO, C, T]
    type EngineFor[T] = foperator.types.Engine[IO, C, T]
    type ReconcilerFor[T] = foperator.Reconciler[IO, C, T]

    // reconciler builder API
    def Reconciler[T](implicit
      e: EngineFor[T],
      res: ObjectResource[T],
      io: Async[IO],
    ) = new ReconcilerBuilder[IO, C, T]()
  }
}