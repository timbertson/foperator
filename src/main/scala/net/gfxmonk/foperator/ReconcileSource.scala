package net.gfxmonk.foperator

import cats.implicits._
import cats.effect.Concurrent
import fs2.Stream

trait ReconcileSourceBase[IO[_], T] {
  def get(id: Id[T]): IO[Option[ResourceState[T]]]
  def ids: Stream[IO, Id[T]]
}

// convenience user API builders on top of Base
trait ReconcileSource[IO[_], T] extends ReconcileSourceBase[IO, T] {
  def withExternalTrigger(triggers: Stream[IO, Id[T]])
    (implicit io: Concurrent[IO]): ReconcileSource[IO, T] = {
    new ReconcileSource.Derived[IO, T](get, ids.merge(triggers))
  }

  def withActiveResourceTrigger[S](mirror: ResourceMirror[IO, S])
    (extractIds: S => IO[List[Id[T]]])
    (implicit io: Concurrent[IO]): ReconcileSource[IO, T] = {
    withResourceTrigger(mirror) {
      case ResourceState.Active(value) => extractIds(value)
      case ResourceState.SoftDeleted(_) => io.pure(Nil)
    }
  }

  def withResourceTrigger[R](mirror: ResourceMirror[IO, R])
    (extractIds: ResourceState[R] => IO[List[Id[T]]])
    (implicit io: Concurrent[IO]): ReconcileSource[IO, T] = {
    withIdTrigger[R](mirror) { id =>
      mirror.get(id).flatMap {
        case Some(resource) => extractIds(resource)
        case None => io.pure(Nil)
      }
    }
  }

  def withIdTrigger[R](watcher: ReconcileSource[IO, R])(extractIds: Id[R] => IO[List[Id[T]]])
    (implicit concurrent: Concurrent[IO])
    : ReconcileSource[IO, T] = {
    withExternalTrigger(watcher.ids.flatMap(id => Stream.eval(extractIds(id)).flatMap(Stream.iterable)))
  }
}

object ReconcileSource {
  private [foperator] class Derived[IO[_], T](getter: Id[T] => IO[Option[ResourceState[T]]], val ids: Stream[IO, Id[T]]) extends ReconcileSource[IO, T] {
    override def get(id: Id[T]): IO[Option[ResourceState[T]]] = getter(id)
  }
}