package foperator.backend.kubernetesclient.impl

import cats.effect.{Async, Deferred}
import cats.implicits._
import com.goyeau.kubernetes.client.EventType
import com.goyeau.kubernetes.client.foperatorext.Types._
import foperator._
import foperator.backend.KubernetesClient
import foperator.internal.Logging
import foperator.types._
import fs2.{Chunk, Stream}
import org.http4s.Status
import org.http4s.client.UnexpectedStatus

import scala.concurrent.duration._
import scala.language.reflectiveCalls

class StatusError(val status: Status) extends RuntimeException(status.toString)

private [backend] class EngineImpl[IO[_], T<:HasMetadata, TList<:ListOf[T]]
  (implicit api: HasResourceApi[IO, T, TList], res: ObjectResource[T], io: Async[IO])
  extends Engine[IO, KubernetesClient[IO], T] with Logging
{
  private def ns(c: KubernetesClient[IO], id: Id[T]) =
    api.namespaceApi(c.underlying, id.namespace)

  // TODO does this really not happen by default?
  // feels like it should, and if not the kclient readme is missing error handling
  private def handleResponse(status: IO[Status]): IO[Unit] = status.flatMap { st =>
    if (st.isSuccess) {
      io.unit
    } else {
      io.raiseError(new StatusError(st))
    }
  }

  override def classifyError(e: Throwable): ClientError = {
    val status = e match {
      case s: StatusError => Some(s.status) // from handleResponse (e.g. write)
      case s: UnexpectedStatus => Some(s.status) // from anything using expect[] (e.g. get)
      case _ => None
    }
    status match {
      case Some(Status.NotFound) => ClientError.NotFound(e)
      case Some(Status.Conflict) => ClientError.VersionConflict(e)
      case _ => ClientError.Unknown(e)
    }
  }

  override def read(c: KubernetesClient[IO], id: Id[T]): IO[Option[T]] =
    ns(c, id).get(id.name).map(t => Some(t): Option[T]).handleErrorWith { e =>
      classifyError(e) match {
        case _: ClientError.NotFound => io.pure(None)
        case _ => io.raiseError(e)
      }
    }

  override def create(c: KubernetesClient[IO], t: T): IO[Unit] = handleResponse(ns(c, res.id(t)).create(t))

  override def update(c: KubernetesClient[IO], t: T): IO[Unit] = handleResponse(ns(c, res.id(t)).replace(t))

  override def updateStatus[St](c: KubernetesClient[IO], t: T, st: St)(implicit sub: HasStatus[T, St]): IO[Unit] =
    handleResponse(api.updateStatus(c.underlying, sub.withStatus(t, st)))

  override def delete(c: KubernetesClient[IO], id: Id[T]): IO[Unit] = handleResponse(ns(c, id).delete(id.name))

  override def listAndWatch(c: KubernetesClient[IO], opts: ListOptions): IO[(List[T], fs2.Stream[IO, Event[T]])] = {
    val ns = api.namespaceApi(c.underlying, opts.namespace)
    if (opts.fieldSelector.nonEmpty) {
      // TODO: feature request
      io.raiseError(new RuntimeException(s"kubernetes-client backend does not support fieldSelector in opts: ${opts}"))
    } else {
      val validateLabels = opts.labelSelector.traverse[IO, (String, String)] { l =>
        l.split("=", 2) match {
          case Array(k,v) => io.pure((k,v))
          case _ =>
            // TODO: feature-request
            io.raiseError(new RuntimeException(s"kubernetes-client backend only supports equality-based labels, you provided: ${l}"))
        }
      }.map(_.toMap)

      for {
        labels <- validateLabels
        initial <- ns.list(labels)
        // TODO: feature request: accept resourceVersion in watch request
        //       (the below reconcile code could then be removed)
        updates = ns.watch(labels)
        startReconcile <- Deferred[IO, Unit]
      } yield {
        val triggerReconcile = startReconcile.complete(()).attempt.void
        val events = updates.zipWithIndex.evalMap[IO, Event[T]] {
          case (Left(err), _) => io.raiseError(new RuntimeException(s"Error watching ${res.kindDescription} resources: $err"))
          case (Right(event), idx) => {
            (if (idx === 0) triggerReconcile else io.unit) >> (event.`type` match {
              case EventType.ADDED | EventType.MODIFIED => io.pure(Event.Updated(event.`object`))
              case EventType.DELETED => io.pure(Event.Deleted(event.`object`))
              case EventType.ERROR => io.raiseError(new RuntimeException(s"Error watching ${res.kindDescription} resources: ${event}"))
            })
          }
        }

        // we do a reconcile on the first update event,
        val reconcile: IO[List[Event[T]]] = startReconcile.get.flatMap(_ => ns.list(labels)).map { secondary =>
          val initialMap = initial.items.map(r => (res.id(r), r)).toMap
          val secondaryMap = secondary.items.map(r => (res.id(r), r)).toMap
          val allIds = (initialMap.keys ++ secondaryMap.keys).toSet
          allIds.toList.flatMap[Event[T]] { id =>
            (initialMap.get(id), secondaryMap.get(id)) match {
              case (None, Some(current)) => Some(Event.Updated(current))
              case (Some(prev), None) => Some(Event.Deleted(prev))
              case (Some(prev), Some(current)) => {
                if (res.version(prev) === res.version(current)) {
                  None
                } else {
                  Some(Event.Updated(current))
                }
              }
              case (None, None) => None // impossible, but humor the compiler
            }
          }
        }
        val reconcileStream = Stream.evalUnChunk(reconcile.map(Chunk.apply))
        val reconcileAfterDelay = Stream.evalUnChunk(io.sleep(20.seconds) >> triggerReconcile.as(Chunk.empty[Event[T]]))
        (initial.items.toList, events.merge(reconcileStream).merge(reconcileAfterDelay))
      }
    }
  }
}