package foperator.sample.generic

import cats.effect.{Async, IO, IOApp}
import com.goyeau.kubernetes.client.crd.CustomResource
import foperator.backend.KubernetesClient
import foperator.backend.kubernetesclient.implicits._
import foperator.sample.Models
import foperator.sample.Models.KubernetesClient._
import io.circe.generic.auto._
import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1.CustomResourceDefinition
import org.typelevel.log4cats.slf4j.Slf4jLogger

object KubernetesClientMain extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  override def run: IO[Unit] = {
    // TODO: this shouldn't be necessary, but without it we get diverging implementation
    // starting with method asyncForKleisli in object Async
    implicit val async: Async[IO] = IO.asyncForIO

    KubernetesClient[IO].default.use { client =>
      new GenericOperator[IO, KubernetesClient[IO], CustomResourceDefinition, CustomResource](
        client,
        Models.KubernetesClient.greetingCrd
      ).run
    }
  }
}
