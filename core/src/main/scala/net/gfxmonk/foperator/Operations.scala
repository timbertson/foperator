package net.gfxmonk.foperator

import cats.Eq
import cats.effect.{Concurrent, ContextShift, Timer}
import cats.implicits._
import net.gfxmonk.foperator.internal.{Dispatcher, Logging}
import net.gfxmonk.foperator.types.{ObjectResource, _}

class Operations[IO[_], C, T](c: C)
  (implicit io: Concurrent[IO], cs: ContextShift[IO], e: Engine[IO, C, T], res: ObjectResource[T])
  extends Logging
{
  def updateStatus[St](original: T, st: St)
    (implicit sub: HasStatus[T, St]): IO[T] = {
    implicit val eq = sub.eqStatus
    if (sub.status(original).exists(_ === st)) {
      io.pure(original)
    } else {
      logger.debug(s"Updating status of ${res.id(original)}")
      e.writeStatus(c, original, st)
    }
  }

  def write(t: T): IO[T] = {
    logger.debug(s"Writing ${res.id(t)}")
    e.write(c, t)
  }

  def update(t: T)(block: T => T)(implicit eq: Eq[T]): IO[T] = {
    val updated = block(t)
    if (t === updated) {
      io.pure(t)
    } else {
      io.delay(logger.debug(s"Writing ${res.id(t)}")) >>
      e.write(c, t)
    }
  }

  def delete(id: Id[T]): IO[Unit] = {
    io.delay(logger.debug(s"Deleting $id")) >>
    e.delete(c, id)
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
    e.read(c, id)
  }

  def forceWrite(t: T): IO[T] = {
    val id = res.id(t)
    e.read(c, id).flatMap {
      case None => {
        logger.debug("[{}] forceWrite: no current version found", id)
        e.write(c, t)
      }
      case Some(current) => {
        // resource exists, update based on the current resource version
        val overwrite = res.withVersion(t, res.version(current))
        logger.debug("[{}] forceWrite: overwriting current version {}", id, res.version(overwrite))
        e.write(c, overwrite)
      }
    }.map { result =>
      logger.info(s"Force-wrote ${id} v${res.version(result)}")
      result
    }
  }

  def mirror[R](block: ResourceMirror[IO, T] => IO[R]): IO[R] = ResourceMirror[IO, C, T, R](c, ListOptions.all)(block)

  def mirrorFor[R](opts: ListOptions)(block: ResourceMirror[IO, T] => IO[R]): IO[R] = ResourceMirror[IO, C, T, R](c, opts)(block)

  def runReconciler(
    reconciler: Reconciler[IO, C, T],
    opts: ReconcileOptions = ReconcileOptions()
  )(implicit timer: Timer[IO], cs: ContextShift[IO]): IO[Unit] = {
    mirror[Unit] { mirror =>
      Dispatcher.run[IO, C, T](c, mirror, reconciler.reconcile, opts)
    }
  }

  def runReconcilerWithInput(
    input: ReconcileSource[IO, T],
    reconciler: Reconciler[IO, C, T],
    opts: ReconcileOptions = ReconcileOptions()
  )(implicit timer: Timer[IO], cs: ContextShift[IO]): IO[Unit] =
    Dispatcher.run[IO, C, T](c, input, reconciler.reconcile, opts)
}

object Operations {
  def apply[IO[_]: Concurrent: ContextShift, C](client: C) = new Builder[IO, C](client)

  class Builder[IO[_]: Concurrent: ContextShift, C](client: C) {
    def apply[T](implicit e: Engine[IO, C, T], res: ObjectResource[T]): Operations[IO, C, T] = new Operations[IO, C, T](client)
  }
}