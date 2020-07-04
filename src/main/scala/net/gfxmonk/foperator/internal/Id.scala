package net.gfxmonk.foperator.internal

import skuber.ObjectResource

case class Id(namespace: String, name: String)

object Id {
  def of(resource: ObjectResource): Id = Id(
    namespace = resource.namespace,
    name = resource.name)
}
