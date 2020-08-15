package net.gfxmonk.foperator.sample

import cats.Eq
import net.gfxmonk.foperator.{Id, Update}
import play.api.libs.json.{Format, JsNull, JsSuccess, Json}
import skuber.ResourceSpecification.{Names, Scope}
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ObjectMeta, ResourceDefinition, ResourceSpecification}

object Models {
  /** Greeting */
  case class GreetingSpec(name: Option[String], surname: Option[String])
  case class GreetingStatus(message: String, people: List[String])
  object GreetingStatus {
    def peopleIds(update: Update.Status[Greeting, GreetingStatus]): List[Id[Person]] = update.status.people.map { name =>
      Id.createUnsafe(namespace = update.initial.metadata.namespace, name = name)
    }
  }
  implicit val eqGreetingSpec: Eq[GreetingSpec] = Eq.fromUniversalEquals
  implicit val eqGreetingStatus: Eq[GreetingStatus] = Eq.fromUniversalEquals

  type Greeting = CustomResource[GreetingSpec,GreetingStatus]

  implicit val statusFmt = Json.format[GreetingStatus]
  implicit val specFmt = Json.format[GreetingSpec]
  implicit val fmt = CustomResource.crFormat[GreetingSpec,GreetingStatus]

  val greetingSpec = CustomResourceDefinition.Spec(
    apiGroup="sample.foperator.gfxmonk.net",
    version="v1alpha1",
    names=Names(
      plural = "greetings",
      singular = "greeting",
      kind = "Greeting",
      shortNames = Nil
    ),
    scope=Scope.Namespaced,
  ).copy(subresources = Some(ResourceSpecification.Subresources().withStatusSubresource()))

  val greetingCrd = CustomResourceDefinition(
    metadata=ObjectMeta(name="greetings.sample.foperator.gfxmonk.net"),
    spec=greetingSpec)

  implicit val greetingRd: ResourceDefinition[Greeting] = ResourceDefinition(greetingCrd)
  implicit val st: skuber.HasStatusSubresource[Greeting] = CustomResource.statusMethodsEnabler[Greeting]

  /** Person */
  case class PersonSpec(firstName: String, surname: String)
  implicit val eqPersonSpec: Eq[PersonSpec] = Eq.fromUniversalEquals

  type Person = CustomResource[PersonSpec, Unit]
  val personSpec = CustomResourceDefinition.Spec(
    apiGroup="sample.foperator.gfxmonk.net",
    version="v1alpha1",
    names=Names(
      plural = "people",
      singular = "person",
      kind = "Person",
      shortNames = Nil
    ),
    scope=Scope.Namespaced,
  ).copy(subresources = Some(ResourceSpecification.Subresources().withStatusSubresource()))

  val personCrd = CustomResourceDefinition(
    metadata=ObjectMeta(name="people.sample.foperator.gfxmonk.net"),
    spec=personSpec)

  implicit val personRd: ResourceDefinition[Person] = ResourceDefinition(personCrd)

  implicit val personSpecFmt = Json.format[PersonSpec]
  implicit val unitFormat: Format[Unit] = Format(_ => JsSuccess(()), _ => JsNull)
  implicit val personFmt = CustomResource.crFormat[PersonSpec, Unit]
  implicit val personHasStatus: skuber.HasStatusSubresource[Person] = CustomResource.statusMethodsEnabler[Person]
}
