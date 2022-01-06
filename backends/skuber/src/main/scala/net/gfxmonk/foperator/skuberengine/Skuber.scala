package net.gfxmonk.foperator.skuberengine

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import cats.data.NonEmptyList
import com.typesafe.config.{Config, ConfigFactory}
import fs2.interop.reactivestreams._
import monix.eval.Task
import monix.eval.instances.CatsConcurrentEffectForTask
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import net.gfxmonk.foperator.internal.{BackendCompanion, Logging}
import net.gfxmonk.foperator.types._
import net.gfxmonk.foperator.{Event, Id, ListOptions, Operations}
import play.api.libs.json.Format
import skuber.api.client.{EventType, KubernetesClient, WatchEvent}
import skuber.json.format.ListResourceFormat
import skuber.{HasStatusSubresource, LabelSelector, ResourceDefinition}

import scala.concurrent.ExecutionContext

class Skuber(val underlying: KubernetesClient, val scheduler: Scheduler, val materializer: ActorMaterializer) {
  val ops = Operations[Task, Skuber](this)
}

object Skuber extends BackendCompanion[Task, Skuber] {
  type Ops[T] = Operations[Task, Skuber, T]
  import scala.jdk.CollectionConverters._

  implicit def engine[T<:skuber.ObjectResource]
    (implicit rd: skuber.ResourceDefinition[T], fmt: Format[T])
    : Engine[Task, Skuber, T]
    = new EngineImpl[T]

  def apply(
    scheduler: Scheduler = Scheduler.global,
    config: Config = ConfigFactory.load(),
    clientOverride: Option[KubernetesClient] = None,
  ): Skuber = {
    val actorSystem = Skuber.actorSystem(scheduler, config)
    val materializer: ActorMaterializer = ActorMaterializer()(actorSystem)
    val client: KubernetesClient = clientOverride.getOrElse(skuber.k8sInit(config)(actorSystem, materializer))
    new Skuber(client, scheduler, materializer)
  }

  def overrideConfig(config: Config) = configOverrides.withFallback(config)

  private val configOverrides: Config = ConfigFactory.parseMap(Map[String, Any](
    "akka.daemonic" -> true, // ugh `false` is such a rude default
    "akka.loggers" -> List("akka.event.slf4j.Slf4jLogger").asJava,
    "akka.logging-filter" -> "akka.event.slf4j.Slf4jLoggingFilter",
  ).asJava)

  def actorSystem(scheduler: Scheduler, config: Config): ActorSystem = {
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
      config = Some(overrideConfig(config)),
      classLoader = None,
      defaultExecutionContext = Some[ExecutionContext](schedulerImpl)
    )
  }

  private class EngineImpl[T<: skuber.ObjectResource](
    implicit rd: ResourceDefinition[T],
    fmt: Format[T],
  ) extends Engine[Task, Skuber, T] with Logging {
    override def classifyError(e: Throwable): ClientError = e match {
      case err: skuber.K8SException if err.status.code.contains(409) => ClientError.VersionConflict(e)
      case err: skuber.K8SException if err.status.code.contains(404) => ClientError.NotFound(e)
      case _ => ClientError.Unknown(e)
    }

    override def read(c: Skuber, t: Id[T]): Task[Option[T]] =
      Task.deferFuture(c.underlying.usingNamespace(t.namespace).getOption(t.name))

    override def write(c: Skuber, t: T): Task[Unit] =
      Task.deferFuture(c.underlying.update(t)).void

    override def writeStatus[St](c: Skuber, t: T, st: St)(implicit sub: HasStatus[T, St]): Task[Unit] = {
      // we assume that HasStatus corresponds to substatus
      implicit val skuberStatus: HasStatusSubresource[T] = new skuber.HasStatusSubresource[T] {}
      Task.deferFuture(c.underlying.updateStatus(sub.withStatus(t, st))).void
    }

    override def delete(c: Skuber, id: Id[T]): Task[Unit] =
      Task.deferFuture(c.underlying.usingNamespace(id.namespace).delete(id.name))

    override def listAndWatch(c: Skuber, opts: ListOptions): Task[(List[T], fs2.Stream[Task, Event[T]])] = {
      implicit val lrf: Format[skuber.ListResource[T]] = ListResourceFormat[T]
      implicit val mat: ActorMaterializer = c.materializer
      implicit val io: CatsConcurrentEffectForTask = Task.catsEffect(c.scheduler)

      // skuber lets you build typed requirements, but they all end up as a toString anyway.
      // the "exists" requirement performs no formatting or validation, so we can tunnel
      // anything through it
      val labelSelector = NonEmptyList.fromList(opts.labelSelector).map { l =>
        skuber.LabelSelector(l.toList.map(LabelSelector.ExistsRequirement):_*)
      }
      val fieldSelector = NonEmptyList.fromList(opts.fieldSelector).map(_.toList.mkString(","))
      val listOptions = skuber.ListOptions(
        labelSelector = labelSelector,
        fieldSelector = fieldSelector,
      )

      val namespaced = c.underlying.usingNamespace(opts.namespace)

      Task.deferFuture(namespaced.listWithOptions[skuber.ListResource[T]](listOptions)).map { listResource =>
        val source = namespaced.watchWithOptions[T](listOptions.copy(
          resourceVersion = Some(listResource.resourceVersion),
          timeoutSeconds = Some(30) // TODO configurable?
        ))
        logger.debug(s"ResourceMirror[${rd.spec.names.kind}] in sync, watching for updates")
        val updates = fromPublisher[Task, WatchEvent[T]](source.runWith(Sink.asPublisher(fanout = false)))
          .evalMap { e =>
            e._type match {
              case EventType.ADDED | EventType.MODIFIED => io.pure(Event.Updated(e._object))
              case EventType.DELETED => io.pure(Event.Deleted(e._object))
              case EventType.ERROR | _ => io.raiseError(new RuntimeException(s"Error watching resources: $e"))
            }
          }
        (listResource.items, updates)
      }
    }
  }
}


