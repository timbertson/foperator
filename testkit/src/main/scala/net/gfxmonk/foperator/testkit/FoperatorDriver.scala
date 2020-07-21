package net.gfxmonk.foperator.testkit

import java.util.concurrent.ConcurrentHashMap

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import cats.implicits._
import monix.execution.Scheduler
import monix.reactive.MulticastStrategy
import monix.reactive.subjects.ConcurrentSubject
import net.gfxmonk.foperator.{Id, ResourceMirror, ResourceMirrorImpl}
import play.api.libs.json.{Format, JsArray, Writes}
import skuber.api.client.{EventType, KubernetesClient, LoggingConfig, WatchEvent}
import skuber.api.patch.Patch
import skuber.api.{client, patch}
import skuber.{K8SException, LabelSelector, ListResource, ObjectResource, Pod, ResourceDefinition, Scale}

import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters._

class FoperatorDriver[T]()(implicit s: Scheduler) {
  var client: FoperatorClient = new FoperatorClient()
  def mirror[O<:ObjectResource]()(implicit rd: ResourceDefinition[O]): ResourceMirror[O] = {
    client.mirror[O]()
  }
}

object FoperatorClient {
  type ResourceMap[T] = ConcurrentHashMap[ResourceDefinition[_], T]
  type ResourceSet[T] = ConcurrentHashMap[Id[T], T]
}

class FoperatorClient()(implicit s: Scheduler) extends KubernetesClient {
  import FoperatorClient._
  private val state: ResourceMap[ResourceSet[_]] = new ConcurrentHashMap[ResourceDefinition[_], ResourceSet[_]]()
  private val subjects: ResourceMap[ConcurrentSubject[_,_]] = new ConcurrentHashMap[ResourceDefinition[_], ConcurrentSubject[_,_]]()

  override def close: Unit = ()

  override val logConfig: LoggingConfig = LoggingConfig()
  override val clusterServer: String = "localhost"
  override val namespaceName: String = "default"

  private [testkit] def mirror[O<:ObjectResource]()(implicit rd: ResourceDefinition[O]): ResourceMirror[O] = {
    new ResourceMirrorImpl[O](Nil, subject(rd))
  }

  private def resourceSet[T<:ObjectResource](rd: ResourceDefinition[T]): ResourceSet[T] = {
    state.putIfAbsent(rd, new ConcurrentHashMap[ResourceDefinition[T], ResourceSet[T]]().asInstanceOf[ResourceSet[_]])
    // cast should be safe since it's keyed on ResourceDefinition
    (state.get(rd): ResourceSet[_]).asInstanceOf[ResourceSet[T]]
  }

  private def subject[O<: ObjectResource](rd: ResourceDefinition[O]): ConcurrentSubject[WatchEvent[O],WatchEvent[O]] = {
    subjects.putIfAbsent(rd, ConcurrentSubject(MulticastStrategy.publish))
    (subjects.get(rd): ConcurrentSubject[_,_]).asInstanceOf[ConcurrentSubject[WatchEvent[O],WatchEvent[O]]]
  }

  private def getId[O <: skuber.ObjectResource](id: Id[O])(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Option[O] = {
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
    getOption(name).flatMap(requireOpt)

  override def getOption[O <: skuber.ObjectResource](name: String)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Option[O]] =
    Future.successful(getId(Id.createUnsafe[O]("default", name)))

  override def getInNamespace[O <: skuber.ObjectResource](name: String, namespace: String)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] =
    requireOpt(getId(Id.createUnsafe[O](namespace, name)))

  override def create[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] = {
    state.synchronized {
      Option(resourceSet(rd).putIfAbsent(Id.of(obj), obj)) match {
        case Some(existing) => conflict
        // TODO should set ResourceVersion
        case None => {
          subject(rd)
            .onNext(WatchEvent(EventType.ADDED, obj))
            .map(_ => obj) // NOTE ignores Ack.Stop
        }
      }
    }
  }

  def updateUnsynchronized[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] = {
    val res = resourceSet(rd)
    val id = Id.of(obj)
    Option(res.get(id)) match {
      case Some(existing) => if (existing.resourceVersion === obj.metadata.resourceVersion) {
        // TODO increment version
        res.put(id, obj)
        subject(rd)
          .onNext(WatchEvent(EventType.MODIFIED, obj))
          .map(_ => obj) // NOTE ignores Ack.Stop
      } else conflict
      case None => notFound
    }
  }

  override def update[O <: skuber.ObjectResource](obj: O)(implicit fmt: Format[O], rd: ResourceDefinition[O], lc: client.LoggingContext): Future[O] = {
    state.synchronized {
      updateUnsynchronized(obj)
    }
  }

  override def delete[O <: skuber.ObjectResource](name: String, gracePeriodSeconds: Int)(implicit rd: ResourceDefinition[O], lc: client.LoggingContext): Future[Unit] = {
    state.synchronized {
      val res = resourceSet(rd)
      val id = Id.createUnsafe(namespaceName, name)
      Option(res.get(id)) match {
        case None => notFound
        case Some(current) => {
          res.remove(id)
          subject(rd)
            .onNext(WatchEvent(EventType.DELETED, current))
            .map(_ => ())
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
    Future.fromTry(fmt.reads(JsArray(Nil)).asEither.left.map(err => new RuntimeException(err.toString)).toTry)
  }

  override def listSelected[L <: skuber.ListResource[_]](labelSelector: LabelSelector)(implicit fmt: Format[L], rd: ResourceDefinition[L], lc: client.LoggingContext): Future[L] = ???

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
    Source.fromPublisher(subject(rd).toReactivePublisher)
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
