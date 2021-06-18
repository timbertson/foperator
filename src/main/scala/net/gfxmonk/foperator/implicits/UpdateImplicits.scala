package net.gfxmonk.foperator.implicits

import net.gfxmonk.foperator.Update.Updateable
import skuber.{CustomResource, ObjectEditor, ObjectMeta}

trait UpdateImplicits {
  implicit def crUpdateable[Sp,St] = new Updateable[CustomResource[Sp,St],Sp,St] {
    private type T = CustomResource[Sp,St]

    override def spec(obj: T): Option[Sp] = Some(obj.spec)

    override def status(obj: T): Option[St] = obj.status

    override def withSpec(obj: T, spec: Sp): T = obj.copy(spec=spec)

    override def withStatus(obj: T, status: St): T = obj.withStatus(status)
  }

  implicit def crEditable[Sp,St] = new ObjectEditor[CustomResource[Sp,St]] {
    override def updateMetadata(obj: CustomResource[Sp, St], newMetadata: ObjectMeta): CustomResource[Sp, St] = obj.withMetadata(newMetadata)
  }
}
