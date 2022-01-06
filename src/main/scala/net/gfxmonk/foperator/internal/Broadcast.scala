package net.gfxmonk.foperator.internal

import cats.effect.{Concurrent, ContextShift, Resource}
import cats.implicits._
import fs2.concurrent.Topic
import fs2.{Pipe, Stream}
import monix.catnap.Semaphore

// based on Topic, but with additional sequencing to ensure subscription doesn't
// miss concurrent events, and not requiring an initial value
// This is inefficient, but hopefully in cats 3 we can just use Topic
class Broadcast[IO[_], T]
  (val underlying: Topic[IO, Option[T]], val lock: Semaphore[IO])
  (implicit io: Concurrent[IO])
  extends Logging
{
  def publish1(t: T): IO[Unit] = {
    lock.withPermit(underlying.publish1(Some(t)))
  }

  def publish: Pipe[IO, T, Unit] = {
    stream => stream.evalMap(item => publish1(item))
  }

  def subscribeAwait(maxQueued: Int): Resource[IO, Stream[IO, T]] = {
    // NOTE: cancelling this stream immediately (while it's waiting for the first element)
    // could cause a deadlock. // Don't do that ;)
    val acquire =
      lock.acquire >>
      underlying.publish1(None).as {
        underlying.subscribe(maxQueued).zipWithIndex.evalMapFilter[IO, T] {
          case (elem, idx) => {
            if (idx === 0) {
              // once we've seen the first element, we are definitely subscribed
              // and can allow further publishes
              lock.release.as(elem)
            } else {
              io.pure(elem)
            }
          }
        }
      }

    // TODO this doesn't actually unsubscribe, so behaviour may differ from CE3
    Resource.make(acquire)(_ => io.unit)
  }

  def subscribe(maxQueued: Int): Stream[IO, T] = Stream.resource(subscribeAwait(maxQueued)).flatten
}

object Broadcast {
  def apply[IO[_]: Concurrent: ContextShift, T]: IO[Broadcast[IO, T]] = for {
    topic <- Topic[IO, Option[T]](None)
    lock <- Semaphore[IO](1)
  } yield new Broadcast[IO, T](topic, lock)
}