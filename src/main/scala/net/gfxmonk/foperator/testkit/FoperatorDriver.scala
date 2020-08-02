package net.gfxmonk.foperator.testkit

import java.time.{Clock, ZonedDateTime}
import java.util.concurrent.ConcurrentHashMap

import akka.NotUsed
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import cats.implicits._
import monix.eval.Task
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{MulticastStrategy, Observable}
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.{FoperatorContext, Id, ResourceMirror, ResourceMirrorImpl, ResourceState}
import play.api.libs.json.{Format, JsArray, JsNull, JsObject, JsString, Writes}
import skuber.api.client
import skuber.api.client.{EventType, KubernetesClient, LoggingConfig, WatchEvent}
import skuber.api.patch.Patch
import skuber.{CustomResource, K8SException, LabelSelector, ObjectResource, Pod, ResourceDefinition, Scale}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

class FoperatorDriver[T](userScheduler: Scheduler) {
  private val actorSystem = FoperatorContext.actorSystem(userScheduler)

  private val mat = ActorMaterializer()(actorSystem)

  val client: FoperatorClient = new FoperatorClient(userScheduler, mat)

  val context: FoperatorContext = FoperatorContext(userScheduler, client=Some(client))

  def mirror[O<:ObjectResource]()(implicit rd: ResourceDefinition[O]): Task[ResourceMirror[O]] = {
    client.mirror[O]()
  }

  def list[O<:ObjectResource](implicit rd: ResourceDefinition[O]): Iterable[ResourceState[O]] = {
    client.resourceSet(rd).values.asScala.map(ResourceState.of)
  }

  def subject[O<:ObjectResource](implicit rd: ResourceDefinition[O]): ConcurrentSubject[WatchEvent[O],WatchEvent[O]] = {
    client.subject(rd)
  }
}

object FoperatorDriver {
  def apply(userScheduler: Scheduler) = new FoperatorDriver(userScheduler)
}

object FoperatorClient {
  type ResourceMap[T] = ConcurrentHashMap[ResourceDefinition[_], T]
  type ResourceSet[T] = ConcurrentHashMap[Id[T], T]
}

// Just enough skuber to satisfy the needs of a typical foperator usage
class FoperatorClient(userScheduler: Scheduler, materializer: Materializer) extends KubernetesClient with Logging {
  import FoperatorClient._
  private val state: ResourceMap[ResourceSet[_]] = new ConcurrentHashMap[ResourceDefinition[_], ResourceSet[_]]()
  private val subjects: ResourceMap[ConcurrentSubject[_,_]] = new ConcurrentHashMap[ResourceDefinition[_], ConcurrentSubject[_,_]]()

  override def close: Unit = ()

  override val logConfig: LoggingConfig = LoggingConfig()
  override val clusterServer: String = "localhost"
  override val namespaceName: String = "default"

  private [testkit] def mirror[O<:ObjectResource]()(implicit rd: ResourceDefinition[O]): Task[ResourceMirror[O]] = {
    implicit val s: Scheduler = userScheduler
    val underlying = subject(rd)
    val sub = new Observable[WatchEvent[O]] {
      override def unsafeSubscribeFn(subscriber: Subscriber[WatchEvent[O]]): Cancelable = {
        logger.trace(s"Subscribed: ${rd.spec.names.kind}")
        val c = underlying.unsafeSubscribeFn(subscriber)
        Cancelable { () =>
          logger.trace(s"Unsubscribed: ${rd.spec.names.kind}")
          c.cancel()
        }
      }
    }
    ResourceMirrorImpl[O](Nil, sub)
  }

  private [testkit] def resourceSet[T<:ObjectResource](rd: ResourceDefinition[T]): ResourceSet[T] = {
    state.putIfAbsent(rd, new ConcurrentHashMap[ResourceDefinition[T], ResourceSet[T]]().asInstanceOf[ResourceSet[_]])
    // cast should be safe since it's keyed on ResourceDefinition
    (state.get(rd): ResourceSet[_]).asInstanceOf[ResourceSet[T]]
  }

  private [testkit] def subject[O<: ObjectResource](rd: ResourceDefinition[O]): ConcurrentSubject[WatchEvent[O],WatchEvent[O]] = {
    subjects.computeIfAbsent(rd, _ => {
      logger.trace(s"Creating ConcurrentSubject for ${rd.spec.names.kind}")
      ConcurrentSubject(MulticastStrategy.replayLimited(10))(userScheduler)
    })
    (subjects.get(rd): ConcurrentSubject[_,_]).asInstanceOf[ConcurrentSubject[WatchEvent[O],WatchEvent[O]]]
  }

  private def emitAndForget[O<: ObjectResource](event: WatchEvent[O])(implicit rd: ResourceDefinition[O]): Unit = {
    logger.debug(s"Emitting: ${event}")
    subject(rd).onNext(event).onComplete {
      case Success(Ack.Stop) => logger.warn("Ignoring Ack.Stop")
      case Success(Ack.Continue) => ()
      case Failure(t) => logger.error("uncaught failure from emit", t)
    }(ExecutionContext.parasitic)
  }

