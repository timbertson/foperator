package foperator.backend.skuber_backend

import skuber.ResourceDefinition

case class CustomResourceDefinition[Sp,St](metadata: skuber.ObjectMeta, spec: skuber.apiextensions.CustomResourceDefinition.Spec) {
  val skuberCRD = skuber.apiextensions.CustomResourceDefinition(metadata=metadata, spec=spec)

  implicit val resourceDefinition: skuber.ResourceDefinition[skuber.CustomResource[Sp, St]] = ResourceDefinition[skuber.CustomResource[Sp,St]](skuberCRD)
}
