package foperator.sample.generic

import cats.implicits._
import cats.effect.ExitCode
import com.goyeau.kubernetes.client.KubeConfig
import com.goyeau.kubernetes.client.crd.CustomResource
import foperator.backend.kubernetesclient_backend.KClient
import foperator.backend.kubernetesclient_backend.implicits._
import foperator.sample.Models
import foperator.sample.Models.{GreetingSpec, GreetingStatus}
import foperator.sample.Models.KubernetesClient._
import io.k8s.apiextensionsapiserver.pkg.apis.apiextensions.v1.CustomResourceDefinition
import monix.eval.{Task, TaskApp}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.circe.generic.auto._

import java.io.File

object KClientMain extends TaskApp {
  implicit val logger = Slf4jLogger.getLogger[Task]

  override def run(args: List[String]): Task[ExitCode] = {
    KClient[Task].apply(KubeConfig.fromFile(new File(sys.props.get("user.home").get + "/.kube/config"))).use { client =>
      new GenericOperator[Task, KClient[Task], CustomResourceDefinition, CustomResource](
        client.ops,
        Models.KubernetesClient.greetingCrd
      ).run.as(ExitCode.Success)
    }
  }
}