  private def getId[O <: skuber.ObjectResource](id: Id[O])(implicit rd: ResourceDefinition[O]): Option[O] = {
    Option(resourceSet(rd).get(id))
  }

  private def notFound: Future[Nothing] = {
    Future.failed(new K8SException(skuber.api.client.Status(code = Some(404), status = Some(s"Not found"))))
  }

  private def requireOpt[T](t: Option[T]): Future[T] = {
    t.map(Future.successful).getOrElse(notFound)
  }

  private def conflict: Future[Nothing] = {
    Future.failed(new K8SException(skuber.api.client.Status(code = Some(409), status = Some(s"Conflict"))))
  }

  override def get[O <: skuber.ObjectResource](name: String)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] =
    getOption(name).flatMap(requireOpt)(ExecutionContext.parasitic)

  override def getOption[O <: skuber.ObjectResource](name: String)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Option[O]] =
    Future.successful(getId(Id.createUnsafe[O]("default", name)))

  override def getInNamespace[O <: skuber.ObjectResource](name: String, namespace: String)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] =
    requireOpt(getId(Id.createUnsafe[O](namespace, name)))

  private def bumpResourceVersion[O <: skuber.ObjectResource](obj: O): O = {
    val cr = obj.asInstanceOf[CustomResource[_,_]]
    val meta = obj.metadata
    val version = if (meta.resourceVersion == "") 0 else Integer.parseInt(meta.resourceVersion)
    val namespace = if (meta.namespace == "") "default" else meta.namespace
    cr.withMetadata(meta.copy(
      resourceVersion = (version + 1).toString,
      namespace = namespace
    )).asInstanceOf[O]
  }

  private def softDelete[O <: skuber.ObjectResource](obj: O): O = {
    val cr = obj.asInstanceOf[CustomResource[_,_]]
    val meta = obj.metadata
    cr.withMetadata(
      meta.copy(deletionTimestamp = Some(meta.deletionTimestamp.getOrElse(ZonedDateTime.now(Clock.systemUTC()))))
    ).asInstanceOf[O]
  }

  private def startDeletion[O <: skuber.ObjectResource](obj: O): Option[O] = {
    if (obj.metadata.finalizers.exists(_.nonEmpty)) {
      // soft delete
      Some(softDelete(obj))
    } else {
      None
    }
  }

  override def create[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] = {
    val updated = bumpResourceVersion(obj)
    state.synchronized {
      Option(resourceSet(rd).putIfAbsent(Id.of(updated), updated)) match {
        case Some(_) => conflict
        case None => {
          emitAndForget(WatchEvent(EventType.ADDED, updated))
          Future.successful(obj)
        }
      }
    }
  }

  def updateUnsynchronized[O <: skuber.ObjectResource](obj: O)(implicit rd: ResourceDefinition[O]): Future[O] = {
    val res = resourceSet(rd)
    val id = Id.of(obj)
    Option(res.get(id)) match {
      case Some(existing) => if (existing.resourceVersion === obj.metadata.resourceVersion) {
        val updated = bumpResourceVersion(obj)
        if (obj.metadata.deletionTimestamp.isDefined && obj.metadata.finalizers.getOrElse(Nil).isEmpty) {
          // If we've removed the last finalizer, hard delete it instead of updating.
          // K8s might do this asynchronously, but the framework currently doesn't care and this makes tests easier
          // if we delete synchronously
          hardDeleteUnsynchronized(updated)
            .map(_ => updated)(ExecutionContext.parasitic) // NOTE ignores Ack.Stop
        } else {
          res.put(id, updated)
          emitAndForget(WatchEvent(EventType.MODIFIED, updated))
          Future.successful(updated)
        }
      } else conflict
      case None => notFound
    }
  }

  override def update[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] = {
    state.synchronized {
      updateUnsynchronized(obj)
    }
  }

  private def hardDeleteUnsynchronized[O <: skuber.ObjectResource](obj: O)(implicit rd: ResourceDefinition[O]): Future[Unit] = {
    val res = resourceSet(rd) // TODO pass in?
    res.remove(Id.of(obj))
    emitAndForget(WatchEvent(EventType.DELETED, obj))
    Future.unit
  }

  override def delete[O <: skuber.ObjectResource](name: String, gracePeriodSeconds: Int)(implicit rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Unit] = {
    state.synchronized {
      val res = resourceSet(rd)
      val id = Id.createUnsafe(namespaceName, name)
      Option(res.get(id)) match {
        case None => notFound
        case Some(current) => {
          startDeletion(current) match {
            case None => hardDeleteUnsynchronized(current)
            case Some(softDeleted) =>
              updateUnsynchronized(softDeleted).map(_ => ())(ExecutionContext.parasitic)
          }
        }
      }
    }
  }

  override def deleteWithOptions[O <: skuber.ObjectResource](name: String, options: skuber.DeleteOptions)(implicit rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Unit] = ???

  override def deleteAll[L <: skuber.ListResource[_]]()(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[L] = ???

  override def deleteAllSelected[L <: skuber.ListResource[_]](labelSelector: LabelSelector)(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[L] = ???

  override def getNamespaceNames(implicit lc: client.LoggingContext): Future[List[String]] = ???

  override def listByNamespace[L <: skuber.ListResource[_]]()(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[Map[String, L]] = ???

  override def listInNamespace[L <: skuber.ListResource[_]](theNamespace: String)(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[L] = ???

  override def list[L <: skuber.ListResource[_]]()(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[L] = {
    println("LIST ")
    println("LIST " + (rd.spec))
    val response = JsObject(Seq(
      "apiVersion" -> JsString(rd.spec.defaultVersion),
      "kind" -> JsString(rd.spec.names.kind),
      "metadata" -> JsNull,
      "items" -> JsArray(Nil),
    ))
    println("RESPO" + response)
    val result = fmt.reads(response).asEither.left.map(err => new RuntimeException(err.toString))
    println("LIST RESULT: " +  result)
    Future.fromTry(result.toTry)
  }

  override def listSelected[L <: skuber.ListResource[_]](labelSelector: LabelSelector)(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[L] = {
    list[L]
  }

  override def listWithOptions[L <: skuber.ListResource[_]](options: skuber.ListOptions)(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[L] = {
    // TODO care about listOptions
    list[L]
  }

  override def updateStatus[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], statusEv: skuber.HasStatusSubresource[O], lc: client.LoggingContext): Future[O] = {
    // TODO not implemented as a subresource, but probably should
    update(obj)
  }

  override def getStatus[O <: skuber.ObjectResource](name: String)(implicit fmt: Format[O], rd: ResourceDefinition[O], statusEv: skuber.HasStatusSubresource[O], lc: client.LoggingContext): Future[O] = ???

  override def watch[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Source[WatchEvent[O], _]] = ???

  override def watch[O <: skuber.ObjectResource](name: String, sinceResourceVersion: Option[String], bufSize: Int)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Source[WatchEvent[O], _]] = ???

  override def watchAll[O <: skuber.ObjectResource](sinceResourceVersion: Option[String], bufSize: Int)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Source[WatchEvent[O], _]] = ???

  override def watchContinuously[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Source[WatchEvent[O], _] = ???

  override def watchContinuously[O <: skuber.ObjectResource](name: String, sinceResourceVersion: Option[String], bufSize: Int)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Source[WatchEvent[O], _] = ???

  override def watchAllContinuously[O <: skuber.ObjectResource](sinceResourceVersion: Option[String], bufSize: Int)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Source[WatchEvent[O], _] = ???

  override def watchWithOptions[O <: skuber.ObjectResource](options: skuber.ListOptions, bufsize: Int)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Source[WatchEvent[O], _] = {
    // TODO care about options
    Source.fromPublisher(subject(rd).toReactivePublisher(userScheduler))
  }

  override def getScale[O <: skuber.ObjectResource](objName: String)(implicit rd: ResourceDefinition[O], sc: Scale.SubresourceSpec[O], lc: client.LoggingContext): Future[Scale] = ???

  override def updateScale[O <: skuber.ObjectResource](objName: String, scale: Scale)(implicit rd: ResourceDefinition[O], sc: Scale.SubresourceSpec[O], lc: client.LoggingContext): Future[Scale] = ???

  override def scale[O <: skuber.ObjectResource](objName: String, count: Int)(implicit rd: ResourceDefinition[O], sc: Scale.SubresourceSpec[O], lc: client.LoggingContext): Future[Scale] = ???

  override def patch[P <: Patch, O <: skuber.ObjectResource](name: String, patchData: P, namespace: Option[String])(implicit patchfmt: Writes[P], fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] = ???

  override def jsonMergePatch[O <: skuber.ObjectResource](obj: O, patch: String)(implicit rd: ResourceDefinition[O], fmt: Format[O], lc: client.LoggingContext): Future[O] = ???

  override def getPodLogSource(name: String, queryParams: Pod.LogQueryParams, namespace: Option[String])(implicit lc: client.LoggingContext): Future[Source[ByteString, _]] = ???

  override def exec(podName: String, command: Seq[String], maybeContainerName: Option[String], maybeStdin: Option[Source[String, _]], maybeStdout: Option[Sink[String, _]], maybeStderr: Option[Sink[String, _]], tty: Boolean, maybeClose: Option[Promise[Unit]])(implicit lc: client.LoggingContext): Future[Unit] = ???

  override def getServerAPIVersions(implicit lc: client.LoggingContext): Future[List[String]] = ???

  override def usingNamespace(newNamespace: String): KubernetesClient = ???
}
