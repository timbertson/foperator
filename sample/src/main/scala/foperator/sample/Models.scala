package foperator.sample

object Models {
  import cats.Eq

  case class GreetingSpec(name: Option[String], surname: Option[String])
  case class GreetingStatus(message: String, people: List[String])

  implicit val eqGreetingSpec: Eq[GreetingSpec] = Eq.fromUniversalEquals
  implicit val eqGreetingStatus: Eq[GreetingStatus] = Eq.fromUniversalEquals

  case class PersonSpec(firstName: String, surname: String)
  implicit val eqPersonSpec: Eq[PersonSpec] = Eq.fromUniversalEquals

  val apiGroup = "sample.foperator.gfxmonk.net"
  val version = "v1alpha1"

  object Skuber {
    // implicits / definitions required for skuber operators
    import play.api.libs.json.{Format, JsNull, JsSuccess, Json}
    import skuber.ResourceSpecification.{Names, Scope}
    import skuber.apiextensions.CustomResourceDefinition
    import skuber.{CustomResource, ObjectMeta, ResourceDefinition, ResourceSpecification}

    type Greeting = CustomResource[GreetingSpec,GreetingStatus]

    implicit val statusFmt = Json.format[GreetingStatus]
    implicit val specFmt = Json.format[GreetingSpec]
    implicit val fmt = CustomResource.crFormat[GreetingSpec,GreetingStatus]

    val greetingCrd = CustomResourceDefinition(
      metadata=ObjectMeta(name="greetings." + apiGroup),
      spec = CustomResourceDefinition.Spec(
        apiGroup = apiGroup,
        version = version,
        names = Names(
          plural = "greetings",
          singular = "greeting",
          kind = "Greeting",
          shortNames = Nil
        ),
        scope = Scope.Namespaced,
      ).copy(subresources = Some(ResourceSpecification.Subresources().withStatusSubresource())))

    implicit val greetingRd: ResourceDefinition[Greeting] = ResourceDefinition(greetingCrd)

    type Person = CustomResource[PersonSpec, Unit]
    val personCrd = CustomResourceDefinition(
      metadata=ObjectMeta(name="people." + apiGroup),
      spec = CustomResourceDefinition.Spec(
        apiGroup = apiGroup,
        version = version,
        names = Names(
          plural = "people",
          singular = "person",
          kind = "Person",
          shortNames = Nil
        ),
        scope = Scope.Namespaced,
      ).copy(subresources = Some(ResourceSpecification.Subresources().withStatusSubresource())))

    implicit val personRd: ResourceDefinition[Person] = ResourceDefinition(personCrd)
    implicit val personSpecFmt = Json.format[PersonSpec]
    implicit val unitFormat: Format[Unit] = Format(_ => JsSuccess(()), _ => JsNull)
  }

  object KubernetesClient {
    // implicits / definitions required for kubernetes-client operators

    import com.goyeau.kubernetes.client.crd._
    import foperator.backend.kubernetesclient.CrdContext
    import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1._
    import io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta

    val greetingCrd = CustomResourceDefinition(
      metadata = Some(ObjectMeta(name = Some("greetings." + apiGroup))),
      spec = CustomResourceDefinitionSpec(
        group = apiGroup,
        scope = "Namespaced",
        names = CustomResourceDefinitionNames(
          plural = "greetings",
          singular = Some("greeting"),
          kind = "Greeting",
          shortNames = None
        ),
        versions = List(
          CustomResourceDefinitionVersion(
            name = version, served = true, storage = true,
            subresources = Some(CustomResourceSubresources(status = Some(CustomResourceSubresourceStatus()))),
            schema = Some(CustomResourceValidation(openAPIV3Schema = Some(JSONSchemaProps(
              `type` = Some("object"),
              properties = Some(Map(
                "spec" -> JSONSchemaProps(
                  `type` = Some("object"),
                  properties = Some(Map(
                    "name" -> JSONSchemaProps(`type`= Some("string")),
                    "surname" -> JSONSchemaProps(`type`= Some("string")),
                  ))
                ),
                "status" -> JSONSchemaProps(
                  `type` = Some("object"),
                  properties = Some(Map(
                    "message" -> JSONSchemaProps(`type`= Some("string")),
                    "people" -> JSONSchemaProps(`type`= Some("array"), items = Some(SchemaNotArrayValue(JSONSchemaProps(`type` = Some("string"))))),
                  )),
                  required = Some(List("message", "people"))
                )
              )),
            ))))
          )
        )
      )
    )

    implicit val greetingCrdContext = CrdContext[GreetingSpec, GreetingStatus](greetingCrd)

    type Greeting = CustomResource[GreetingSpec, GreetingStatus]
  }
}
