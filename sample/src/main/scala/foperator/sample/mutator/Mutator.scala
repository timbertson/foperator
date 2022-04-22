package foperator.sample.mutator

import cats.Eq
import cats.effect.{IO, IOApp}
import cats.implicits._
import foperator._
import foperator.backend.Skuber
import foperator.backend.skuber.implicits._
import foperator.internal.Logging
import foperator.sample.Models.Skuber._
import foperator.sample.Models.{GreetingSpec, PersonSpec}
import foperator.sample.PrettyPrint.Implicits._
import foperator.sample.{AdvancedOperator, PrettyPrint, SimpleOperator}
import foperator.types.{Engine, ObjectResource}
import fs2.{Stream, text}
import skuber.CustomResource

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

object Simple extends IOApp.Simple with Logging {
  override def run: IO[Unit] = {
    Skuber[IO].default().use { client =>
      val simple = new SimpleOperator(client)
      simple.install >> Mutator.withResourceMirrors(client) { (greetings, people) =>
        List(
          client[Greeting].runReconcilerWithInput(greetings, SimpleOperator.reconciler),
          new Mutator(client, greetings, people).run
        ).parSequence_
      }
    }
  }
}

object Advanced extends IOApp.Simple {
  override def run: IO[Unit] = {
    Skuber[IO].default().use { client =>
      val advanced = new AdvancedOperator(client)
      advanced.install >> Mutator.withResourceMirrors(client) { (greetings, people) =>
        List(
          advanced.runWith(greetings, people),
          new Mutator(client, greetings, people).run
        ).parSequence_
      }
    }
  }
}

object Standalone extends IOApp.Simple {
  override def run: IO[Unit] = {
    Skuber[IO].default().use { client =>
      new AdvancedOperator(client).install >>
      Mutator.withResourceMirrors(client) { (greetings, people) =>
        new Mutator(client, greetings, people).run
      }
    }
  }
}

// Mutator is an app for randomly mutating Greeting / People objects and observing the results.
// It logs all changes to these resources, and attempts a mutation whenever you press Return
// (Not all mutations succeed, it'll pick a new mutation on failure)
object Mutator extends Logging {
  implicit def withNameSkuber[T<:skuber.ObjectResource](implicit ed: skuber.ObjectEditor[T]): WithName[T] = new WithName[T] {
    override def withName(value: T, name: String): T = ed.updateMetadata(value, value.metadata.copy(name = name))
  }

  sealed trait Decision // for traversing a decision tree

  object Decision {
    case class Concrete(action: Action) extends Decision
    case class Choice[T](inputs: List[T], expand: T => Decision) extends Decision {
      def isEmpty = inputs.isEmpty
      def size = inputs.length
      def get(index: Int) = expand(inputs.apply(index)) // this can throw, but I'm lazy and it shouldn't
    }

    def apply(single: Action) = Concrete(single)

    def apply[T](inputs: List[T])(expand: T => Decision) = Choice(inputs, expand)

    def eager(decisions: Decision*) = Choice(decisions.toList, identity[Decision])

    def randomAction(random: Random, decision: Decision): IO[Action] = {
      def nextInt(limit: Int) = IO(random.nextInt(limit))

      decision match {
        case Concrete(action) => IO.pure(action)
        case choice: Choice[_] => {
          if (choice.isEmpty) {
            IO.pure(Noop)
          } else {
            nextInt(choice.inputs.length).map(choice.get).flatMap(randomAction(random, _))
          }
        }
      }
    }
  }

  sealed trait Action {
    def conflictKey: Option[Id[_]]
    def run: IO[Unit]
    def pretty: String
  }

  case object Noop extends Action {
    override def run: IO[Unit] = IO.raiseError(new RuntimeException("No action found (reached a branch with no children)"))

    override def pretty: String = "Noop"

    override def conflictKey: Option[Id[_]] = None
  }

  case class Delete[T](resource: T)(implicit rd: ObjectResource[T], ops: Operations[IO, _, T])
    extends Action
  {
    override def run: IO[Unit] = ops.delete(Id.of(resource))

    override def pretty: String = s"Delete[${rd.kindDescription}](${rd.id(resource)})"

    override def conflictKey: Option[Id[_]] = Some(Id.of(resource))
  }

  case class Create[T](random: Random, resource: T)
    (implicit ops: Operations[IO, _, T], res: ObjectResource[T], n: WithName[T]) extends Action
  {
    def pretty: String = s"Create[${res.kindDescription}]"

    private def randomId: IO[String] = {
      IO(random.nextBytes(4)).map { bytes =>
        bytes.map(b => String.format("%02X", b & 0xff)).mkString.toLowerCase
      }
    }

    private def namedResource = randomId.map(id => n.withName(resource, id))

    override def run: IO[Unit] = {
      namedResource.flatMap { resource =>
        ops.write(resource).void
      }
    }

    override def conflictKey: Option[Id[_]] = None
  }

