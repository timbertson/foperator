package foperator.backend.kubernetesclient_backend

import cats.Eq
import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.implicits._
import com.goyeau.kubernetes.client.foperatorext.Types.{HasMetadata, ListOf, ResourceAPI, ResourceGetters}
import com.goyeau.kubernetes.client.{EventType, KubeConfig, KubernetesClient}
import fs2.{Chunk, Stream}
import io.k8s.apimachinery.pkg.apis.meta.v1.{ObjectMeta, Time}
import monix.eval.Task
import monix.eval.instances.CatsConcurrentEffectForTask
import monix.execution.Scheduler
import foperator.internal.{BackendCompanion, Logging}
import foperator.types._
import foperator.{Event, Id, ListOptions, Operations}
import org.http4s.{Status, Uri}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration._
import scala.language.reflectiveCalls

class KClient[IO[_] : Concurrent : ContextShift](val underlying: KubernetesClient[IO]) {
  val ops = Operations[IO, KClient[IO]](this)
}

object KClient {
  def apply[IO[_]](implicit cs: ContextShift[IO], ce: ConcurrentEffect[IO]) = new KClientCompanion[IO]

  implicit def engine[IO[_] : Concurrent : ContextShift : Timer, T<:HasMetadata, TList<:ListOf[T]]
    (implicit api: HasResourceApi[IO, T, TList], res: ObjectResource[T], io: Sync[IO])
  : Engine[IO, KClient[IO], T]
  = new EngineImpl[IO, T, TList]

  // Internal traits used to tie resources to clients
  // (i.e you can only build a KClient engine for types which have
  // an instance of HasResourceApi
  private [kubernetesclient_backend] trait HasResourceApi[IO[_], T<:HasMetadata, TList<:ListOf[T]] {
    def resourceAPI(client: KubernetesClient[IO]): ResourceAPI[IO, T, TList]

    // NOTE: not all resources can have their status updated. This will fail
    // at runtime if you try to use it on the wrong type, because
    // encoding this in the type system sounds like too much hard work ;)
    def updateStatus(client: KubernetesClient[IO], t: T): IO[Status]
  }

  // we need a fake client instance just to get api endpoint strings.
  // This is definitely using it wrong, but we only need a few strings,
  // and they're only used in the TestClient backend :shrug:
  // TODO feature request: expose kind somewhere more accessible
  private val dummyClient = {
    implicit val io: CatsConcurrentEffectForTask = Task.catsEffect(Scheduler.global)
    KubernetesClient(KubeConfig.of[Task](
      Uri.fromString("https://127.0.0.1/fake-kubernetes").toOption.get
    )).use(Task.pure).runSyncUnsafe(10.seconds)(Scheduler.global, implicitly)
  }

  class ResourceImpl[IO[_], St, T<:ResourceGetters[St], TList<:ListOf[T]](
    getApi: KubernetesClient[IO] => ResourceAPI[IO, T, TList],
    withMeta: (T, ObjectMeta) => T,
    withStatusFn: (T, St) => T,
    updateStatusFn: (KubernetesClient[IO], Id[T], T) => IO[Status],
    eq: Eq[T] = Eq.fromUniversalEquals[T],
    eqSt: Eq[St] = Eq.fromUniversalEquals[St],
  ) extends ObjectResource[T] with HasStatus[T, St] with HasResourceApi[IO, T, TList] with Eq[T] {

    private def meta(t: T) = t.metadata.getOrElse(ObjectMeta())

    override val eqStatus: Eq[St] = eqSt

    override def status(obj: T): Option[St] = obj.status

    override def withStatus(obj: T, status: St): T = withStatusFn(obj, status)

    override def kind: String = getApi(dummyClient.asInstanceOf[KubernetesClient[IO]]).resourceUri.path.segments.lastOption.map(_.toString).getOrElse("UNKNOWN")

    override def finalizers(t: T): List[String] = t.metadata.flatMap(_.finalizers).map(_.toList).getOrElse(Nil)

    override def replaceFinalizers(t: T, f: List[String]): T = withMeta(t, meta(t).copy(finalizers = Some(f)))

    override def version(t: T): String = t.metadata.flatMap(_.resourceVersion).getOrElse("")

    override def id(t: T): Id[T] = t.metadata.map { m =>
      Id[T](m.namespace.getOrElse(""), m.name.getOrElse(""))
    }.getOrElse(Id[T]("", ""))

    override def isSoftDeleted(t: T): Boolean = t.metadata.flatMap(_.deletionTimestamp).isDefined

    override def eqv(x: T, y: T): Boolean = eq.eqv(x, y)

    override def withVersion(t: T, newVersion: String): T =
      withMeta(t, meta(t).copy(resourceVersion = Some(newVersion)))

    override def softDeletedAt(t: T, time: Instant): T =
      withMeta(t, meta(t).copy(deletionTimestamp = Some(Time(time.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)))))

