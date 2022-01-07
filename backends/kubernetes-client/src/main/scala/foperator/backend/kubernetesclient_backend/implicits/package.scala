package foperator.backend.kubernetesclient_backend

import cats.Eq
import cats.effect.Async
import com.goyeau.kubernetes.client.KubernetesClient
import com.goyeau.kubernetes.client.crd.{CustomResource, CustomResourceList}
import io.circe.{Decoder, Encoder}
import io.k8s.api.core.v1.{Pod, PodList, PodStatus}
import io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta
import foperator
import foperator.Id
import org.http4s.Status

import scala.reflect.ClassTag

package object implicits extends foperator.CommonImplicits {
  import KClient._

  // implicits that don't have a better place
  implicit val metadataEq: Eq[ObjectMeta] = Eq.fromUniversalEquals

  private def cantUpdateStatus[IO[_], T]
    (implicit io: Async[IO], ct: ClassTag[T]): (KubernetesClient[IO], Id[T], T) => IO[Status] =
    (_, _, _) => io.raiseError(new RuntimeException(
      s"resource ${ct.getClass.getSimpleName} does not have a status sub-resource. " +
      "(if it should, please update kubernetesclientoperator.implicits to expose it)")
    )

  implicit def implicitPodResource[IO[_]: Async]: ResourceImpl[IO, PodStatus, Pod, PodList] =
    new ResourceImpl[IO, PodStatus, Pod, PodList](
      _.pods,
      (o, m) => o.copy(metadata=Some(m)),
      (o, s) => o.copy(status=Some(s)),
      cantUpdateStatus[IO, Pod]
    )

  implicit def implicitCustomResource[IO[_], Sp : Encoder: Decoder, St: Encoder: Decoder]
    (implicit crd: CrdContextFor[CustomResource[Sp, St]])
    : ResourceImpl[IO, St, CustomResource[Sp,St], CustomResourceList[Sp, St]]
    = new ResourceImpl[IO, St, CustomResource[Sp, St], CustomResourceList[Sp, St]](
      _.customResources[Sp, St](crd.ctx),
      (o, m) => o.copy(metadata=Some(m)),
      (o, s) => o.copy(status=Some(s)),
      (client: KubernetesClient[IO], id: Id[CustomResource[Sp, St]], t: CustomResource[Sp, St]) => {
        client.customResources[Sp,St](crd.ctx).namespace(id.namespace).updateStatus(id.name, t)
      }
    )

  // TODO remaining builtin types
}
