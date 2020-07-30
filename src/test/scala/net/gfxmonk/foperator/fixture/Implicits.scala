package net.gfxmonk.foperator.fixture

import play.api.libs.json.{Format, JsResult, JsValue}
import skuber.{NonCoreResourceSpecification, ResourceDefinition, ResourceSpecification}

object Implicits {
  implicit val fmt: Format[Resource] = new Format[Resource] {
    override def reads(json: JsValue): JsResult[Resource] = ???

    override def writes(o: Resource): JsValue = ???
  }

  implicit val rd: ResourceDefinition[Resource] = new ResourceDefinition[Resource] {
    override def spec: ResourceSpecification = NonCoreResourceSpecification(
      apiGroup = "net.gfxmonk.foperator",
      version=None,
      versions=Nil,
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
