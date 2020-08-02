package net.gfxmonk.foperator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigFactory}
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import skuber.api.client.KubernetesClient

import scala.concurrent.ExecutionContext

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

  val client: KubernetesClient = clientOverride.getOrElse(skuber.k8sInit(config)(actorSystem, materializer))
}

object FoperatorContext {
  import scala.jdk.CollectionConverters._

  def apply(scheduler: Scheduler, config: Option[Config] = None, client: Option[KubernetesClient] = None): FoperatorContext = {
    new FoperatorContext(FoperatorContext.config(config), actorSystem(scheduler, config), scheduler, client)
  }

  def config(config: Option[Config]) = configOverride.withFallback(slf4jConfigOverride).withFallback(config.getOrElse(ConfigFactory.load()))

  def actorSystem(scheduler: Scheduler, config: Option[Config] = None): ActorSystem = {
    val schedulerImpl = scheduler match {
      case _: TestScheduler => {
        // Akka is rife with Await.result() calls, which completely breaks any attempt to use a synthetic
        // scheduler. This seems like the least broken alternative.
        ExecutionContext.parasitic
      }
      case other => other
    }
    ActorSystem(
      name = "foperatorActorSystem",
      config = Some(FoperatorContext.config(config)),
      classLoader = None,
      defaultExecutionContext = Some[ExecutionContext](schedulerImpl)
    )
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
