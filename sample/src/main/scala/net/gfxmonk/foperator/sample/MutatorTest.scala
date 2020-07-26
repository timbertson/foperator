package net.gfxmonk.foperator.sample

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import cats.data.Validated
import monix.eval.Task
import monix.execution.atomic.{AtomicBoolean, AtomicInt}
import monix.execution.internal.{Trampoline, TrampolineSpy}
import monix.execution.schedulers.{CanBlock, TestScheduler, TrampolineExecutionContext, TrampolineScheduler, TrampolinedRunnable}
import monix.execution.{Cancelable, ExecutionModel, Scheduler, UncaughtExceptionReporter}
import net.gfxmonk.foperator.ResourceState
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Implicits._
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.testkit.FoperatorDriver
import skuber.ObjectResource

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Random

object MutatorTest extends Logging {
  val maxSteps = 40
  val dedicatedThread = Scheduler.singleThread("initThread", daemonic = true)
  def main(args: Array[String]): Unit = {
    (args match {
      case Array() => while(true) {
        testWithSeed(System.currentTimeMillis())
      }
      case args => args.map(_.toLong).foreach(testWithSeed)
    })
  }

  def assertValid(validator: StateValidator) = {
    validator.validate match {
      case Validated.Valid(_) => Task.unit
      case Validated.Invalid(errors) => {
        Task(logger.error("Invalid state detected:")) >> validator.dumpState >> Task.raiseError(
          new AssertionError("Inconsistencies found:\n" + errors.toList.mkString("\n"))
        )
      }
    }
  }

  class MyScheduler(s: TestScheduler) extends Scheduler with Logging {
    private val tasks = new ConcurrentLinkedQueue[Runnable]()

    override def execute(command: Runnable): Unit = {
      logger.trace(s"execute: $command from ${Thread.currentThread.getName}");
      tasks.add(command)
    }

    override def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
      logger.trace("scheduleOnce");
      tasks.add(r)
      Cancelable { () =>
        logger.trace("scheduleOnce <- cancelled")
        tasks.remove(r)
      }
    }

    override def scheduleWithFixedDelay(initialDelay: Long, delay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
      logger.trace("scheduleWithFixedDelay")
      ???
    }