  case class Modify[Sp,St] private (initial: CustomResource[Sp,St], newSpec: Sp)(
    implicit res: ObjectResource[CustomResource[Sp, St]],
    ops: Operations[IO, _, CustomResource[Sp,St]],
    pp: PrettyPrint[Sp]
  ) extends Action {
    override def run: IO[Unit] = ops.write(initial.copy(spec=newSpec)).void

    def pretty: String = s"Modify[${res.kindDescription}](${res.id(initial)}, ${pp.pretty(newSpec)})"

    override def conflictKey: Option[Id[_]] = Some(Id.of(initial))
  }

  object Modify {
    def spec[Sp,St](initial: CustomResource[Sp,St], newSpec: Sp)(
      implicit res: ObjectResource[CustomResource[Sp,St]],
      ops: Operations[IO, _, CustomResource[Sp,St]],
      pp: PrettyPrint[Sp],
      eqSp: Eq[Sp]
    ): Action = {
      // override Modify constructor to return Noop when nothing is actually changing
      if (initial.spec === newSpec) {
        Noop
      } else {
        new Modify(initial, newSpec)
      }
    }
  }

  // Since we want to share one mirror globally, we use this as the toplevel hook, and run
  // all mutators / operators within the `op`
  def withResourceMirrors[T](client: Skuber[IO])(op: (ResourceMirror[IO, Greeting], ResourceMirror[IO, Person]) => IO[T]): IO[T] = {
    IO(logger.info("Loading ... ")) >>
      client.apply[Greeting].mirror { greetings =>
        client.apply[Person].mirror { people =>
          IO(logger.info("Running ... ")) >>
            op(greetings, people)
        }
      }
  }
}

