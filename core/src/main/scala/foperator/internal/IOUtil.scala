package foperator.internal

import cats.MonadError
import cats.effect.{Async, Fiber, Outcome}
import cats.implicits._

import scala.concurrent.Future

private[foperator] object IOUtil extends Logging {
  def deferFuture[IO[_], T](f: => Future[T])(implicit io: Async[IO]) = io.fromFuture(io.delay(f))

  def withBackground[IO[_], T](bg: IO[Nothing], fg: IO[T])(implicit io: Async[IO]): IO[T] = {
    for {
      _ <- io.delay(logger.debug("spawning background fiber"))
      fiber <- io.start[Nothing](bg)
      f: Fiber[IO, Throwable, Nothing] = fiber
      either <- io.guarantee(io.race[Outcome[IO, Throwable, Nothing], T](f.join, fg), f.cancel)
      result <- either match {
        case Right(fg) => {
          logger.debug("block ended; cancelled background task")
          io.pure(fg)
        }
        case Left(Outcome.Errored(err)) => io.raiseError(err)
        case Left(_: Outcome.Succeeded[IO, Throwable, Nothing]) => io.raiseError(new RuntimeException(s"background task succeeded, impossible"))
        case Left(Outcome.Canceled()) => io.raiseError(new RuntimeException(s"background task canceled"))
      }
    } yield result
  }

  def nonTerminating[IO[_]](t: IO[Unit])(implicit io: MonadError[IO, Throwable]): IO[Nothing] = {
    io.flatMap[Unit, Nothing](t)((_: Unit) => io.raiseError[Nothing](new RuntimeException("nonterminating computation returned prematurely!")))
  }
}
