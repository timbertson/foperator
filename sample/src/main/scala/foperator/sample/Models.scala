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
    import skuber.ResourceSpecification.{Names, Scope}
    import skuber.apiextensions.CustomResourceDefinition
    import skuber.{CustomResource, ObjectMeta, ResourceDefinition, ResourceSpecification}
    import play.api.libs.json.{Format, JsNull, JsSuccess, Json}

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

    import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1._
    import io.k8s.apimachinery.pkg.apis.meta.v1.ObjectMeta
    import com.goyeau.kubernetes.client.crd.CustomResource
    import foperator.backend.kubernetesclient.CrdContext

    val greetingCrd = CustomResourceDefinition(
      metadata = Some(ObjectMeta(name = Some("greeting." + apiGroup))),
      spec = CustomResourceDefinitionSpec(
        group = apiGroup,
        scope = "Namespace",
        names = CustomResourceDefinitionNames(
          plural = "greetings",
          singular = Some("greeting"),
          kind = "Greeting",
          shortNames = None
        ),
        versions = List(
          CustomResourceDefinitionVersion(
            name = version, served = true, storage = true,
            subresources = Some(CustomResourceSubresources(status = Some(CustomResourceSubresourceStatus())))
          )
        )
      )
    )

    implicit val greetingCrdContext = CrdContext[GreetingSpec, GreetingStatus](greetingCrd)

    type Greeting = CustomResource[GreetingSpec, GreetingStatus]
  }
}
