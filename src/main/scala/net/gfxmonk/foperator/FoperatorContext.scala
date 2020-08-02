package net.gfxmonk.foperator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigFactory}
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean
import monix.execution.schedulers.TestScheduler
import skuber.api.client.KubernetesClient

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.concurrent.blocking

/**
 * Encapsulates all the dependencies needed for foperator.
 * You should also import net.gfxmonk.foperator.implicits._
 * in order to derive the various components from an
 * `implicit val _ = FoperatorContext(...)`
 *
 * Components:
 *  - ActorSystem (akka)
 *  - Materializer (akka streams)
 *  - Scheduler (monix)
 *  - KubernetesClient (skuber)
 */
class FoperatorContext private (config: Config, val actorSystem: ActorSystem, val scheduler: Scheduler, clientOverride: Option[KubernetesClient]) {
  val materializer: ActorMaterializer = ActorMaterializer()(actorSystem)

  val client: KubernetesClient = clientOverride.getOrElse(skuber.k8sInit(actorSystem, materializer))
}

object FoperatorContext {
  import scala.jdk.CollectionConverters._

  def apply(scheduler: Scheduler, config: Option[Config] = None, client: Option[KubernetesClient] = None): FoperatorContext = {
    new FoperatorContext(FoperatorContext.config(config), actorSystem(scheduler, config), scheduler, client)
  }

  def config(config: Option[Config]) = configOverride.withFallback(slf4jConfigOverride).withFallback(config.getOrElse(ConfigFactory.load()))

  def actorSystem(scheduler: Scheduler, config: Option[Config] = None): ActorSystem = {
    val implicits = {
      // This is extremely silly: akka's logger setup synchronously blocks for the logger to (asynchronously)
      // respond that it's ready, but it can't because the scheduler's paused. So... we run it in a background
      // thread until akka gets unblocked.
    }

    def create() = ActorSystem(
      name = "foperatorActorSystem",
      config = Some(FoperatorContext.config(config)),
      classLoader = None,
      defaultExecutionContext = Some[ExecutionContext](scheduler)
    )
    scheduler match {
      case testScheduler: TestScheduler => {
        // This is extremely silly: akka's logger setup synchronously blocks for the logger to (asynchronously)
        // respond that it's ready, but it can't because the scheduler's paused. So... we run it in a background
        // thread until akka gets unblocked.

        val condition = AtomicBoolean(false)
        Scheduler.global.execute(() => blocking {
          while (!condition.get()) {
            testScheduler.tick(1.second)
          }
        })
        val result = create()
        condition.set(true)
        result
      }
      case _ => create()
    }
  }

  val configOverride: Config = ConfigFactory.parseMap(Map[String, Any](
    "akka.daemonic" -> true, // ugh `false` is such a rude default
  ).asJava)

  val slf4jConfigOverride: Config = ConfigFactory.parseMap(Map[String, Any](
    "akka.loggers" -> List("akka.event.slf4j.Slf4jLogger").asJava,
    "akka.logging-filter" -> "akka.event.slf4j.Slf4jLoggingFilter",
  ).asJava)

  def global: FoperatorContext = FoperatorContext(Scheduler.global)
}
