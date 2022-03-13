package foperator.internal

import cats.implicits._
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}

// Reduced API version of cats-effect v2.x MVar, which was removed in 3.x
trait IORef[IO[_], T] {
  def modify[R](f: T => IO[(T, R)]): IO[R]
  def modify_(f: T => IO[T]): IO[Unit]
  def readLast: IO[T]
}

object IORef {
  def apply[IO[_]](implicit io: Concurrent[IO]) = new IORef.Builder[IO]()

  class Builder[IO[_]]()(implicit io: Concurrent[IO]){
    def of[T](initial: T): IO[IORef[IO, T]] = for {
      ref <- Ref[IO].of[T](initial)
      sem <- Semaphore[IO](1)
    } yield (new IORef[IO, T] {
      override def modify[R](f: T => IO[(T, R)]): IO[R] = sem.withPermit {
        for {
          v <- ref.get
          pair <- f(v)
          _ <- io.uncancelable(ref.set(pair._1))
        } yield pair._2
      }

      override def modify_(f: T => IO[T]): IO[Unit] = modify(t => f(t).map(t => (t, ())))

      override def readLast: IO[T] = ref.get
    })
  }
}