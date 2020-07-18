package net.gfxmonk.foperator

import skuber.{ObjectMeta, ObjectResource}

package object implicits {
  implicit class UpdateExt[T<:ObjectResource](val resource: T) extends AnyVal {
    def specUpdate[Sp](sp: Sp): Update.Spec[T,Sp] = Update.Spec(resource, sp)
    def statusUpdate[St](st: St): Update.Status[T,St] = Update.Status(resource, st)
    def metadataUpdate(metadata: ObjectMeta): Update.Metadata[T] = Update.Metadata(resource, metadata)
    def unchanged: Update.None[T] = Update.None(resource)
  }
}
