package net.gfxmonk.foperator.sample

import cats.Eq
import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}
import monix.reactive.Observable
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Implicits._
import net.gfxmonk.foperator.sample.Models.{Greeting, GreetingSpec, Person, PersonSpec}
import play.api.libs.json.Format
import skuber.api.client.KubernetesClient
import skuber.{CustomResource, HasStatusSubresource, ObjectResource, ResourceDefinition}

import scala.util.{Failure, Random, Success}

object SimpleWithMutator extends TaskApp with Logging {
  override def run(args: List[String]): Task[ExitCode] = {
    MutatorMain.withResourceMirrors((greetings, people) => Task.parZip2(
      SimpleMain.runWith(greetings),
      MutatorMain.runMutator(greetings, people)
    ).void)
  }.map(_ => ExitCode.Success)
}

object AdvancedWithMutator extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = {
    AdvancedMain.install() >> MutatorMain.withResourceMirrors((greetings, people) => Task.parZip2(
      AdvancedMain.runWith(greetings, people),
      MutatorMain.runMutator(greetings, people)
    ).void)
  }.map(_ => ExitCode.Success)
}

// Mutator is an app for randomly mutating Greeting / People objects and observing the results.
// It logs all changes to these resources, and attempts a mutation whenever you press Return
// (Not all mutations succeed, it'll pick a new mutation on failure)
object MutatorMain extends Logging {
  import Implicits._

  // Since we want to share one mirror globally, we use this as the toplevel hook, and run
  // all mutators / operators within the `op`
  def withResourceMirrors(op: (ResourceMirror[Greeting], ResourceMirror[Person]) => Task[Unit]): Task[Unit] = {
    Task(logger.info("Installing ... ")) >>
    AdvancedMain.install() >>
    Task(logger.info("Loading ... ")) >>
    ResourceMirror.all[Greeting].use { greetings =>
      ResourceMirror.all[Person].use { people =>
        Task(logger.info("Running ... ")) >>
        op(greetings, people)
      }
    }
  }

  def runMutator(greetings: ResourceMirror[Greeting], people: ResourceMirror[Person]): Task[ExitCode] = {
    Task.parZip3(
      watchResource(people),
      watchResource(greetings),
      mutate(people, greetings)
    ).map(_ => ExitCode.Success)
  }

  private def watchResource[T<:ObjectResource](mirror: ResourceMirror[T])(implicit pp: PrettyPrint[T], rd: ResourceDefinition[T]): Task[Unit] = {
    val logId = s"${rd.spec.names.kind}"
    mirror.all.map(_.size).flatMap { initialItems =>
      mirror.ids.drop(initialItems).map {
        case Input.HardDeleted(id) => id
        case Input.Updated(id) => id
      }.mapEval { id =>
        mirror.all.flatMap { all =>
          val desc = all.get(id) match {
            case None => "[deleted]"
            case Some(ResourceState.Active(resource)) => pp.pretty(resource)
            case Some(ResourceState.SoftDeleted(resource)) => s"[finalizing] ${pp.pretty(resource)}"
          }
          Task(logger.debug(s"[$logId total:${all.size}] Saw update to ${id}: ${desc}"))
        }
      }.completedL
    }
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

    def random(limit: Int) = Task(Random.nextInt(limit))

    def randomAction(decision: Decision): Task[Action] = {
      decision match {
        case Concrete(action) => Task.pure(action)
        case choice: Choice[_] => {
          if (choice.isEmpty) {
            Task.pure(Noop)
          } else {
            random(choice.inputs.length).map(choice.get).flatMap(randomAction)
          }
        }
      }
    }
  }

  sealed trait Action {
    def run: Task[Unit]
    def pretty: String
  }

  case object Noop extends Action {
    override def run: Task[Unit] = Task.raiseError(new RuntimeException("No action found (reached a branch with no children)"))

    override def pretty: String = "Noop"
  }

  case class Delete[Sp,St](resource: CustomResource[Sp,St])(implicit rd: ResourceDefinition[CustomResource[Sp,St]], pp: PrettyPrint[CustomResource[Sp,St]]) extends Action {
    override def run: Task[Unit] = Task.deferFuture(client.delete(resource.name))

    override def pretty: String = s"Delete[${rd.spec.names.kind}](${pp.pretty(resource)})"
  }

  case class Create[Sp,St](resource: CustomResource[Sp,St])(
    implicit rd: ResourceDefinition[CustomResource[Sp,St]],
    fmt: Format[CustomResource[Sp,St]],
    pp: PrettyPrint[Sp]
  ) extends Action {
    def pretty: String = s"Create[${rd.spec.names.kind}](${pp.pretty(resource.spec)})"

    private def randomId: Task[String] = {
      Task(Random.nextBytes(4)).map { bytes =>
        bytes.map(b => String.format("%02X", b & 0xff)).mkString.toLowerCase
      }
    }

    private def namedResource = randomId.map(resource.withName)

    override def run: Task[Unit] = {
      namedResource.flatMap { resource =>
        Task.deferFuture(client.create(resource)).void
      }
    }
  }

