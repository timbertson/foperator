package net.gfxmonk.foperator.sample

import java.io.{BufferedReader, InputStreamReader}

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}
import monix.reactive.Observable
import net.gfxmonk.foperator.sample.Models.{Greeting, GreetingSpec, Person, PersonSpec}
import net.gfxmonk.foperator._
import play.api.libs.json.Format
import skuber.api.client.KubernetesClient
import skuber.{CustomResource, HasStatusSubresource, ObjectResource, ResourceDefinition}

import scala.util.{Failure, Random, Success}


// Mutator is an app for randomly mutating Greeting / People objects and observing the results.
// It logs all changes to these resources, and mutates something whenever you press Return
object MutatorMain {
  import Implicits._
  object WithSimpleOperator extends TaskApp {
    override def run(args: List[String]): Task[ExitCode] = {
      SimpleMain.install() >> Task.parZip2(
        SimpleMain.run(Nil),
        runMutator
      ).map(_ => ExitCode.Success)
    }
  }

  object WithAdvancedOperator extends TaskApp {
    override def run(args: List[String]): Task[ExitCode] = {
      AdvancedMain.install() >> Task.parZip2(
        AdvancedMain.run(Nil),
        runMutator
      ).map(_ => ExitCode.Success)
    }
  }

  def runMutator: Task[ExitCode] = {
    println("Loading ... ")
    ResourceMirror.all[Greeting].use { greetings =>
      ResourceMirror.all[Person].use { people =>
        Task.parZip3(
          watchPeople(people),
          watchGreetings(greetings),
          mutate(people, greetings)
        ).map(_ => ExitCode.Success)
      }
    }
  }

  private def watchPeople(people: ResourceMirror[Person]): Task[Unit] = {
    Task.unit
  }

  private def watchGreetings(greetings: ResourceMirror[Greeting]): Task[Unit] = {
    Task.unit
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
  }
  case object Noop extends Action {
    override def run: Task[Unit] = Task.raiseError(new RuntimeException("No action found (reached a branch with no children)"))
  }

  case class Delete[T <: ObjectResource](id: Id[T])(implicit rd: ResourceDefinition[T]) extends Action {
    override def run: Task[Unit] = Task.deferFuture(client.delete(id.name))
  }

  case class Create[T <: ObjectResource](resource: T)(implicit rd: ResourceDefinition[T], fmt: Format[T]) extends Action {
    override def run: Task[Unit] = Task.deferFuture(client.create(resource)).void
  }

  case class Modify[Sp,St](update: CRUpdate[Sp,St])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient
  ) extends Action {
    override def run: Task[Unit] = Operations.apply(update).void
  }

  def rootDecision(people: List[Person], greetings: List[Greeting]): Decision = {
    val deletePerson = Decision(people)(person => Decision(Delete(Id.of(person))))
    val deleteGreeting = Decision(greetings)(greeting => Decision(Delete(Id.of(greeting))))

    val firstNames = List("a", "b", "c")
    val surnames = List("a", "b", "c")
    val createPerson = Decision(firstNames) { firstName =>
      Decision(surnames) { surname =>
        val spec = PersonSpec(firstName = firstName, surname = surname)
        Decision(Create(CustomResource(spec)))
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

    // TODO createGreeting
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
    val nextAction: Task[Action] = {
      for {
        allPeople <- people.active.map(_.values.toList)
        allGreetings <- greetings.active.map(_.values.toList)
        root = rootDecision(allPeople, allGreetings)
        action <- Decision.randomAction(root)
      } yield action
    }

    def doSomething: Task[Unit] = {
      nextAction.flatMap { action =>
        println(s"*** Running: $action")
        action.run.materialize
      }.flatMap {
        case Success(_) => Task(println("Done"))
        case Failure(err) => Task(println(s"^^^ Failed: $err")).flatMap(_ => doSomething)
      }
    }

    Observable.fromLinesReader(Task(new BufferedReader(new InputStreamReader(sys.process.stdin))))
      .mapEval(_ => doSomething)
      .completedL
  }
}
