package foperator.sample.generic

import cats.effect.ExitCode
import cats.implicits._
import com.goyeau.kubernetes.client.crd.CustomResource
import foperator.backend.KubernetesClient
import foperator.backend.kubernetesclient.implicits._
import foperator.sample.Models
import foperator.sample.Models.KubernetesClient._
import io.circe.generic.auto._
import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1.CustomResourceDefinition
import monix.eval.{Task, TaskApp}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object KubernetesClientMain extends TaskApp {
  implicit val logger = Slf4jLogger.getLogger[Task]

  override def run(args: List[String]): Task[ExitCode] = {
    KubernetesClient[Task].default.use { client =>
      new GenericOperator[Task, KubernetesClient[Task], CustomResourceDefinition, CustomResource](
        client,
        Models.KubernetesClient.greetingCrd
      ).run.as(ExitCode.Success)
    }
  }
}
