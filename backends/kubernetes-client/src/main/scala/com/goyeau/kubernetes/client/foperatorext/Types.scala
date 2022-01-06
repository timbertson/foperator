package com.goyeau.kubernetes.client.foperatorext

import com.goyeau.kubernetes.client.operation._
import io.k8s.apimachinery.pkg.apis.meta.v1.{ListMeta, ObjectMeta}
import org.http4s.Uri

// Kubernetes-client doesn't have any shared hierarchy, so we use structural types.
// We also cheekily put this in their namespace so we can use package-private traits.
// This surely breaks semtantic versioning, hopefully the traits become public in the future.
object Types {
  type HasMetadata = {
    def metadata: Option[ObjectMeta]
  }

  type ResourceGetters[St] = {
    def metadata: Option[ObjectMeta]
    def status: Option[St]
  }

  type ListOf[T] = {
    def metadata: Option[ListMeta]
    def items: Seq[T]
  }

  // toplevel API, e.g. PodsApi
  type ResourceAPI[IO[_], T<:HasMetadata, TList<:ListOf[T]] = {
    def resourceUri: Uri
    def namespace(namespace: String): NamespacedResourceAPI[IO, T, TList]
  }

  // nested API, e.g. NamespacedPodsApi
  type NamespacedResourceAPI[IO[_], T<:HasMetadata, TList<:ListOf[T]] =
    Replaceable[IO, T]
    with Gettable[IO, T]
    with Listable[IO, TList]
    with Deletable[IO]
    with GroupDeletable[IO]
    with Watchable[IO, T]
}