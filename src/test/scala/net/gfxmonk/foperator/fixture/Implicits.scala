package net.gfxmonk.foperator.fixture

import play.api.libs.json.{Format, Json}
import skuber.{CustomResource, NonCoreResourceSpecification, ResourceDefinition, ResourceSpecification}

object Implicits {
  implicit val specFmt = Json.format[ResourceSpec]
  implicit val statusFmt = Json.format[ResourceStatus]
  implicit val fmt = CustomResource.crFormat[ResourceSpec,ResourceStatus]

  implicit val rd: ResourceDefinition[Resource] = new ResourceDefinition[Resource] {
    override def spec: ResourceSpecification = NonCoreResourceSpecification(
      apiGroup = "net.gfxmonk.foperator",
      version=None,
      versions=List(ResourceSpecification.Version("v1", served=true)),
      scope = ResourceSpecification.Scope.Cluster,
      names = ResourceSpecification.Names(
        plural = "resources",
        singular = "resource",
        kind = "Resource",
        shortNames = Nil
      )
    )
  }
}
