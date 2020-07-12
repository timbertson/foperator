package net.gfxmonk.foperator.internal

import skuber.ObjectResource

// TODO: phantom type param for Id
case class Id(namespace: String, name: String)

object Id {
  def of(resource: ObjectResource): Id = Id(
    namespace = resource.namespace,
    name = resource.name)
}
