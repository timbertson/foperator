package foperator.backend

import cats.effect.{Async, Resource}
import com.goyeau.kubernetes.client
import com.goyeau.kubernetes.client.KubeConfig
import com.goyeau.kubernetes.client.foperatorext.Types._
import foperator._
import foperator.backend.kubernetesclient.impl
import foperator.types._
import org.typelevel.log4cats.Logger

import fs2.io.file.Path

class KubernetesClient[IO[_] : Async](val underlying: client.KubernetesClient[IO])
  extends Client[IO, KubernetesClient[IO]] {
  override def apply[T]
    (implicit e: Engine[IO, KubernetesClient[IO], T], res: ObjectResource[T]): Operations[IO, KubernetesClient[IO], T]
    = new Operations[IO, KubernetesClient[IO], T](this)
}

object KubernetesClient {
  def apply[IO[_]: Async : Logger] = new Companion[IO]

  class Companion[IO[_]](implicit io: Async[IO], logger: Logger[IO]) extends Client.Companion[IO, KubernetesClient[IO]] {
    def wrap(underlying: client.KubernetesClient[IO]): KubernetesClient[IO] = new KubernetesClient(underlying)

    def default: Resource[IO, KubernetesClient[IO]] = for {
      path <- Resource.eval(KubeconfigPath.fromEnv[IO])
      client <- client.KubernetesClient[IO](KubeConfig.fromFile(Path(path)))
    } yield wrap(client)

    def apply(config: KubeConfig[IO]): Resource[IO, KubernetesClient[IO]] = client.KubernetesClient[IO](config).map(wrap)

    def apply(config: IO[KubeConfig[IO]]): Resource[IO, KubernetesClient[IO]] = client.KubernetesClient(config).map(wrap)
  }

  implicit def engine[IO[_] : Async, T<:HasMetadata, TList<:ListOf[T]]
    (implicit api: impl.HasResourceApi[IO, T, TList], res: ObjectResource[T])
  : Engine[IO, KubernetesClient[IO], T]
  = new impl.EngineImpl[IO, T, TList]
}


