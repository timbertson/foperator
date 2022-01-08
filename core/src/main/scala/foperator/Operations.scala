package foperator

import cats.Eq
import cats.effect.{Concurrent, ContextShift, Timer}
import cats.implicits._
import foperator.internal.{Dispatcher, Logging}
import foperator.types.{ObjectResource, _}

class Operations[IO[_], C, T](val client: C)
  (implicit io: Concurrent[IO], cs: ContextShift[IO], e: Engine[IO, C, T], res: ObjectResource[T])
  extends Logging
{
  def updateStatus[St](original: T, st: St)
    (implicit sub: HasStatus[T, St]): IO[Unit] = {
    implicit val eq = sub.eqStatus
    if (sub.status(original).exists(_ === st)) {
      io.pure(original)
    } else {
      logger.debug(s"Updating status of ${res.id(original)}")
      e.writeStatus(client, original, st)
    }
  }

  def write(t: T): IO[Unit] = {
    logger.debug(s"Writing ${res.id(t)}")
    e.write(client, t)
  }

  def update(t: T)(block: T => T)(implicit eq: Eq[T]): IO[Unit] = {
    val updated = block(t)
    if (t === updated) {
      io.pure(t)
    } else {
      io.delay(logger.debug(s"Writing ${res.id(t)}")) >>
      e.write(client, t)
    }
  }

  def delete(id: Id[T]): IO[Unit] = {
    io.delay(logger.debug(s"Deleting $id")) >>
    e.delete(client, id)
  }

  def deleteIfPresent(id: Id[T]): IO[Boolean] = {
    io.handleErrorWith(delete(id).as(true)) { err =>
      e.classifyError(err) match {
        case ClientError.NotFound(_) => io.pure(false)
        case other => io.raiseError(other.throwable)
      }
    }
  }

  def get(id: Id[T]): IO[Option[T]] = {
    e.read(client, id)
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
        val overwrite = res.withVersion(t, res.version(current))
        val newVersion = res.version(overwrite)
        logger.debug("[{}] forceWrite: overwriting current version {}", id, newVersion)
        write(overwrite)
      }
    }
  }

  def mirror[R](block: ResourceMirror[IO, T] => IO[R]): IO[R] = ResourceMirror[IO, C, T, R](client, ListOptions.all)(block)

  def mirrorFor[R](opts: ListOptions)(block: ResourceMirror[IO, T] => IO[R]): IO[R] = ResourceMirror[IO, C, T, R](client, opts)(block)

  def runReconciler(
    reconciler: Reconciler[IO, C, T],
    opts: ReconcileOptions = ReconcileOptions()
  )(implicit timer: Timer[IO], cs: ContextShift[IO]): IO[Unit] = {
    mirror[Unit] { mirror =>
      Dispatcher.run[IO, C, T](client, mirror, reconciler.reconcile, opts)
    }
  }

  def runReconcilerWithInput(
    input: ReconcileSource[IO, T],
    reconciler: Reconciler[IO, C, T],
    opts: ReconcileOptions = ReconcileOptions()
  )(implicit timer: Timer[IO], cs: ContextShift[IO]): IO[Unit] =
    Dispatcher.run[IO, C, T](client, input, reconciler.reconcile, opts)
}