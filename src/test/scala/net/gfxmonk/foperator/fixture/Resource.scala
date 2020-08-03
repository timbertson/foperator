package net.gfxmonk.foperator.fixture

import skuber.CustomResource

case class ResourceSpec(name: String)
case class ResourceStatus(something: Int)

object Resource {
  import Implicits._
  def fixture = CustomResource[ResourceSpec, ResourceStatus](ResourceSpec(name="??"))
}