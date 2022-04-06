package foperator.backend

import _root_.skuber
import _root_.skuber.api.client.{EventType, KubernetesClient, LoggingConfig, WatchEvent}
import _root_.skuber.json.format.ListResourceFormat
import _root_.skuber.{HasStatusSubresource, LabelSelector, ResourceDefinition}
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import cats.data.NonEmptyList
import cats.effect.{Async, Resource}
import cats.implicits._
import com.typesafe.config.{Config, ConfigFactory}
import foperator._
import foperator.internal.{IOUtil, Logging}
import foperator.types._
import fs2.interop.reactivestreams._
import play.api.libs.json.Format

import scala.jdk.CollectionConverters._
import java.nio.file.Paths
import scala.concurrent.ExecutionContext

class Skuber[IO[_]]
  (val underlying: KubernetesClient, val actorSystem: ActorSystem)
    (implicit io: Async[IO])
  extends Client[IO, Skuber[IO]] {
  override def apply[T]
    (implicit e: Engine[IO, Skuber[IO], T], res: ObjectResource[T]): Operations[IO, Skuber[IO], T]
    = new Operations[IO, Skuber[IO], T](this)
}

object Skuber {
  def apply[IO[_]: Async] = new Companion[IO]

  class Companion[IO[_]](implicit io: Async[IO]) extends Client.Companion[IO, Skuber[IO]] {
    def wrap(client: KubernetesClient, actorSystem: ActorSystem): Skuber[IO] =
      new Skuber(client, actorSystem)

    def default(executionContext: ExecutionContext = ExecutionContext.global): Resource[IO, Skuber[IO]] = {
      for {
        config <- Resource.eval(io.delay(ConfigFactory.load()))
        actorSystem <- actorSystem(config, executionContext)
        configPath <- Resource.eval(KubeconfigPath.fromEnv[IO])
        k8sConfig <- Resource.eval(io.fromTry(skuber.api.Configuration.parseKubeconfigFile(Paths.get(configPath))))
        client <- Resource.make(io.delay {
          val client = skuber.api.client.init(k8sConfig.currentContext, LoggingConfig())(actorSystem)
          new Skuber(client, actorSystem)
        })(skuber => io.delay(skuber.underlying.close))
      } yield client
    }

    def actorSystem(config: Config, scheduler: ExecutionContext): Resource[IO, ActorSystem] = {
      Resource.make(io.delay {
        ActorSystem(
          name = "foperatorActorSystem",
          config = Some(overrideConfig(config)),
          classLoader = None,
          defaultExecutionContext = Some[ExecutionContext](scheduler)
        )
      })(sys => IOUtil.deferFuture(sys.terminate()).void)
    }
  }

  implicit def engine[IO[_]: Async, T<:skuber.ObjectResource]
    (implicit rd: skuber.ResourceDefinition[T], fmt: Format[T])
    : Engine[IO, Skuber[IO], T]
    = new EngineImpl[IO, T]

  def overrideConfig(config: Config) = configOverrides.withFallback(config)

  private val configOverrides: Config = ConfigFactory.parseMap(Map[String, Any](
    "akka.daemonic" -> true, // ugh `false` is such a rude default
    "akka.loggers" -> List("akka.event.slf4j.Slf4jLogger").asJava,
    "akka.logging-filter" -> "akka.event.slf4j.Slf4jLoggingFilter",
  ).asJava)

  private class EngineImpl[IO[_], T<: skuber.ObjectResource](
    implicit rd: ResourceDefinition[T],
    io: Async[IO],
    fmt: Format[T],
  ) extends Engine[IO, Skuber[IO], T] with Logging {
    override def classifyError(e: Throwable): ClientError = e match {
      case err: skuber.K8SException if err.status.code.contains(409) => ClientError.VersionConflict(e)
      case err: skuber.K8SException if err.status.code.contains(404) => ClientError.NotFound(e)
      case _ => ClientError.Unknown(e)
    }

    override def read(c: Skuber[IO], t: Id[T]): IO[Option[T]] =
      IOUtil.deferFuture(c.underlying.usingNamespace(t.namespace).getOption(t.name))

    override def create(c: Skuber[IO], t: T): IO[Unit] =
      IOUtil.deferFuture(c.underlying.create(t)).void

    override def update(c: Skuber[IO], t: T): IO[Unit] =
      IOUtil.deferFuture(c.underlying.update(t)).void

    override def updateStatus[St](c: Skuber[IO], t: T, st: St)(implicit sub: HasStatus[T, St]): IO[Unit] = {
      // we assume that HasStatus corresponds to substatus
      implicit val skuberStatus: HasStatusSubresource[T] = new skuber.HasStatusSubresource[T] {}
      IOUtil.deferFuture(c.underlying.updateStatus(sub.withStatus(t, st))).void
    }

    override def delete(c: Skuber[IO], id: Id[T]): IO[Unit] =
      IOUtil.deferFuture(c.underlying.usingNamespace(id.namespace).delete(id.name))

    override def listAndWatch(c: Skuber[IO], opts: ListOptions): IO[(List[T], fs2.Stream[IO, Event[T]])] = {
      implicit val lrf: Format[skuber.ListResource[T]] = ListResourceFormat[T]
      implicit val sys: ActorSystem = c.actorSystem

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

      IOUtil.deferFuture(namespaced.listWithOptions[skuber.ListResource[T]](listOptions)).map { listResource =>
        val source = namespaced.watchWithOptions[T](listOptions.copy(
          resourceVersion = Some(listResource.resourceVersion),
          timeoutSeconds = Some(30) // TODO configurable?
        ))
        logger.debug(s"ResourceMirror[${rd.spec.names.kind}] in sync, watching for updates")
        val updates = fromPublisher[IO, WatchEvent[T]](source.runWith(Sink.asPublisher(fanout = false)), 1)
          .evalMap[IO, Event[T]] { e =>
            e._type match {
              case EventType.ADDED | EventType.MODIFIED => io.pure(Event.Updated(e._object))
              case EventType.DELETED => io.pure(Event.Deleted(e._object))
              case EventType.ERROR | _ => io.raiseError[Event[T]](new RuntimeException(s"Error watching resources: $e"))
            }
          }
        (listResource.items, updates)
      }
    }
  }
}


