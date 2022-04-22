package foperator.testkit

import cats.Eq
import foperator.Id
import foperator.types.{HasStatus, ObjectResource}

import java.time.Instant
import scala.reflect.ClassTag

// TestClient can use any model with valid typeclasses, but this is handy for simple tests
case class TestResource[Spec, Status](
  name: String,
  spec: Spec,
  status: Option[Status]=None,
  meta: TestMeta=TestMeta.initial)
{
  def id
    (implicit res: ObjectResource[TestResource[Spec, Status]])
    : Id[TestResource[Spec, Status]]
    = res.id(this)
}

object TestResource {
  implicit def eq[Sp, St]: Eq[TestResource[Sp, St]] = Eq.fromUniversalEquals

  implicit def res[Sp: ClassTag, St: ClassTag]: ObjectResource[TestResource[Sp,St]] = {
    // assume Sp / St have a sensible `equals` method, rather than making people provide an `Eq`
    implicit val eqSt: Eq[St] = Eq.fromUniversalEquals[St]
    new ResourceInstance[Sp,St]
  }

  class ResourceInstance[Sp, St](implicit eqSt: Eq[St], classSp: ClassTag[Sp], classSt: ClassTag[St])
    extends ObjectResource[TestResource[Sp, St]]
    with HasStatus[TestResource[Sp,St], St]
  {
    type T = TestResource[Sp,St]
    override def id(t: T): Id[T] = Id.apply[T]("default", t.name)
    override def kindDescription: String = s"${classOf[T].getSimpleName}[${classSp.getClass.getSimpleName},${classSt.getClass.getSimpleName}]"
    override def version(t: T): Option[String] = t.meta.version
    override def withVersion(t: T, newVersion: String): T = t.copy(meta=t.meta.copy(version = Some(newVersion)))
    override def finalizers(t: T): List[String] = t.meta.finalizers
    override def replaceFinalizers(t: T, f: List[String]): T = t.copy(meta=t.meta.copy(finalizers = f))
    override def isSoftDeleted(t: T): Boolean = t.meta.deletionTimestamp.isDefined
    override def softDeletedAt(t: T, timestamp: Instant): T = t.copy(meta=t.meta.copy(deletionTimestamp = Some(timestamp)))
    override val eqStatus: Eq[St] = eqSt
    override def status(t: T): Option[St] = t.status
    override def withStatus(t: T, status: St): T = t.copy(status=Some(status))
  }
}

case class TestMeta(
  version: Option[String]=None,
  finalizers: List[String]=Nil,
  deletionTimestamp: Option[Instant]=None)

object TestMeta {
  def initial = TestMeta()
  implicit val eq: Eq[TestMeta] = Eq.fromUniversalEquals
}