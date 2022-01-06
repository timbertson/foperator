package net.gfxmonk.foperator.sample.mutator

import cats.Eq
import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.subjects.ConcurrentSubject
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Models.{Greeting, GreetingSpec, Person, PersonSpec}
import net.gfxmonk.foperator.sample.PrettyPrint.Implicits._
import net.gfxmonk.foperator.sample.{AdvancedOperator, PrettyPrint, SimpleOperator}
import net.gfxmonk.foperator.skuberengine.Skuber
import net.gfxmonk.foperator.skuberengine.implicits._
import net.gfxmonk.foperator.types.{Engine, ObjectResource}
import skuber.CustomResource

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Random, Success}

object Simple extends TaskApp with Logging {
  override def run(args: List[String]): Task[ExitCode] = {
    val client = Skuber()
    val simple = new SimpleOperator(client)
    simple.install >> Mutator.withResourceMirrors(client) { (greetings, people) =>
      Task.parZip2(
        client.ops[Greeting].runReconcilerWithInput(greetings, SimpleOperator.reconciler),
        new Mutator(client, greetings, people).run
      ).void
    }
  }.map(_ => ExitCode.Success)
}

object Advanced extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = {
    val client = Skuber()
    val advanced = new AdvancedOperator(client)
    advanced.install >> Mutator.withResourceMirrors(client) { (greetings, people) =>
      Task.parZip2(
        advanced.runWith(greetings, people),
        new Mutator(client, greetings, people).run
      ).void
    }
  }.map(_ => ExitCode.Success)
}

object Standalone extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = {
    val client = Skuber()
    Mutator.withResourceMirrors(client) { (greetings, people) =>
      new Mutator(client, greetings, people).run
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

    def randomAction(random: Random, decision: Decision): Task[Action] = {
      def nextInt(limit: Int) = Task(random.nextInt(limit))

      decision match {
        case Concrete(action) => Task.pure(action)
        case choice: Choice[_] => {
          if (choice.isEmpty) {
            Task.pure(Noop)
          } else {
            nextInt(choice.inputs.length).map(choice.get).flatMap(randomAction(random, _))
          }
        }
      }
    }
  }

  sealed trait Action {
    def conflictKey: Option[Id[_]]
    def run: Task[Unit]
    def pretty: String
  }

  case object Noop extends Action {
    override def run: Task[Unit] = Task.raiseError(new RuntimeException("No action found (reached a branch with no children)"))

    override def pretty: String = "Noop"

    override def conflictKey: Option[Id[_]] = None
  }

  case class Delete[T](resource: T)(implicit rd: ObjectResource[T], ops: Operations[Task, _, T])
    extends Action
  {
    override def run: Task[Unit] = ops.delete(Id.of(resource))

    override def pretty: String = s"Delete[${rd.kind}](${rd.id(resource)})"

    override def conflictKey: Option[Id[_]] = Some(Id.of(resource))
  }

  case class Create[T](random: Random, resource: T)
    (implicit ops: Operations[Task, _, T], res: ObjectResource[T], n: WithName[T]) extends Action
  {
    def pretty: String = s"Create[${res.kind}]"

    private def randomId: Task[String] = {
      Task(random.nextBytes(4)).map { bytes =>
        bytes.map(b => String.format("%02X", b & 0xff)).mkString.toLowerCase
      }
    }

    private def namedResource = randomId.map(id => n.withName(resource, id))

    override def run: Task[Unit] = {
      namedResource.flatMap { resource =>
        ops.write(resource).void
      }
    }

    override def conflictKey: Option[Id[_]] = None
  }

  case class Modify[Sp,St] private (initial: CustomResource[Sp,St], newSpec: Sp)(
    implicit res: ObjectResource[CustomResource[Sp, St]],
    ops: Operations[Task, _, CustomResource[Sp,St]],
    pp: PrettyPrint[Sp]
  ) extends Action {
    override def run: Task[Unit] = ops.write(initial.copy(spec=newSpec)).void

    def pretty: String = s"Modify[${res.kind}](${res.id(initial)}, ${pp.pretty(newSpec)})"

    override def conflictKey: Option[Id[_]] = Some(Id.of(initial))
  }

