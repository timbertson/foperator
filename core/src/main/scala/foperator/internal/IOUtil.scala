package foperator.internal

import cats.MonadError
import cats.effect.{Concurrent, Fiber}
import cats.implicits._

private[foperator] object IOUtil extends Logging {
  def withBackground[IO[_], T](bg: IO[Nothing], fg: IO[T])(implicit io: Concurrent[IO]): IO[T] = {
    for {
      _ <- io.delay(logger.debug("spawning background fiber"))
      fiber <- io.start[Nothing](bg)
      f: Fiber[IO, Nothing] = fiber
      either <- io.guarantee(io.race[Nothing, T](f.join, fg))(fiber.cancel)
      result <- either match {
        case Right(r) => {
          logger.debug("block ended; cancelled background task")
          io.pure(r)
        }
        case _: Left[T, _] => io.raiseError(new RuntimeException("consumption terminated prematurely"))
      }
    } yield result
  }

  def nonTerminating[IO[_]](t: IO[Unit])(implicit io: MonadError[IO, Throwable]): IO[Nothing] = {
    io.flatMap[Unit, Nothing](t)((_: Unit) => io.raiseError[Nothing](new RuntimeException("nonterminating computation returned prematurely!")))
  }
}
