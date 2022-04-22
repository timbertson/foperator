package foperator.backend

import cats.Eq
import cats.effect.{Async, Deferred, Resource}
import cats.implicits._
import com.goyeau.kubernetes.client
import com.goyeau.kubernetes.client.foperatorext.Types._
import com.goyeau.kubernetes.client.{EventType, KubeConfig}
import foperator._
import foperator.internal.Logging
import foperator.types._
import fs2.{Chunk, Stream}
import io.k8s.apimachinery.pkg.apis.meta.v1.{ObjectMeta, Time}
import org.http4s.Status
import org.http4s.client.UnexpectedStatus
import org.typelevel.log4cats.Logger

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration._
import scala.language.reflectiveCalls
import scala.reflect.ClassTag

class KubernetesClient[IO[_] : Async](val underlying: client.KubernetesClient[IO])
  extends Client[IO, KubernetesClient[IO]] {
  override def apply[T]
    (implicit e: Engine[IO, KubernetesClient[IO], T], res: ObjectResource[T]): Operations[IO, KubernetesClient[IO], T]
    = new Operations[IO, KubernetesClient[IO], T](this)
}

object KubernetesClient {
  def apply[IO[_]: Async : Logger] = new Companion[IO]

  class Companion[IO[_]](implicit io: Async[IO], logger: Logger[IO]) extends Client.Companion[IO, KubernetesClient[IO]] {
    def wrap(underlying: client.KubernetesClient[IO]): KubernetesClient[IO] = new KubernetesClient(underlying)

    def default: Resource[IO, KubernetesClient[IO]] = for {
      path <- Resource.eval(KubeconfigPath.fromEnv[IO])
      client <- client.KubernetesClient[IO](KubeConfig.fromFile(new File(path)))
    } yield wrap(client)

    def apply(config: KubeConfig): Resource[IO, KubernetesClient[IO]] = client.KubernetesClient[IO](config).map(wrap)

    def apply(config: IO[KubeConfig]): Resource[IO, KubernetesClient[IO]] = client.KubernetesClient(config).map(wrap)
  }

  implicit def engine[IO[_] : Async, T<:HasMetadata, TList<:ListOf[T]]
    (implicit api: HasResourceApi[IO, T, TList], res: ObjectResource[T])
  : Engine[IO, KubernetesClient[IO], T]
  = new EngineImpl[IO, T, TList]

  // Internal traits used to tie resources to clients
  // (i.e you can only build a KubernetesClient engine for types which have
  // an instance of HasResourceApi
  private [backend] trait HasResourceApi[IO[_], T<:HasMetadata, TList<:ListOf[T]] {
    def namespaceApi(c: client.KubernetesClient[IO], ns: String): NamespacedResourceAPI[IO, T, TList] with HasResourceURI

    // NOTE: not all resources can have their status updated. This will fail
    // at runtime if you try to use it on the wrong type, because
    // encoding this in the type system sounds like too much hard work ;)
    def updateStatus(c: client.KubernetesClient[IO], t: T): IO[Status]
  }

  private [backend] class ResourceImpl[IO[_], St, T<:ResourceGetters[St], TList<:ListOf[T]](
    getApi: (client.KubernetesClient[IO], String) => NamespacedResourceAPI[IO, T, TList] with HasResourceURI,
    withMeta: (T, ObjectMeta) => T,
    withStatusFn: (T, St) => T,
    updateStatusFn: (client.KubernetesClient[IO], Id[T], T) => IO[Status],
    eq: Eq[T] = Eq.fromUniversalEquals[T],
    eqSt: Eq[St] = Eq.fromUniversalEquals[St],
  )(implicit classTag: ClassTag[T]) extends ObjectResource[T] with HasStatus[T, St] with HasResourceApi[IO, T, TList] with Eq[T] {

    private def meta(t: T) = t.metadata.getOrElse(ObjectMeta())

    override val eqStatus: Eq[St] = eqSt

    override def status(obj: T): Option[St] = obj.status

    override def withStatus(obj: T, status: St): T = withStatusFn(obj, status)

    override def kindDescription: String = classTag.runtimeClass.getSimpleName

    override def finalizers(t: T): List[String] = t.metadata.flatMap(_.finalizers).map(_.toList).getOrElse(Nil)

    override def replaceFinalizers(t: T, f: List[String]): T = withMeta(t, meta(t).copy(finalizers = Some(f)))

    override def version(t: T): Option[String] = t.metadata.flatMap(_.resourceVersion)

    override def id(t: T): Id[T] = t.metadata.map { m =>
      Id[T](m.namespace.getOrElse(""), m.name.getOrElse(""))
    }.getOrElse(Id[T]("", ""))

    override def isSoftDeleted(t: T): Boolean = t.metadata.flatMap(_.deletionTimestamp).isDefined

    override def eqv(x: T, y: T): Boolean = eq.eqv(x, y)

    override def withVersion(t: T, newVersion: String): T =
      withMeta(t, meta(t).copy(resourceVersion = Some(newVersion)))

    override def softDeletedAt(t: T, time: Instant): T =
      withMeta(t, meta(t).copy(deletionTimestamp = Some(Time(time.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)))))

    override def namespaceApi(c: client.KubernetesClient[IO], ns: String): NamespacedResourceAPI[IO, T, TList] = getApi(c, ns)

    override def updateStatus(c: client.KubernetesClient[IO], t: T): IO[Status] = updateStatusFn(c, id(t), t)
  }

  class StatusError(val status: Status) extends RuntimeException(status.toString)

  private class EngineImpl[IO[_], T<:HasMetadata, TList<:ListOf[T]]
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
}


