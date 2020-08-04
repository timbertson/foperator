package net.gfxmonk.foperator.fixture

import skuber.{CustomResource, ObjectMeta}

case class ResourceSpec(title: String)
case class ResourceStatus(something: Int)

object Resource {
  private val spec = Implicits.rd.spec
  def fixture = new CustomResource[ResourceSpec, ResourceStatus](
    kind = spec.names.kind,
    apiVersion = spec.defaultVersion,
    metadata = ObjectMeta(name="fixture", namespace = "default"),
    spec = ResourceSpec(title="??"),
    status = None
  )
}