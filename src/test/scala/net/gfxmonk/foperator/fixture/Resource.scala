package net.gfxmonk.foperator.fixture

import skuber.{ObjectMeta, ObjectResource}

case class Resource(name_ : String) extends ObjectResource {

  override val metadata: skuber.ObjectMeta = ObjectMeta(name=name_)

  override def apiVersion: String = "v1"

  override def kind: String = "Resource"
}
