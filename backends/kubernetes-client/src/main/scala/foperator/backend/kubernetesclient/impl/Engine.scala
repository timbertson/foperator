package foperator.backend.kubernetesclient.impl

import cats.effect.Async
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

  override def listAndWatch(c: KubernetesClient[IO], opts: ListOptions): fs2.Stream[IO, StateChange[T]] = {
    val ns = api.namespaceApi(c.underlying, opts.namespace)
    val validateFieldSelector = if (opts.fieldSelector.nonEmpty) {
      // TODO: feature request
      io.raiseError(new RuntimeException(s"kubernetes-client backend does not support fieldSelector in opts: ${opts}"))
    } else io.unit

    val validateLabels = opts.labelSelector.traverse[IO, (String, String)] { l =>
      l.split("=", 2) match {
        case Array(k,v) => io.pure((k,v))
        case _ =>
          // TODO: feature-request
          io.raiseError(new RuntimeException(s"kubernetes-client backend only supports equality-based labels, you provided: ${l}"))
      }
    }.map(_.toMap)

    Stream.eval(for {
      _ <- validateFieldSelector
      labels <- validateLabels
      // TODO: feature request: accept resourceVersion in watch request
      //       (then we wouldn't have to resetState after the fist update)
      //       https://github.com/joan38/kubernetes-client/issues/138
      resetState = Stream.evalUnChunk(ns.list(labels).map(tl => Chunk(StateChange.ResetState(tl.items.toList))))
      updates = ns.watch(labels)
    } yield {
      resetState ++ updates.zipWithIndex.flatMap[IO, StateChange[T]] {
        case (Left(err), _) => Stream.raiseError[IO](new RuntimeException(s"Error watching ${res.kindDescription} resources: $err"))
        case (Right(event), idx) => {
          val outEvent = event.`type` match {
            case EventType.ADDED | EventType.MODIFIED => io.pure(StateChange.Updated(event.`object`))
            case EventType.DELETED => io.pure(StateChange.Deleted(event.`object`))
            case EventType.ERROR => io.raiseError(new RuntimeException(s"Error watching ${res.kindDescription} resources: ${event}"))
          }
          // we force a reset after seeing the first update; this
          // ensures we catch anything missed between the initial list and update
          val maybeReset = if (idx === 0) resetState else Stream.empty
          Stream.evalUnChunk(outEvent.map(Chunk.singleton)) ++ maybeReset
        }
      }
    }).flatten
  }
}