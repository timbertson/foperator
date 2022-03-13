package fs2

import cats.syntax._
import cats.implicits._
import cats.effect.{Concurrent, Resource}
import fs2.concurrent.Topic


// TODO remove
trait BroadcastTopic[F[_], A] {
  def publish: Pipe[F, A, Unit]
  def publish1(a: A): F[Unit]
  def subscribeAwait(maxQueued: Int): Resource[F, Stream[F, A]]
}

object BroadcastTopic {
  def apply[F[_], A](implicit F: Concurrent[F]): F[BroadcastTopic[F, A]] = Topic[F, A].map { t => new BroadcastTopic[F, A] {
    override def publish: Pipe[F, A, Unit] = t.publish

    override def publish1(a: A): F[Unit] = t.publish1(a).void

    override def subscribeAwait(maxQueued: Int): Resource[F, Stream[F, A]] = t.subscribeAwait(maxQueued)
  } }


//  // forked from fs2-for-CE2, hopefully redundant in fs2-for-CE3
//  def apply[F[_], A](implicit F: Concurrent[F]): F[BroadcastTopic[F, A]] = {
//    PubSub
//      .in[F]
//      .from(Strategy.boundedSubscribers[F, Option[A]](Option.empty[A]))
//      .map { (pubSub: PubSub[F, Option[A], SizedQueue[Option[A]], (Token, Int)]) =>
//        new BroadcastTopic[F, A] {
//          def subscribeAwait(size: Int): Resource[F, Stream[F, A]] =
//            Resource.make(
//                Sync[F]
//                  .delay((new Token, size))
//                  .flatTap(_ => pubSub.publish(None)) // clear last item, just for cleanliness
//                  .flatTap(selector => pubSub.subscribe(selector))
//            )(selector => pubSub.unsubscribe(selector))
//              .map { token =>
//                pubSub.getStream(token).flatMap { q =>
//                  Stream.emits(q.toQueue) }.mapFilter(identity)
//              }
//
//          def publish: Pipe[F, A, Unit] =
//            _.evalMap(publish1)
//
//          def publish1(a: A): F[Unit] =
//          pubSub.publish(Some(a))
//        }
//      }
//  }
}
