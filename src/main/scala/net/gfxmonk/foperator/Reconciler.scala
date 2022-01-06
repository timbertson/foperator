package net.gfxmonk.foperator

import cats.Eq
import cats.effect.{Concurrent, ContextShift}
import cats.implicits._
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.types._

import scala.concurrent.duration.FiniteDuration

sealed trait ReconcileResult
object ReconcileResult {
  case class RetryAfter(delay: FiniteDuration) extends ReconcileResult
  case object Ok extends ReconcileResult
  def merge(a: ReconcileResult, b: ReconcileResult): ReconcileResult = (a, b) match {
    case (ReconcileResult.Ok, b) => b
    case (a, ReconcileResult.Ok) => a
    case (ReconcileResult.RetryAfter(a), ReconcileResult.RetryAfter(b)) => ReconcileResult.RetryAfter(a.max(b))
  }
}

trait Reconciler[IO[_], C, T] {
  def reconcile(client: C, resource: ResourceState[T]): IO[ReconcileResult]

  def withFinalizer(id: String, action: T => IO[Unit]): Reconciler[IO, C, T]

  def withFinalizerFull(id: String, action: (C, T) => IO[Unit]): Reconciler[IO, C, T]
}

private class ReconcilerImpl[IO[_], C, T](
  reconcileFn: (C, T) => IO[ReconcileResult],
  finalizers: List[(String, (C, T) => IO[Unit])],
  )(implicit io: Concurrent[IO], e: Engine[IO, C, T], res: ObjectResource[T])
  extends Reconciler[IO, C, T] with Logging
{
  private type Finalizer = (String, (C, T) => IO[Unit])

  def withFinalizer(id: String, action: T => IO[Unit]): ReconcilerImpl[IO, C, T] = withFinalizerFull(id, (_, t) => action(t))

  def withFinalizerFull(id: String, action: (C, T) => IO[Unit]): ReconcilerImpl[IO, C, T] = new ReconcilerImpl(reconcileFn, (id, action) :: finalizers)

  private def finalizerNames = finalizers.map(_._1)

  override def reconcile(client: C, resource: ResourceState[T]): IO[ReconcileResult] = {
    resource match {
      case ResourceState.Active(resource) => {
        ResourceState.addFinalizers(resource, finalizerNames) match {
          case None => {
            // already installed, regular reconcile
            reconcileFn(client, resource)
          }
          case Some(updated) => {
            // install finalizer before doing any user actions.
            // Note that we don't invoke the user reconciler in this case,
            // we rely on a re-reconcile being triggered by the metadata addition
            logger.info("[{}] Adding finalizer {} to {}", res.kind, finalizerNames.mkString(", "), res.id(resource))
            e.write(client, updated).as(ReconcileResult.Ok)
          }
        }
      }

      case ResourceState.SoftDeleted(resource) => {
        def loop: List[Finalizer] => IO[Unit] = {
          case Nil => io.pure(ReconcileResult.Ok)
          case (id, fn) :: tail => {
            ResourceState.removeFinalizer(resource, id) match {
              case Some(updated) => {
                logger.info("[{}] Finalizing {} ({})", res.kind, res.id(resource), id)
                // we could probably run multiple finalizers before updating,
                // but there's almost never more than one :shrug:
                fn(client, resource).flatMap { (_: Unit) =>
                  e.write(client, updated).as(ReconcileResult.Ok)
                }
              }
              case None => loop(tail)
            }
          }
        }
        loop(finalizers).as(ReconcileResult.Ok)
      }
    }
  }
}

class ReconcilerBuilder[IO[_], C, T](implicit
  e: Engine[IO, C, T],
  res: ObjectResource[T],
  io: Concurrent[IO],
  cs: ContextShift[IO],
) extends Logging {
  def empty: Reconciler[IO, C, T] = new ReconcilerImpl[IO, C, T]((_, _) => io.pure(ReconcileResult.Ok), Nil)

  def run(fn: T => IO[Unit]): Reconciler[IO, C, T] = new ReconcilerImpl((_, res) => fn(res).as(ReconcileResult.Ok), Nil)

  def full(fn: (C, T) => IO[ReconcileResult]): Reconciler[IO, C, T] = {
    new ReconcilerImpl[IO, C, T]((c, res) => fn(c, res).as(ReconcileResult.Ok), Nil)
  }

  def write(fn: T => IO[T])(implicit eq: Eq[T]): Reconciler[IO, C, T] =
    full((client, resource) => {
      fn(resource).flatMap { newResource =>
        if (newResource =!= resource) {
          e.write(client, newResource).as(ReconcileResult.Ok)
        } else {
          io.pure(ReconcileResult.Ok)
        }
      }
    })

  def status[St](fn: T => IO[St])(implicit st: HasStatus[T, St]): Reconciler[IO, C, T] = {
    full(
      (client: C, resource: T) => {
        fn(resource).flatMap { newStatus =>
          new Operations[IO, C, T](client).updateStatus(resource, newStatus).as(ReconcileResult.Ok)
        }
      }
    )
  }
}

object Reconciler {
  private [foperator] type Fn[IO[_], -C, T] = (C, ResourceState[T]) => IO[ReconcileResult]

  /* Raw reconciler builder.
  If you're using a specific backend, it's more convenient to use e.g. Skuber.Reconciler[T]
   */
  def builder[IO[_], C, T](implicit
    e: Engine[IO, C, T],
    res: ObjectResource[T],
    io: Concurrent[IO],
    cs: ContextShift[IO],
  ) = new ReconcilerBuilder[IO, C, T]()
}
