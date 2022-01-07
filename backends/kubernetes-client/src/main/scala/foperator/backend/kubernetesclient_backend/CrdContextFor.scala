package foperator.backend.kubernetesclient_backend

import com.goyeau.kubernetes.client.crd.{CrdContext, CustomResource}
import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1.CustomResourceDefinition

// a typeclass for CrdContext so we don't have to pass them around manually
trait CrdContextFor[T] {
  def ctx: CrdContext
}

object CrdContextFor {
  def wrap[Sp, St](crdCtx: CrdContext): CrdContextFor[CustomResource[Sp, St]] = new CrdContextFor[CustomResource[Sp, St]] {
    override def ctx: CrdContext = crdCtx
  }

  def apply[Sp, St](defn: CustomResourceDefinition): CrdContextFor[CustomResource[Sp, St]] = {
    val impl = CrdContext(
      group = defn.spec.group,
      version = defn.spec.versions.find(_.storage).getOrElse(throw new RuntimeException(s"CRD has no version with storage=true: ${defn}")).name,
      plural = defn.spec.names.plural)

    new CrdContextFor[CustomResource[Sp, St]] {
      override def ctx: CrdContext = impl
    }
  }
}
