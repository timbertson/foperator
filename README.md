![](/doc/logo.png)

# _functional_ k8s operator framework, in Scala

Current status: everything works, but it's early days and it might change substantially without notice. Also it's not actually packaged anywhere.

# Features

 - Write your operator in `scala`, not `golang`
   - type safe, `null`-safe, generics, composable
 - Small: <1k LOC, only a dozen classes
 - Client-agnostic (`foperator-skuber` is provided but you can BYO)
 - Functional, pure API using [cats-effect](https://typelevel.org/cats-effect/) (v2 only for now)
 - First-class object deletion (`ResourceState` encapsulates `Active` | `SoftDeleted` so the types ensure correct handling of deleted resources)
 - Best practices baked in (e.g. `ResourceMirror` is the equivalent of go's `SharedInformer`, except that a `SharedInformer` isn't supported by `controller-runtime`, you have to write a custom `Controller`)

# Sample operators

See [./sample](./sample) for a couple of sample operators using the framework.

# Why not `operator-sdk`, `controller-runtime`, etc?

If you're going to shun the de-facto standard technologies for creating Kubernetes Operators, you should probably have good justification for doing so. I strongly believe that `foperator` (and Scala) lets you write operators with less bugs, less effort and less code then anything built in Go. But this is not just a gut feel, I wrote up some [extensive notes on the downsides of `operator-sdk`](./doc/operator-sdk.md) after implementing the same operator with both frameworks.

# Mechanics

The flow of information looks a bit like this:

![](/doc/k8s-flow.png)

The ResourceMirror is responsible for mirroring the state of watched objects, as well as providing an `ids` update stream.

The Dispatcher (an internal component) uses the stream of updated IDs to schedule calls to the reconciler. It handles concurrency, error backoff and periodic retries.

The reconciler is your job: given the current state of an object, it does whatever it is you want to do, and (typically) updates the status of the reconciled object.

# Types for writing operators

To get a better understanding of how to write foperator code, let's take a tour of the core types you'll encounter:

## Reconciler[IO, Client, T]:

`T` is the type of the resource being reconciled, like `Pod`, `Job` or `MyCustomThing`.

`IO` is e.g. Monix Task or cats-effect IO.

And `Client` is the type of the client - `Skuber` if you're using `foperator-skuber`.

A reconciler is essentially a function of the type `(Client, T) => IO[ReconcileResult]`.

The reconciler is given the current state of the object it's reconciling, and is responsible for making the world right (which is up to you).

However there are some convenience builders to make this even simpler, e.g. if you don't need to return a custom result or interact with the client, you can just supply a function `T => IO[Unit]`.

## Operations[IO, Cclient, T]

The Operations type simply ties together a backend client engine with a compatible resource type. Given these, it provides convenience functions for anything that needs a client - reading, writing, creating a mirror and running a reconciler.

## ResourceMirror[IO, T]

A ResourceMirror is effectively a local cache of the Kubernetes state that we're interested in. You can set up a ResourceMirror for all objects of a given type, or just for a subset (based on labels). It will initially get a list of all such resources, as well as setting up a watch for any future changes. Using this, it maintains an internal state of every watched object, _plus_ a stream of IDs of updated resources.

We don't send full resources into the dispatcher, since a notification about an updated resource might be queued rather than immediately executed. To ensure we don't waste time reconciling old versions of objects, the dispatcher will grab the current version of the relevant resource from the mirror _just before_ it actually runs the reconcile.

Because a ResourceMirror instance corresponds to an ongoing `watch` API query as well as a full local copy of every watched resource, we don't want to make lots of them. In general, an operator controller process should only have one ResourceMirror for each resource type, since they can be safely shared.

# Types for implementing new backends

A backend is made up of two main parts - the client type, and the resource type(s).

## ObjectResource[T]

This is a simple typeclass to provide resource functionality for a given type. Mostly it relates to getting / setting various kubernetes metadata (version, finalizers, API path, etc).

## Engine[IO, Client, T]

This is the interface required for foperator to use an underlying client type. The required methods are a small subset of what most kubernetes clients will expose.

Some backends will place a type constraint on `T`, e.g for skuber the Engine implementation requires `T <: skuber.ObjectResource`

# Troubleshooting implicits / typeclasses

The use of typeclasses means foperator can trivially support multiple backends, but can make errors harder to understand.

One common issue is when an implicit is not found, e.g. "No implicits found for e: Engine[Task, TestClient[Task], CustomResourceDefinition]"

Most of the time, an implicit will become available when you import e.g. `net.gfxmonk.foperator.skuberengine.implicits._` (for the Skuber backend). If not, it often means that there's an implicit missing somewhere in the chain.

In these cases, it's useful to dig into the implicit chain. Firstly, figure out the method which _ought_ to provide this implicit. In this case, it's `TestClient.implicitEngine`. Then try to invoke that explicitly, by adding:

```
val e = TestClient.implicitEngine[Task, CustomResourceDefinition]
```

In this case you get a new error, "No implicits found for eq: Eq[CustomResourceDefinition]"

In this case an `Eq` instance was missing, preventing the implicit Engine from being built. In an earlier version of foperator) the skuber backend provided an `Eq` for `CustomResource`, but not `CustomResourceDefinition`.

