package net.gfxmonk.foperator.backend.kubernetesclientoperator

import com.goyeau.kubernetes.client.crd.{CrdContext, CustomResource}

// a typeclass for CrdContext so we don't have to pass them around manually
trait CrdContextFor[T] {
  def ctx: CrdContext
}

object CrdContextFor {
  def apply[Sp, St](crdCtx: CrdContext): CrdContextFor[CustomResource[Sp, St]] = new CrdContextFor[CustomResource[Sp, St]] {
    override def ctx: CrdContext = crdCtx
  }
}
