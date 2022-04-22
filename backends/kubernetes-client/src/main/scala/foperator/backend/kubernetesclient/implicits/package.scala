package foperator.backend.kubernetesclient

import cats.Eq
import cats.effect.Async
import com.goyeau.kubernetes.client
import com.goyeau.kubernetes.client.crd.{CustomResource, CustomResourceList}
import foperator.Id
import foperator.types.HasSpec
import io.circe.{Decoder, Encoder}
import io.k8s.api.apps.v1._
import io.k8s.api.core.v1.{Pod, PodList, PodStatus}
import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1.{CustomResourceDefinition, CustomResourceDefinitionList, CustomResourceDefinitionStatus}
import io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta

package object implicits {
  import foperator.backend.KubernetesClient._

  // implicits that don't have a better place
  implicit val metadataEq: Eq[ObjectMeta] = Eq.fromUniversalEquals

  implicit def implicitCustomResourceResource[Sp : Encoder: Decoder, St: Encoder: Decoder]
    (implicit crd: CrdContext[CustomResource[Sp, St]])
    : ResourceImpl[St, CustomResource[Sp,St]]
    = new ResourceImpl[St, CustomResource[Sp, St]](
      (o, m) => o.copy(metadata=Some(m)),
      (o, s) => o.copy(status=Some(s)),
    )

  implicit def implicitCustomResourceResourceApi[IO[_]: Async, Sp : Encoder: Decoder, St: Encoder: Decoder]
    (implicit crd: CrdContext[CustomResource[Sp, St]])
  : ResourceApiImpl[IO, St, CustomResource[Sp,St], CustomResourceList[Sp, St]]
  = new ResourceApiImpl[IO, St, CustomResource[Sp, St], CustomResourceList[Sp, St]](
    (c, ns) => c.customResources[Sp, St](crd.ctx).namespace(ns),
    Some((c: client.KubernetesClient[IO], id: Id[CustomResource[Sp, St]], t: CustomResource[Sp, St]) => {
      c.customResources[Sp,St](crd.ctx).namespace(id.namespace).updateStatus(id.name, t)
    })
  )

  // TODO implement in ResourceImpl
  implicit def implicitCustomResourceSpec[Sp,St]: HasSpec[CustomResource[Sp, St], Sp]
  = new HasSpec[CustomResource[Sp, St], Sp] {
    override def spec(obj: CustomResource[Sp, St]): Sp = obj.spec

    override def withSpec(obj: CustomResource[Sp, St], spec: Sp): CustomResource[Sp, St] = obj.copy(spec = spec)
  }

  implicit val implicitCrdResource: ResourceImpl[CustomResourceDefinitionStatus, CustomResourceDefinition]
  = new ResourceImpl[CustomResourceDefinitionStatus, CustomResourceDefinition](
    (o, m) => o.copy(metadata=Some(m)),
    (o, s) => o.copy(status=Some(s)),
  )

  implicit def implicitCrdResourceApi[IO[_]: Async]: ResourceApiImpl[IO, CustomResourceDefinitionStatus, CustomResourceDefinition, CustomResourceDefinitionList]
  = new ResourceApiImpl[IO, CustomResourceDefinitionStatus, CustomResourceDefinition, CustomResourceDefinitionList](
    // CRDs aren't namespaced, so we just ignore the namespace
    (c, _) => c.customResourceDefinitions)

  implicit val implicitPodResource: ResourceImpl[PodStatus, Pod] = new ResourceImpl[PodStatus, Pod](
      (o, m) => o.copy(metadata=Some(m)),
      (o, s) => o.copy(status=Some(s)))

  implicit def implicitPodResourceApi[IO[_]: Async]: ResourceApiImpl[IO, PodStatus, Pod, PodList] =
    new ResourceApiImpl[IO, PodStatus, Pod, PodList]((c, ns) => c.pods.namespace(ns))

  implicit val implicitDeploymentResource: ResourceImpl[DeploymentStatus, Deployment] =
    new ResourceImpl[DeploymentStatus, Deployment](
      (o, m) => o.copy(metadata=Some(m)),
      (o, s) => o.copy(status=Some(s)))

  implicit def implicitDeploymentResourceApi[IO[_]: Async]: ResourceApiImpl[IO, DeploymentStatus, Deployment, DeploymentList] =
    new ResourceApiImpl[IO, DeploymentStatus, Deployment, DeploymentList](
      (c, ns) => c.deployments.namespace(ns))

  // TODO remaining builtin types
}
