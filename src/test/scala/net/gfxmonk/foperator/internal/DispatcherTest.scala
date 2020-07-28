package net.gfxmonk.foperator.internal

import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import monix.reactive.MulticastStrategy
import monix.reactive.subjects.ConcurrentSubject
import net.gfxmonk.foperator.fixture.{PermitScopeFixture, Resource}
import net.gfxmonk.foperator.{Id, Event, Reconciler, ResourceState}
import org.scalatest.funspec.AnyFunSpec

import scala.concurrent.duration._
import scala.util.Failure

class DispatcherTest extends AnyFunSpec {
  class Loop[T](
    val id: Int,
    var count: Int = 0,
    var deleted: Boolean = false,
    val onError: Throwable => Task[Unit] = _ => Task.unit
  )

  class Manager(scheduler: Scheduler) extends ResourceLoop.Manager[Loop, Resource] {
    private var nextId = 0
    var loops = List.empty[Loop[Resource]]
    val inputs = ConcurrentSubject[Event[Id[Resource]]](MulticastStrategy.publish)(scheduler)

    val dispatcher = new Dispatcher[Loop, Resource](
      reconciler = Reconciler.empty,
      getResource = _ => Task.never,
      manager = this,
      permitScope = PermitScopeFixture.empty
    )

    override def create(
      currentState: Task[Option[ResourceState[Resource]]],
      reconciler: Reconciler[ResourceState[Resource]],
      permitScope: PermitScope,
      onError: Throwable => Task[Unit]
    ): Loop[Resource] = {
      nextId += 1
      val loop = new Loop[Resource](nextId, onError = onError)
      loops = loop :: loops
      loop
    }

    override def update(loop: Loop[Resource]): Task[Unit] = Task {
      loop.count += 1
    }

    override def destroy(loop: Loop[Resource]): Task[Unit] = Task {
      loop.deleted = true
    }
  }

  val id1 = Id.createUnsafe[Resource]("default", "id1")
  val id2 = Id.createUnsafe[Resource]("default", "id2")

  it("creates, updates and deletes") {
    val testScheduler = TestScheduler()
    val manager = new Manager(testScheduler)

    Task.raceMany(List(
      manager.dispatcher.run(manager.inputs).flatMap(_ => Task.raiseError(new AssertionError("should not complete"))).executeOn(testScheduler),
      Task {
        manager.inputs.onNext(Event.Updated(id1))
        testScheduler.tick()
        assert(manager.loops.map(_.count) == List(1))

        manager.inputs.onNext(Event.Updated(id2))
        manager.inputs.onNext(Event.Updated(id1))
        testScheduler.tick()

        assert(manager.loops.map(_.count) == List(2,1))

        manager.inputs.onNext(Event.HardDeleted(id2))
        testScheduler.tick()

        assert(manager.loops.map(_.count) == List(2))
      }
    )).runToFuture(Scheduler.global)
  }

  it("aborts when onError is invoked") {
    val manager = new Manager(Scheduler.global)

    val error = new RuntimeException("test error")
    Task.race(
      manager.dispatcher.run(manager.inputs).attempt,
      Task.defer {
        manager.inputs.onNext(Event.Updated(id1))
        manager.loops.head.onError(error)
        Task.sleep(1.second)
      }
    ).map { result =>
      assert(result == Left(Failure(error)))
    }.runToFuture(Scheduler.global)
  }
}