  object Modify {
    def spec[Sp,St](initial: CustomResource[Sp,St], newSpec: Sp)(
      implicit res: ObjectResource[CustomResource[Sp,St]],
      ops: Operations[Task, _, CustomResource[Sp,St]],
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
  def withResourceMirrors[T](client: Skuber)(op: (ResourceMirror[Task, Greeting], ResourceMirror[Task, Person]) => Task[T]): Task[T] = {
    Task(logger.info("Loading ... ")) >>
      client.ops[Greeting].mirror { greetings =>
        client.ops[Person].mirror { people =>
          Task(logger.info("Running ... ")) >>
            op(greetings, people)
        }
      }
  }
}

class Mutator[C](client: C, greetings: ResourceMirror[Task, Greeting], people: ResourceMirror[Task, Person])
  (implicit
    ep: Engine[Task, C, Person],
    eg: Engine[Task, C, Greeting],
  ) extends Logging {

  import Mutator._

  def rootDecision(random: Random, peopleUnsorted: List[Person], greetingsUnsorted: List[Greeting]): Decision = {
    // explicitly sort so that using the same random seed will yield the same result
    val people = peopleUnsorted.sortBy(_.name)
    val greetings = greetingsUnsorted.sortBy(_.name)
    // these shouldn't really be implicit since they carry state, but it's convenient
    implicit val personOps: Operations[Task, C, Person] = new Operations[Task, C, Person](client)
    implicit val greetingOps: Operations[Task, C, Greeting] = new Operations[Task, C, Greeting](client)

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

  def nextActions(random: Random, count: Int, filter: Action => Boolean): Task[List[Action]] = {
    // Picks `count` actions, ensuring that all `conflictKeys` are distinct (i.e. the actions occur on different objects)
    def pick(existing: List[Action]): Task[List[Action]] = {
      if (existing.size == count) {
        Task.pure(existing)
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

  def nextAction(random: Random, filter: Action => Boolean): Task[Action] = {
    def pick(root: Decision, limit: Int): Task[Action] = {
      if (limit <= 0) {
        Task.pure(Noop)
      } else {
        Decision.randomAction(random, root).flatMap { action =>
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

  def runRepl: Task[Unit] = {
    // TODO figure out a less terrible solution: https://github.com/monix/monix/issues/1242
    val globalScheduler = Scheduler.global
    val threadScheduler = Scheduler.singleThread("stdin", daemonic = true)
    val getThread = Task {
      Thread.currentThread
    }.executeOn(threadScheduler)

    val lines = ConcurrentSubject[String](MulticastStrategy.publish)(globalScheduler)
    val readLoop = getThread.flatMap { thread =>
      Task {
        logger.info("--- ready (press return to mutate) ---")
        while(true) {
          scala.io.Source.stdin.getLines().foreach { line =>
            lines.onNext(line)
          }
          lines.onComplete()
        }
      }.executeOn(threadScheduler).doOnCancel(Task {
        println("Cancelling ...")
        thread.interrupt()
      }.executeOn(globalScheduler))
    }

    def doSomething(filter: Action => Boolean): Task[Unit] = {
      nextAction(Random, filter).flatMap { action =>
        logger.info(s"Running: ${prettyPrintAction.pretty(action)}")
        action.run.materialize
      }.flatMap {
        case Success(_) => {
          Task.sleep(FiniteDuration(200, TimeUnit.MILLISECONDS)) >>
            stateValidator.flatMap(_.report(verbose = false))
        }
        case Failure(err) => Task(logger.warn(s"^^^ Failed: $err"))
      }
    }

    val consume = lines.mapEval {
      case "d" => doSomething(_.isInstanceOf[Delete[_]])
      case "m" => doSomething(_.isInstanceOf[Modify[_,_]])
      case "c" => doSomething(_.isInstanceOf[Create[_]])
      case "" => doSomething(_ => true)
      case " " => stateValidator.flatMap(_.report(verbose = true))
      case other => {
        Task(logger.error(s"Unknown command: ${other}, try c/m/d"))
      }
    }.mapEval(_ => Task(println("\n\n\n"))).completedL

    Task.parZip2(readLoop, consume).void
  }

  def run: Task[ExitCode] = {
    Task.parZip3(
      watchResource(people),
      watchResource(greetings),
      runRepl
    ).map(_ => ExitCode.Success)
  }

  private def watchResource[T](mirror: ResourceMirror[Task, T])(implicit res: ObjectResource[T], pp: PrettyPrint[T]): Task[Unit] = {
    val logId = s"${res.kind}"
    mirror.all.map(_.size).flatMap { initialItems =>
      mirror.ids.drop(initialItems.toLong).evalMap { id =>
        mirror.all.flatMap { all =>
          val desc = all.get(id) match {
            case None => "[deleted]"
            case Some(ResourceState.Active(resource)) => pp.pretty(resource)
            case Some(ResourceState.SoftDeleted(resource)) => s"[finalizing] ${pp.pretty(resource)}"
          }
          Task(logger.debug(s"[$logId total:${all.size}] Saw update to ${id}: ${desc}"))
        }
      }.compile.drain
    }
  }
}

trait WithName[T] {
  def withName(value: T, name: String): T
}