  case class Modify[Sp,St](update: CustomResourceUpdate[Sp,St])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient,
    pp: PrettyPrint[CustomResourceUpdate[Sp,St]],
    eqSp: Eq[Sp],
    eqSt: Eq[St],
  ) extends Action {
    override def run: Task[Unit] = Operations.apply(update).void
    def pretty: String = s"Modify[${rd.spec.names.kind}](${pp.pretty(update)})"
  }

  object Modify {
    def apply[Sp,St](update: CustomResourceUpdate[Sp,St])(
      implicit fmt: Format[CustomResource[Sp,St]],
      rd: ResourceDefinition[CustomResource[Sp,St]],
      st: HasStatusSubresource[CustomResource[Sp,St]],
      client: KubernetesClient,
      pp: PrettyPrint[CustomResourceUpdate[Sp,St]],
      eqSp: Eq[Sp],
      eqSt: Eq[St],
    ): Action = {
      // override Modify constructor to return Noop when nothing is actually changing
      Update.minimal(update) match {
        case Update.Unchanged(_) => Noop
        case other => new Modify(other)
      }
    }
  }

  def rootDecision(people: List[Person], greetings: List[Greeting]): Decision = {
    val deletePerson = Decision(people)(person => Decision(Delete(person)))
    val deleteGreeting = Decision(greetings)(greeting => Decision(Delete(greeting)))

    val firstNames = List("Lachy", "Emma", "Simon", "Anthony", "Wags", "Captain", "Dorothy")
    val surnames = List("Wiggle", "Henri", "Coombe", "Smith")

    val createPerson = Decision(firstNames) { firstName =>
      Decision(surnames) { surname =>
        val spec = PersonSpec(firstName = firstName, surname = surname)
        Decision(Create(CustomResource(spec).withName("ds")))
      }
    }

    val createSingleGreeting = Decision(firstNames) { name =>
      val spec = GreetingSpec(name = Some(name), surname = None)
      Decision(Create(CustomResource(spec)))
    }

    val createFamilyGreeting = Decision(surnames) { name =>
      val spec = GreetingSpec(name = None, surname = Some(name))
      Decision(Create(CustomResource(spec)))
    }

    val modifyPerson = Decision(people) { person =>
      val changeSurname = Decision(surnames) { surname =>
        Decision(Modify(new implicits.UpdateExt(person).specUpdate(person.spec.copy(surname = surname))))
      }
      val changeFirstName = Decision(firstNames) { firstName =>
        Decision(Modify(new implicits.UpdateExt(person).specUpdate(person.spec.copy(firstName = firstName))))
      }
      Decision.eager(changeFirstName, changeSurname)
    }

    val modifyGreeting = Decision(greetings) { greeting =>
      val changeSurname = Decision(surnames.map(Some.apply) ++ List(None)) { surname =>
        Decision(Modify(new implicits.UpdateExt(greeting).specUpdate(greeting.spec.copy(surname = surname))))
      }
      val changeName = Decision(firstNames.map(Some.apply) ++ List(None)) { name =>
        Decision(Modify(new implicits.UpdateExt(greeting).specUpdate(greeting.spec.copy(name = name))))
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

  private def mutate(people: ResourceMirror[Person], greetings: ResourceMirror[Greeting]): Task[Unit] = {
    def nextAction(filter: Action => Boolean): Task[Action] = {
      def pick(root: Decision, limit: Int): Task[Action] = {
        if (limit <= 0) {
          Task.pure(Noop)
        } else {
          Decision.randomAction(root).flatMap { action =>
            if (action == Noop || !filter(action)) {
              pick(root, limit-1)
            } else {
              Task.pure(action)
            }
          }
        }
      }

      for {
        allPeople <- people.active.map(_.values.toList)
        allGreetings <- greetings.active.map(_.values.toList)
        root = rootDecision(allPeople, allGreetings)
        action <- pick(root, 100)
      } yield action
    }

    def doSomething(filter: Action => Boolean): Task[Unit] = {
      nextAction(filter).flatMap { action =>
        logger.info(s"Running: ${prettyPrintAction.pretty(action)}")
        action.run.materialize
      }.flatMap {
        case Success(_) => Task.unit
        case Failure(err) => Task(logger.warn(s"^^^ Failed: $err"))
      }
    }

    Observable.fromTask(Task {
      // DEBUGGING...
      // class BR(in: InputStreamReader) extends BufferedReader(in) {
      //   override def readLine(): String = {
      //     println(">READ")
      //     try {
      //       val result = super.readLine()
      //       println("<READ")
      //       result
      //     } finally {
      //       println("[x READ]")
      //     }
      //   }
      // }

      // We should be able to use Observable.fromLinesReader, but that
      // uses InputStreamReader.read, which can't be interrupted (even on process exit).
      // Workaround is to close the stream on exit, with:
      //   Runtime.getRuntime.addShutdownHook(new Thread(() => sys.process.stdin.close()))
      // (but scala's IO.source seems to work fine without that)
      logger.info("--- ready (press return to mutate) ---")
      Observable.fromIterator(Task(scala.io.Source.stdin.getLines()))
    }).concat.mapEval {
      case "d" => doSomething(_.isInstanceOf[Delete[_,_]])
      case "m" => doSomething(_.isInstanceOf[Modify[_,_]])
      case "c" => doSomething(_.isInstanceOf[Create[_,_]])
      case "" => doSomething(_ => true)
      case other => {
        Task(logger.error(s"Unknown command: ${other}, try c/m/d"))
      }
    }.completedL
  }
}