In general if you're operating on builtin kubernetes types, you may need to provide some of your own instances (like `Eq` and `ObjectEditor`), because Skuber's type heirarchy doesn't allow for blanket implementations.

# Managing multiple resources

Most "hello world" operators (and even many real-world operators) involve a single Kubernetes resource. When a resource spec changes, you do something to make sure "the world" is in sync, then update the resource's status to match. If you're managing resources outside the Kubernetes cluster, this is really all you need. But what about when you're juggling multiple different types of Kubernetes resources?

Consider the ReplicaSet controller, which typically manages pods for a deployment. The ReplicaSet defines "I want 3 of these pods running", and the controller is responsible for making sure there are 3 pods running. Pods can come and go of their own accord, so only watching the ReplicaSet and relying on periodic reconciliation to notice dead Pods isn't very responsive.

It turns out we can use multiple ResourceMirrors for this: The `Reconciler[ReplicaSet]` reconcile function can hold a reference to a secondary `ResourceMirror[Pod]`. Since a ResourceMirror contains a local copy of the state of a whole set of resources, it's cheap for the ReplicaSet reconcile function to check every time that the right number of Pods exist in this cache, without having to make API calls to Kubernetes all the time.

That makes reconciliation cheap, but it doesn't make it responsive. For this, my first thought was that "we also need to reconcile Pods" - i.e. when a Pod comes or goes, we should reconcile _it_ to make sure each ReplicaSet's desired state is upheld. But that's a bad idea. In particular, the framework guarantees that only one reconcile will be running for a given ReplicaSet at any time, to prevent conflicts. But if we _also_ do some actions per Pod, we may be reconciling a Pod and its corresponding ReplicaSet concurrently, which is hard to reason about. It also makes the code more complex since we have two reconcile loops to reason about.

Instead, we watch pods _without_ reconciling them, and just use them as an additional trigger for ReplicaSet reconciliation. That is, a ReplicaSet will be reconciled whenever:
 - the ReplicaSet itself is updated, OR
 - a Pod is updated which "relates" to this ReplicaSet

The relationship is simple enough to implement, and is also facilitated by the ResourceMirror. For each Pod update:
 - get all ReplicaSets which _should_ relate to this pod but don't (the selector matches but the ReplicaSet doesn't own the pod)
 - get all ReplicaSets which _do_ reference this pod but shouldn't (the ReplicaSet owns the pod but the selector doesn't match)

The ReplicaSet IDs obtained from these relationships are merged with the IDs of updated resources, and either action will cause a reconcile of the ReplicaSet. In this way there is _one_ piece of code responsible for taking action, but we augment it with additional triggers due to changes outside the resource itself. The `ReconcileSource` trait is provided by ResourceMirror, and has functions for merging updates from multiple ResourceMirrors in this way.
