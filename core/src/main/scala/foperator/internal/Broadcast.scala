//package foperator.internal
//
//import cats.Eq
//import cats.effect.concurrent.Semaphore
//import cats.effect.{Concurrent, Resource, Sync}
//import cats.implicits._
//import fs2.concurrent.Topic.Strategy
//import fs2.concurrent.{PubSub, Topic}
//import fs2.internal.{SizedQueue, Token}
//import fs2.{Pipe, Pull, Stream}
//
//// based on Topic, but with additional sequencing to ensure subscription doesn't
//// miss concurrent events, and not requiring an initial value
//// This is inefficient, but hopefully in cats 3 we can just use Topic
//
//class Broadcast[IO[_], T]
//  (val underlying: Topic[IO, Option[T]], val lock: Semaphore[IO])
//  (implicit io: Concurrent[IO])
//  extends Logging
//{
//  def publish1(t: T): IO[Unit] = {
//    io.delay(logger.debug("publish1: acquiring semaphore")) >>
//    lock.withPermit(
//      io.delay(logger.debug("publish1: got semaphore")) >>
//      underlying.publish1(Some(t)) >>
//      io.delay(logger.debug("publish1: releasing semaphore"))
//    )
//  }
//
//  def publish: Pipe[IO, T, Unit] = {
//    stream => stream.evalMap(item => publish1(item))
//  }
//
//  def subscribeAwait(maxQueued: Int): Resource[IO, Stream[IO, T]] = {
//    // NOTE: cancelling this stream immediately (while it's waiting for the first element)
//    // could cause a deadlock. // Don't do that ;)
//    val id = scala.util.Random.nextInt(999)
//    val acquire =
//      lock.acquire >>
//      io.delay(logger.debug("subscribeAwait[{}]: acquired semaphore", id)) >>
//      (
//        underlying.publish1(None) >> io.delay(logger.debug("subscribeAwait[{}]: publihed sentinel", id))
//      ) >> {
//        println("RETURNING subscribeAwait")
//        val stream = underlying.subscribe(maxQueued).zipWithIndex.evalMapFilter[IO, T] {
//          case (elem, idx) => {
//            io.delay(logger.debug("subscribeAwait[{}]: saw elem {} at idx {}", id, elem, idx)) >> (
//            if (idx === 0) {
//              // once we've seen the first element, we are definitely subscribed
//              // and can allow further publishes
//              io.delay(logger.debug("subscribeAwait[{}]: releasing semaphore", id)) >>
//              lock.release.as(elem)
//            } else {
//              io.pure(elem)
//            }
//              )
//          }
//          case other => io.delay(logger.debug("wot? {}", other)) >> io.pure(Option.empty[T])
//        }
//
//        // now make a stream which caches the first item.
//        // This all feels very inefficient but it goes away with CE3 :shrug:
////        val s2 = stream.pull.uncons1.flatMap {
////          case None => Pull.raiseError(new RuntimeException("Empty stream; impossible"))
////          case Some((Some(_), _)) => Pull.raiseError(new RuntimeException("None-None sentinel; impossible"))
////          case Some((None, tail)) => {
////            Pull.output1(io.delay(None))
//////            Pull.pure {
//////              // once we've seen the first element, we are definitely subscribed
//////              // and can allow further publishes
//////              io.delay(logger.debug("subscribeAwait[{}]: releasing semaphore", id)) >>
//////                lock.release.as(elem)
//////            }
////          }
////        }.stream
////        val repeatable: Stream[IO, T] = stream.pull.peek.void.stream
////        println("Starting!")
////        io.start(repeatable.take(1).compile.drain).as(repeatable)
//          ???
//      }
//
//    // TODO this doesn't actually unsubscribe, so behaviour may differ from CE3
//    Resource.make(acquire)(_ => io.unit)
//  }
//
//  def subscribe(maxQueued: Int): Stream[IO, T] = Stream.resource(subscribeAwait(maxQueued)).flatten
//}
//
//object Broadcast {
//  def apply[IO[_]: Concurrent, T]: IO[Broadcast[IO, T]] = for {
//    topic <- Topic[IO, Option[T]](None)
//    lock <- Semaphore[IO](1)
//  } yield new Broadcast[IO, T](topic, lock)
//}