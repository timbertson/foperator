package net.gfxmonk.foperator.testkit

import cats.Eq
import net.gfxmonk.foperator.Id
import net.gfxmonk.foperator.types.{HasStatus, ObjectResource}

import java.time.ZonedDateTime
import scala.reflect.ClassTag

// TestClient can use any model with valid typeclasses, but this is handy for simple tests
case class TestResource[Spec, Status](
  name: String,
  spec: Spec,
  status: Option[Status]=None,
  meta: TestMeta=TestMeta.empty)
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

  class ResourceInstance[Sp, St](implicit eqSt: Eq[St], classSp: ClassTag[Sp], classSt: ClassTag[St]) extends ObjectResource[TestResource[Sp, St]] with HasStatus[TestResource[Sp,St], St] {
    type T = TestResource[Sp,St]
    override def id(t: T): Id[T] = Id.apply[T]("default", t.name)
    override def kind: String = classOf[T].getSimpleName
    override def apiPrefix: String = s"${classOf[T].getCanonicalName}-${classSp.runtimeClass.getCanonicalName},${classSt.runtimeClass.getCanonicalName}/v1"
    override def version(t: T): Option[Int] = t.meta.version
    override def withVersion(t: T, newVersion: Int): T = t.copy(meta=t.meta.copy(version = Some(newVersion)))
    override def finalizers(t: T): List[String] = t.meta.finalizers
    override def replaceFinalizers(t: T, f: List[String]): T = t.copy(meta=t.meta.copy(finalizers = f))
    override def deletionTimestamp(t: T): Option[ZonedDateTime] = t.meta.deletionTimestamp
    override def withDeleted(t: T, timestamp: ZonedDateTime): T = t.copy(meta=t.meta.copy(deletionTimestamp = Some(timestamp)))
    override val eqStatus: Eq[St] = eqSt
    override def status(t: T): Option[St] = t.status
    override def withStatus(t: T, status: St): T = t.copy(status=Some(status))
  }
}

case class TestMeta(
  version: Option[Int]=None,
  finalizers: List[String]=Nil,
  deletionTimestamp: Option[ZonedDateTime]=None)

object TestMeta {
  def empty = TestMeta()
  implicit val eq: Eq[TestMeta] = Eq.fromUniversalEquals
}