    override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit, r: Runnable): Cancelable = {
      logger.trace("scheduleAtFixedRate")
      ???
    }

    override def clockRealTime(unit: TimeUnit): Long =  { logger.trace("clockRealTime"); s.clockRealTime(unit) }

    override def clockMonotonic(unit: TimeUnit): Long =  { logger.trace("clockMonotonic"); s.clockMonotonic(unit) }

    override def reportFailure(t: Throwable): Unit =  { logger.trace("reportFailure"); s.reportFailure(t) }

    override def executionModel: ExecutionModel = ExecutionModel.SynchronousExecution

    override def withExecutionModel(em: ExecutionModel): Scheduler = ???

    override def withUncaughtExceptionReporter(r: UncaughtExceptionReporter): Scheduler = ???

    def tickOne(): Boolean = {
      Option(tasks.poll()).map { task =>
//        logger.trace("tickOne")
        task.run()
      }.isDefined
    }

    def tick(time: FiniteDuration = Duration.Zero): Unit = {
//      logger.trace(s"tick $time")
      while(tickOne()) {
        ()
      }
    }

    def assertEmpty(): Unit = {
      if (!tasks.isEmpty) {
        throw new AssertionError("Scheduler has tasks when it shouldn't")
      }
    }
  }

  class LoggingScheduler(s: TestScheduler, trampolineThreadLocal: Any) extends Scheduler with Logging {
    override def execute(command: Runnable): Unit = {
      val id = s.state.lastID+1
      logger.trace(s"execute[$id]: $command [${Thread.currentThread.getName} // ${Thread.currentThread.getId}]");

      command match {
        case _: TrampolinedRunnable => {
          logger.trace("[trampolined, direct execution]")
          val trampoline = trampolineThreadLocal // .get()
          logger.trace(s">trampoline ${trampoline}, ${TrampolineSpy.look(trampoline)}")
          s.execute(command)
          logger.trace(s"<trampoline ${trampoline}, ${TrampolineSpy.look(trampoline)}")
        }
        case _ => s.execute(command)
      }
      logger.trace(s"execute[$id]: resulting state: ${s.state}");
//      s.execute(new Runnable {
//        override def run(): Unit = {
////          logger.trace(s"[$id]: start")
//          try {
//            command.run()
//          } finally {
////            logger.trace(s"[$id]: end")
//          }
//        }
//      })
    }

    override def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
      logger.trace("scheduleOnce");
      s.scheduleOnce(initialDelay, unit, r)
    }

    override def scheduleWithFixedDelay(initialDelay: Long, delay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
      logger.trace("scheduleWithFixedDelay")
      ???
    }

    override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit, r: Runnable): Cancelable = {
      logger.trace("scheduleAtFixedRate")
      ???
    }

    override def clockRealTime(unit: TimeUnit): Long =  { logger.trace("clockRealTime"); s.clockRealTime(unit) }

    override def clockMonotonic(unit: TimeUnit): Long =  { logger.trace("clockMonotonic"); s.clockMonotonic(unit) }

    override def reportFailure(t: Throwable): Unit =  { logger.trace("reportFailure"); s.reportFailure(t) }

    override def executionModel: ExecutionModel = ExecutionModel.SynchronousExecution

    override def withExecutionModel(em: ExecutionModel): Scheduler = ???

    override def withUncaughtExceptionReporter(r: UncaughtExceptionReporter): Scheduler = ???

    def tickOne(): Boolean = s.tickOne()

    def tick(time: FiniteDuration = Duration.Zero): Unit = {
      logger.trace("> tick")
      while({ logger.trace(s"Running: ${s.state.tasks.headOption}"); s.tickOne() }) {
        val state = s.state
        logger.trace(s"ticked, tasks=${state.tasks}")
      }
//      s.tick(time)
      logger.trace(s" trampoline ${trampolineThreadLocal}, ${TrampolineSpy.look(trampolineThreadLocal)}")
      logger.trace("< tick")
    }
  }

  class UnimplementedScheduler() extends Scheduler {
    override def execute(command: Runnable): Unit = ???

    override def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable = ???

    override def scheduleWithFixedDelay(initialDelay: Long, delay: Long, unit: TimeUnit, r: Runnable): Cancelable = ???

    override def scheduleAtFixedRate(initialDelay: Long, period: Long, unit: TimeUnit, r: Runnable): Cancelable = ???

    override def clockRealTime(unit: TimeUnit): Long = ???

    override def clockMonotonic(unit: TimeUnit): Long = ???

    override def reportFailure(t: Throwable): Unit = ???

    override def executionModel: ExecutionModel = ???

    override def withExecutionModel(em: ExecutionModel): Scheduler = ???

    override def withUncaughtExceptionReporter(r: UncaughtExceptionReporter): Scheduler = ???
  }

  def testWithSeed(seed: Long) = {
    val trampolineThreadLocal = {
      import scala.reflect.runtime.{universe => ru}

      val runMirror = ru.runtimeMirror(ru.getClass.getClassLoader)
      val objectDef = Class.forName("monix.execution.schedulers.TrampolineExecutionContext")
      val objectTypeModule = runMirror.moduleSymbol(objectDef).asModule
      val objectType = objectTypeModule.typeSignature

      val methodMap = objectType.members
        .filter(_.isMethod)
        .map(d => {
          d.name.toString -> d.asMethod
        })
        .toMap

      // get the scala Object
      val instance = runMirror.reflectModule(objectTypeModule).instance
      val instanceMirror = runMirror.reflect(instance)
      // get the private value
      instanceMirror.reflectMethod(methodMap("trampoline")).apply().asInstanceOf[ThreadLocal[_]].get()
    }

//    val testScheduler = new MyScheduler(TestScheduler(ExecutionModel.SynchronousExecution))
    val testScheduler = new LoggingScheduler(TestScheduler(ExecutionModel.SynchronousExecution), trampolineThreadLocal)
//    val testScheduler = TestScheduler()
    val realScheduler = Scheduler(TrampolineExecutionContext.immediate)


    val driver = new FoperatorDriver(testScheduler)
    implicit val client = driver.client

    // Scheduler juggling:
    //  - FoperatorClient operations are fully synchronous (despite retuning a Future).
    //    Whenever it schedules an update notification (for a watch), it does so
    //    by submitting to the user's scheduler (i.e. testScheduler), but it
    //    never waits on the result of such notifications.
    //
    // So, once we call an action it completes immediately, scheduling any
    // update notifications. Any further work (including invoking the client)
    // _only_ requires the TestScheduler to tick.
    // The real scheduler is only used to drive the test

    val greetings = driver.mirror[Greeting]()
    val people = driver.mirror[Person]()
    val rand = new Random(seed)

    val implicits = {
      // This is extremely silly: akka's logger setup synchronously blocks for the logger to (asynchronously)
      // respond that it's ready, but it can't because the scheduler's paused. So... we run it in a background
      // thread until akka has stopped being daft.
      val condition = AtomicBoolean(false)

//      // Thread runs until result is set
//      val th = new Thread(() => {
//        while(!condition.get()) {
//          Thread.sleep(1)
//          testScheduler.tick(1.second)
//        }
//      })
//      th.setName("hackThread")
//      th.setDaemon(true)
//      th.start()
      val runScheduler = Future {
        while(!condition.get()) {
          Thread.sleep(1)
          testScheduler.tick(1.second)
        }
      }(dedicatedThread)
      val result = SchedulerImplicits.full(testScheduler, client)
      condition.set(true)
      Await.result(runScheduler, Duration.Inf)
      result
    }

    val mutator = new Mutator(client, greetings, people)

    val checkMirrorContents: Task[Unit] = {
      def check[O<:ObjectResource](fromMirror: Iterable[ResourceState[O]], fromDriver: Iterable[ResourceState[O]]) = {
        val mirrorSorted = fromMirror.toList.sortBy(_.raw.name)
        val driverSorted = fromMirror.toList.sortBy(_.raw.name)
        if (mirrorSorted != driverSorted) {
          Task.raiseError(new AssertionError(s"Mismatch:\nwanted: $driverSorted\ngot:    $mirrorSorted"))
        } else Task.unit
      }

      for {
        allGreetings <- greetings.all
        _ <- check(allGreetings.values, driver.list[Greeting])
        allPeople <- people.all
        _ <- check(allPeople.values, driver.list[Person])
      } yield ()
    }

    def loop(stepNo: Int): Task[Unit] = {
      if (stepNo >= maxSteps) {
        Task(logger.info("Loop completed successfully"))
      } else {
        (for {
          action <- mutator.nextAction(rand, _ => true)
          _ <- Task(logger.info(s"Running step #${stepNo} (seed: $seed) ${action}"))
          _ <- action.run
          _ <- Task(logger.info(s"Ticking..."))

          // We tick on a dedicated thread, because otherwise the tick() call is happening on the main thread,
          // which is also servicing trampolined executions for the TestScheduler.
          // tl;dr if you do that, your `tick()` can return before it's actually finished running
          // trampolined thunks x_x
          _ <- Task(testScheduler.tick(10.seconds)).executeOn(dedicatedThread)
          _ <- Task(logger.info(s"Checking consistency..."))
          _ <- checkMirrorContents
          validator <- mutator.stateValidator
//          _ <- validator.dumpState
          _ <- assertValid(validator)
        } yield ()).flatMap { _ =>
//                    testScheduler.assertEmpty()
          loop(stepNo + 1)
        }
      }
    }

    // Start main eagerly, and make sure it lives on the testScheduler. Once started, tick() to ensure
    // it's set up all its wachers etc
    val main = new AdvancedOperator(implicits).runWith(greetings, people).runToFuture(testScheduler)
    testScheduler.tick()

    Task.race(
      Task.fromFuture(main).flatMap(_ => Task.raiseError(new AssertionError("main exited prematurely"))),
      loop(1)
    ).void.runSyncUnsafe()(realScheduler, implicitly[CanBlock])
  }
}