    override def resourceAPI(client: KubernetesClient[IO]): ResourceAPI[IO, T, TList] = getApi(client)

    override def updateStatus(client: KubernetesClient[IO], t: T): IO[Status] = updateStatusFn(client, id(t), t)
  }

  class StatusError(val status: Status) extends RuntimeException(status.toString)

  private class EngineImpl[IO[_], T<:HasMetadata, TList<:ListOf[T]]
    (implicit api: HasResourceApi[IO, T, TList], res: ObjectResource[T], io: Concurrent[IO], timer: Timer[IO])
    extends Engine[IO, KClient[IO], T] with Logging
  {
    private def ns(c: KClient[IO], id: Id[T]) =
      api.resourceAPI(c.underlying).namespace(id.namespace)

    // TODO does this happen by default? feels liks it should, and if not the kclient readme is missing error handling
    private def handleResponse(status: IO[Status]): IO[Unit] = status.flatMap { st =>
      if (st.isSuccess) {
        io.unit
      } else {
        io.raiseError(new StatusError(st))
      }
    }

    override def classifyError(e: Throwable): ClientError = e match {
      case s: StatusError => s.status match {
        case Status.NotFound => ClientError.NotFound(e)
        case Status.Conflict => ClientError.VersionConflict(e)
        case _ => ClientError.Unknown(e)
      }
      // TODO does kclient throw its own errors?
      case other => ClientError.Unknown(e)
    }

    // TODO this doesn't report 404 correctly
    override def read(c: KClient[IO], t: Id[T]): IO[Option[T]] = ns(c, t).get(t.name).map(Some.apply)

    override def write(c: KClient[IO], t: T): IO[Unit] = handleResponse(ns(c, res.id(t)).replace(t))

    override def writeStatus[St](c: KClient[IO], t: T, st: St)(implicit sub: HasStatus[T, St]): IO[Unit] =
      handleResponse(api.updateStatus(c.underlying, sub.withStatus(t, st)))

    override def delete(c: KClient[IO], id: Id[T]): IO[Unit] = handleResponse(ns(c, id).delete(id.name))

    override def listAndWatch(c: KClient[IO], opts: ListOptions): IO[(List[T], fs2.Stream[IO, Event[T]])] = {
      val ns = api.resourceAPI(c.underlying).namespace(opts.namespace)
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
            case (Left(err), _) => io.raiseError(new RuntimeException(s"Error watching ${res.kind} resources: $err"))
            case (Right(event), idx) => {
              (if (idx === 0) triggerReconcile else io.unit) >> (event.`type` match {
                case EventType.ADDED | EventType.MODIFIED => io.pure(Event.Updated(event.`object`))
                case EventType.DELETED => io.pure(Event.Deleted(event.`object`))
                case EventType.ERROR => io.raiseError(new RuntimeException(s"Error watching ${res.kind} resources: ${event}"))
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
              }
            }
          }
          val reconcileStream = Stream.evalUnChunk(reconcile.map(Chunk.apply))
          val reconcileAfterDelay = Stream.evalUnChunk(timer.sleep(20.seconds) >> triggerReconcile.as(Chunk.empty[Event[T]]))
          (initial.items.toList, events.merge(reconcileStream).merge(reconcileAfterDelay))
        }
      }
    }
  }
}

class KClientCompanion[IO[_]](implicit cs: ContextShift[IO], ce: ConcurrentEffect[IO]) extends BackendCompanion[IO, KClient[IO]] {

  def wrap(underlying: KubernetesClient[IO]) = new KClient(underlying)

  def apply(config: KubeConfig) = KubernetesClient[IO](config)

  def apply[F[_]: ConcurrentEffect: ContextShift](config: F[KubeConfig]): Resource[F, KubernetesClient[F]] = KubernetesClient(config)

  type Ops[T] = Operations[IO, KClient[IO], T]

}


