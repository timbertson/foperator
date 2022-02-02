package foperator

import cats.effect.Sync

object KubeconfigPath {
  def fromEnv[IO[_]](implicit io: Sync[IO]): IO[String] = {
    io.defer {
      sys.env.get("KUBECONFIG").map(_.split(':')(0))
        .orElse(sys.props.get("user.home").map(_ + "/.kube/config"))
        .fold(io.raiseError[String](new RuntimeException("Can't determine kubeconfig path")))(io.pure)
    }
  }
}
