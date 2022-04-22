package foperator.backend.kubernetesclient.impl

import cats.Eq
import cats.effect.Async
import com.goyeau.kubernetes.client
import com.goyeau.kubernetes.client.foperatorext.Types._
import foperator._
import foperator.types._
import io.k8s.apimachinery.pkg.apis.meta.v1.{ObjectMeta, Time}
import org.http4s.Status

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.language.reflectiveCalls
import scala.reflect.ClassTag

// NOTE: ResourceAPIImpl and PureResourceImpl must be kept separate, otherwise
// scala doesn't know what to use for [F] when it only needs an ObjectResourec, not a HasResourceApi
class ResourceImpl[St, T<:ResourceGetters[St]](
  withMeta: (T, ObjectMeta) => T,
  withStatusFn: (T, St) => T,
  eq: Eq[T] = Eq.fromUniversalEquals[T],
  eqSt: Eq[St] = Eq.fromUniversalEquals[St],
)(implicit classTag: ClassTag[T]) extends ObjectResource[T] with HasStatus[T, St] with Eq[T] {
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
  override def withVersion(t: T, newVersion: String): T = withMeta(t, meta(t).copy(resourceVersion = Some(newVersion)))
  override def softDeletedAt(t: T, time: Instant): T =
    withMeta(t, meta(t).copy(deletionTimestamp = Some(Time(time.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)))))
}

// Internal trait used to tie resources to clients
// (i.e you can only build a KubernetesClient engine for types which have
// an instance of HasResourceApi
trait HasResourceApi[IO[_], T<:HasMetadata, TList<:ListOf[T]] {
  def namespaceApi(c: client.KubernetesClient[IO], ns: String): NamespacedResourceAPI[IO, T, TList] with HasResourceURI

  // NOTE: not all resources can have their status updated. This will fail
  // at runtime if you try to use it on the wrong type, because
  // encoding this in the type system sounds like too much hard work ;)
  def updateStatus(c: client.KubernetesClient[IO], t: T): IO[Status]
}

class ResourceApiImpl[IO[_], St, T<:ResourceGetters[St], TList<:ListOf[T]](
  getApi: (client.KubernetesClient[IO], String) => NamespacedResourceAPI[IO, T, TList] with HasResourceURI,
  updateStatusFn: Option[(client.KubernetesClient[IO], Id[T], T) => IO[Status]] = None,
)(implicit io: Async[IO], res: ObjectResource[T]) extends HasResourceApi[IO, T, TList] {
  override def namespaceApi(c: client.KubernetesClient[IO], ns: String): NamespacedResourceAPI[IO, T, TList] = getApi(c, ns)
  override def updateStatus(c: client.KubernetesClient[IO], t: T): IO[Status] = {
    updateStatusFn match {
      case Some(fn) => fn(c, res.id(t), t)
      case None => io.raiseError(new RuntimeException(
        s"resource ${res.kindDescription} does not have a status sub-resource. " +
          "(if it should, please update kubernetesclientoperator.implicits to expose it)")
      )
    }
  }
}

