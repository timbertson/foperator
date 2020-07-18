package net.gfxmonk.foperator.sample

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import skuber.k8sInit

object Implicits {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit val client = k8sInit
}