class Mutator[C](client: C, greetings: ResourceMirror[IO, Greeting], people: ResourceMirror[IO, Person])
  (implicit
    ep: Engine[IO, C, Person],
    eg: Engine[IO, C, Greeting],
  ) extends Logging {

  import Mutator._

  def run: IO[Unit] = {
    List(
      watchResource(people),
      watchResource(greetings),
      runRepl
    ).parSequence_
  }

  def rootDecision(random: Random, peopleUnsorted: List[Person], greetingsUnsorted: List[Greeting]): Decision = {
    // explicitly sort so that using the same random seed will yield the same result
    val people = peopleUnsorted.sortBy(_.name)
    val greetings = greetingsUnsorted.sortBy(_.name)
    // these shouldn't really be implicit since they carry state, but it's convenient
    implicit val personOps: Operations[IO, C, Person] = new Operations[IO, C, Person](client)
    implicit val greetingOps: Operations[IO, C, Greeting] = new Operations[IO, C, Greeting](client)

    val deletePerson = Decision(people)(person => Decision(Delete(person)))
    val deleteGreeting = Decision(greetings)(greeting => Decision(Delete(greeting)))

    val firstNames = List("Lachy", "Emma", "Simon", "Anthony", "Wags", "Captain", "Dorothy")
    val surnames = List("Wiggle", "Henri", "Coombe", "Smith")

    val createPerson = Decision(firstNames) { firstName =>
      Decision(surnames) { surname =>
        val spec = PersonSpec(firstName = firstName, surname = surname)
        Decision(Create(random, CustomResource(spec)))
      }
    }

    val createSingleGreeting = Decision(firstNames) { name =>
      val spec = GreetingSpec(name = Some(name), surname = None)
      Decision(Create(random, CustomResource(spec)))
    }

    val createFamilyGreeting = Decision(surnames) { name =>
      val spec = GreetingSpec(name = None, surname = Some(name))
      Decision(Create(random, CustomResource(spec)))
    }

    val modifyPerson = Decision(people) { person =>
      val changeSurname = Decision(surnames) { surname =>
        Decision(Modify.spec(person, person.spec.copy(surname = surname)))
      }
      val changeFirstName = Decision(firstNames) { firstName =>
        Decision(Modify.spec(person, person.spec.copy(firstName = firstName)))
      }
      Decision.eager(changeFirstName, changeSurname)
    }

    val modifyGreeting = Decision(greetings) { greeting =>
      val changeSurname = Decision(surnames.map(Some.apply) ++ List(None)) { surname =>
        Decision(Modify.spec(greeting, greeting.spec.copy(surname = surname)))
      }
      val changeName = Decision(firstNames.map(Some.apply) ++ List(None)) { name =>
        Decision(Modify.spec(greeting, greeting.spec.copy(name = name)))
      }
      Decision.eager(changeName, changeSurname)
    }

    Decision.eager(
      createPerson,
      createFamilyGreeting,
      createSingleGreeting,
      modifyPerson,
      modifyGreeting,
      deletePerson,
      deleteGreeting,
    )
  }

  private def dropNamespaceFromKey[T](map: ResourceMirror.ResourceMap[T]): Map[String, ResourceState[T]] = {
    map.map { case (k,v) => (k.name, v) }
  }

  def nextActions(random: Random, count: Int, filter: Action => Boolean): IO[List[Action]] = {
    // Picks `count` actions, ensuring that all `conflictKeys` are distinct (i.e. the actions occur on different objects)
    def pick(existing: List[Action]): IO[List[Action]] = {
      if (existing.size == count) {
        IO.pure(existing)
      } else {
        val conflicts = existing.flatMap(_.conflictKey)
        nextAction(random, { action =>
          filter(action) && (!action.conflictKey.exists(conflicts.contains))
        }).flatMap { newAction =>
          pick(newAction  :: existing)
        }
      }
    }
    pick(Nil)
  }

  def nextAction(random: Random, filter: Action => Boolean): IO[Action] = {
    def pick(root: Decision, limit: Int): IO[Action] = {
      if (limit <= 0) {
        IO.pure(Noop)
      } else {
        Decision.randomAction(random, root).flatMap { action =>
          if (action == Noop || !filter(action)) {
            pick(root, limit-1)
          } else {
            IO.pure(action)
          }
        }
      }
    }

    for {
      allPeople <- people.active.map(_.values.toList)
      allGreetings <- greetings.active.map(_.values.toList)
      root = rootDecision(random, allPeople, allGreetings)
      action <- pick(root, 100)
    } yield action
  }

  def stateValidator = {
    for {
      peopleMap <- people.all.map(dropNamespaceFromKey)
      greetingsMap <- greetings.all.map(dropNamespaceFromKey)
    } yield new StateValidator(peopleMap, greetingsMap)
  }

  def runRepl: IO[Unit] = {
    def doSomething(filter: Action => Boolean): IO[Unit] = {
      nextAction(Random, filter).flatMap { action =>
        logger.info(s"Running: ${prettyPrintAction.pretty(action)}")
        action.run.attempt
      }.flatMap {
        case Right(_) => {
          IO.sleep(FiniteDuration(200, TimeUnit.MILLISECONDS)) >>
            stateValidator.flatMap(_.report(verbose = false))
        }
        case Left(err) => IO(logger.warn(s"^^^ Failed: $err"))
      }
    }

    // for some insane reason it's impossible to interrupt stdin.read,
    // so just burn down the world and hope nothing else was running :shrug:
    // See https://github.com/monix/monix/issues/1242
    val killOnCancel = IO.never.onCancel(IO(Runtime.getRuntime.halt(1)))

    val prompt = IO(logger.info("--- ready (press return to mutate) ---"))
    val lines = fs2.io.stdin[IO](1).through(text.utf8.decode).through(text.lines)

    val consume = Stream.repeatEval(prompt).interleave(
      lines.evalMap {
        case "d" => doSomething(_.isInstanceOf[Delete[_]])
        case "m" => doSomething(_.isInstanceOf[Modify[_,_]])
        case "c" => doSomething(_.isInstanceOf[Create[_]])
        case "" => doSomething(_ => true)
        case " " => stateValidator.flatMap(_.report(verbose = true))
        case other => IO(logger.error(s"Unknown command: ${other}, try c/m/d"))
      }.evalMap(_ => IO(println("\n\n\n")))
    ).compile.drain
    List(killOnCancel, consume).parSequence_
  }

  private def watchResource[T](mirror: ResourceMirror[IO, T])(implicit res: ObjectResource[T], pp: PrettyPrint[T]): IO[Unit] = {
    val logId = s"${res.kindDescription}"
    mirror.all.map(_.size).flatMap { initialItems =>
      mirror.ids.drop(initialItems.toLong).evalMap { id =>
        mirror.all.flatMap { all =>
          val desc = all.get(id) match {
            case None => "[deleted]"
            case Some(ResourceState.Active(resource)) => pp.pretty(resource)
            case Some(ResourceState.SoftDeleted(resource)) => s"[finalizing] ${pp.pretty(resource)}"
          }
          IO(logger.debug(s"[$logId total:${all.size}] Saw update to ${id}: ${desc}"))
        }
      }.compile.drain
    }
  }
}

trait WithName[T] {
  def withName(value: T, name: String): T
}

