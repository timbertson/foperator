package foperator

import cats.Eq
import cats.effect.{Async, Concurrent, Sync}
import cats.implicits._
import foperator.internal.{Dispatcher, Logging}
import foperator.types._

class Operations[IO[_], C, T](val client: C)
  (implicit sync: Sync[IO], e: Engine[IO, C, T], res: ObjectResource[T])
  extends Logging
{
  def updateStatus[St](original: T, st: St)
    (implicit sub: HasStatus[T, St]): IO[Unit] = {
    implicit val eq = sub.eqStatus
    if (sub.status(original).exists(_ === st)) {
      sync.unit
    } else {
      sync.delay(logger.debug(s"Updating status of ${res.id(original)}")) >>
      e.updateStatus(client, original, st)
    }.adaptError(new RuntimeException(s"Error updating status of ${res.id(original)}", _))
  }

  def write(t: T): IO[Unit] = {
    res.version(t) match {
      case None =>
        sync.delay(logger.debug(s"Creating ${res.id(t)}")) >>
        e.create(client, t)
      case Some(_) =>
        sync.delay(logger.debug(s"Updating ${res.id(t)}")) >>
        e.update(client, t)
    }
  }.adaptError(new RuntimeException(s"Error writing ${res.id(t)}", _))

  def update(t: T)(block: T => T)(implicit eq: Eq[T]): IO[Unit] = {
    val updated = block(t)
    if (t === updated) {
      sync.unit
    } else {
      write(updated)
    }
  }

  def delete(id: Id[T]): IO[Unit] = {
    _delete(id).adaptError(new RuntimeException(s"Error deleting ${id}", _))
  }

  def _delete(id: Id[T]): IO[Unit] = {
    sync.delay(logger.debug(s"Deleting $id")) >>
    e.delete(client, id)
  }


  def deleteIfPresent(id: Id[T]): IO[Boolean] = {
    sync.handleErrorWith(_delete(id).as(true)) { err =>
      e.classifyError(err) match {
        case ClientError.NotFound(_) => sync.pure(false)
        case other => sync.raiseError(other.throwable)
      }
    }
  }

  def get(id: Id[T]): IO[Option[T]] = {
    sync.delay(logger.debug(s"Getting ${id}")) >>
    e.read(client, id).adaptError(new RuntimeException(s"Error getting ${id}", _))
  }

  def forceWrite(t: T): IO[Unit] = {
    val id = res.id(t)
    e.read(client, id).flatMap {
      case None => {
        logger.debug("[{}] forceWrite: no current version found", id)
        write(t)
      }
      case Some(current) => {
        // resource exists, update based on the current resource version
        val overwrite = res.version(current).map(v => res.withVersion(t, v)).getOrElse(t)
        logger.debug("[{}] forceWrite: overwriting current version {}", id, res.version(overwrite))
        write(overwrite)
      }
    }
  }

  def mirror[R](block: ResourceMirror[IO, T] => IO[R])(implicit c: Async[IO]): IO[R] =
    ResourceMirror[IO, C, T, R](client, ListOptions.all)(block)

  def mirrorFor[R](opts: ListOptions)(block: ResourceMirror[IO, T] => IO[R])(implicit c: Async[IO]): IO[R] =
    ResourceMirror[IO, C, T, R](client, opts)(block)

  def runReconciler(
    reconciler: Reconciler[IO, C, T],
    opts: ReconcileOptions = ReconcileOptions()
  )(implicit c: Async[IO]): IO[Unit] = {
    mirror[Unit] { mirror =>
      Dispatcher.run[IO, C, T](client, mirror, reconciler.reconcile, opts)
    }
  }

  def runReconcilerWithInput(
    input: ReconcileSource[IO, T],
    reconciler: Reconciler[IO, C, T],
    opts: ReconcileOptions = ReconcileOptions()
  )(implicit c: Async[IO]): IO[Unit] =
    Dispatcher.run[IO, C, T](client, input, reconciler.reconcile, opts)
}