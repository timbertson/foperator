package foperator.sample.generic

import cats.effect.{Concurrent, ContextShift, Timer}
import cats.implicits._
import foperator.sample.Models.{GreetingSpec, GreetingStatus}
import foperator.types.{Engine, HasSpec, HasStatus, ObjectResource}
import foperator.{Client, Reconciler}

/**
 Most operators are written for a particular backend, which is recommended (for simplicity).
 This operator (and the sibling files) demonstrate what's required to be completely backend-agnostic.
 Note that since the backends have different CustomResource types, the Greeting and Status
 types also need to be generic, and manipulated only through associated typeclasses.
 If you only want to target one real client and a TestClient, you won't need to make your
 resource types generic (since TestClient can work with any valid resource type).

 Tips:
  - you'll need to have an (implicit) Engine for each concrete type you need to act on,
    as well as the various ObjectResource / HasSpec / HasStatus to allow you to act on resources
  - passing around a client is unnecessary, the `Operations.Builder` type is more convenient and
    exposes everything useful you can do with a client.
*/
class GenericOperator[IO[_], C, CRD, CR[_, _]](
  ops: Client[IO, C],
  greetingCRD: CRD,
)
  (implicit
    io: Concurrent[IO],
    cs: ContextShift[IO],
    t: Timer[IO],

    // For concrete types, all of these are implicitly available.
    // But when using generic types, you need to list them individually
    sp: HasSpec[CR[GreetingSpec, GreetingStatus], GreetingSpec],
    st: HasStatus[CR[GreetingSpec, GreetingStatus], GreetingStatus],
    ge: Engine[IO, C, CR[GreetingSpec, GreetingStatus]],
    cre: Engine[IO, C, CRD],
    res: ObjectResource[CR[GreetingSpec,GreetingStatus]],
    crdRes: ObjectResource[CRD],
  )
{
  type Greeting = CR[GreetingSpec, GreetingStatus]

  private def expectedStatus(greeting: Greeting) =
    GreetingStatus(s"Hello, ${sp.spec(greeting).name.getOrElse("UNKNOWN")}", Nil)

  private val reconciler = Reconciler.builder[IO, C, Greeting].status { greeting =>
    // Always return the expected status, Reconciler.customResourceUpdater
    // will make this a no-op without any API calls if it is unchanged.
    io.pure(expectedStatus(greeting))
  }

  private def install = ops[CRD].forceWrite(greetingCRD)

  def run: IO[Unit] = install >> ops[Greeting].runReconciler(reconciler)
}