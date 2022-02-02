package foperator.backend.kubernetesclient

import com.goyeau.kubernetes.client.crd
import com.goyeau.kubernetes.client.crd.CustomResource
import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1.CustomResourceDefinition

// a typeclass for CrdContext so we don't have to pass them around manually
trait CrdContext[T] {
  def ctx: crd.CrdContext
}

object CrdContext {
  def wrap[Sp, St](crdCtx: crd.CrdContext): CrdContext[CustomResource[Sp, St]] = new CrdContext[CustomResource[Sp, St]] {
    override def ctx: crd.CrdContext = crdCtx
  }

  def apply[Sp, St](defn: CustomResourceDefinition): CrdContext[CustomResource[Sp, St]] = {
    val impl = crd.CrdContext(
      group = defn.spec.group,
      version = defn.spec.versions.find(_.storage).getOrElse(throw new RuntimeException(s"CRD has no version with storage=true: ${defn}")).name,
      plural = defn.spec.names.plural)

    new CrdContext[CustomResource[Sp, St]] {
      override def ctx: crd.CrdContext = impl
    }
  }
}
