![](/doc/logo.svg)

# foperator: a functional operator framework (for Scala)

Current status: incredibly WIP.

# Mechanics

The flow of information looks a bit like this:

![](/doc/k8s-flow.svg)

The ResourceMirror is responsible for mirroring the state of watched objects, as well as providing an `ids` update stream.

The Dispatcher uses the stream of updated IDs to schedule calls to the reconciler (your code). It handles concurrency, error backoff and periodic retries.

The reconciler is your job: given the current state of an object, does whatever it is you want to do, and (typically) updates the status of the reconciled object.

# Types

To get a good understanding of how foperator works, let's take a tour of the core types:

## Reconciler[T]:

`T` is the type of the resource being reconciled, like `Pod`, `Job` or `MyCustomThing`.

This is the core logic of an operator. Its type is pretty straightforward:

```
def reconcile(current: T): Task[ReconcileResult]
```

 - Task: (possibly) asynchronous
 - ReconcileResult: `Ok`or `Retry(after: FiniteDuration)`


The reconciler is given the current state of the object it's reconciling, and is responsible for making the world right. It's completely up to you how that happens, though there are some common patterns:

```scala
Reconciler.updater[Spec,Status](fn: CustomResource[Spec,Status] => Task[CustomResourceUpdate[Spec, Status])
```

Similar to the `reconcile` function, except:
 - must be a CustomResource
 - we don't _do_ anything, we just (asynchronously) return a `CustomResourceUpdate`. This is an ADT for modifying the input resource, it can be in the form of a new new Spec, Status, Metadata (or Unchanged)

This is a common pattern in functional programming: separate the intent from the action. This is a special case of an `UpdateReconciler[T, Op]`, which is composed of:
 - `operation: T => Task[Op]`
 - `apply: Op => Task[ReconcileResult]`

These could just be manually chained into a single reconcile function with `operation.flatMap(apply)`, but keeping them separate allows us to easily test "what update is produced from the following input" directly, instead of checking what side-effects `apply` has performed.

## Operator[T]

The Operator type is really just "a reconciler plus some settings", e.g. `refreshInterval` / `concurrency`. It also has an optional `Finalizer`, which is really a specialized `Reconciler`. Where a `Reconciler` takes actions to reconcile active resources, a `Finalizer` works on "soft deleted" resources (those with a `deletedTimestamp`). [Read more about Finalizers](https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/).

An `Operator`, like a `Reconciler`, is inert - it's not associated with any resources. That's where the Controller comes in:

## Controller[T]

A `Controller` is simply a pairing of an `Operator[T]` and a `ControllerInput[T]`. It has a single method, `run`, which is the actual thing that gets run in an operator process. We've heard about Operator, but what is a `ControllerInput`?

Well, a Reconciler tells us what to do for a given resource. But how do we know what resources exist, and their states? That's what a `ControllerInput` is. It's essentially a monix `Observable[Id[T]]`, i.e. an infinite stream of IDs for our resource type. Every time an ID appears in this stream, the `Controller` arranges to have the `Reconciler` do its thing on that resource.

Internally, this is accomplished by the `Dispatcher` and `ResourceLoop` types, which ensure that we don't start two concurrent reconciles on the same object, as well as scheduling retries at different rates on success / failure.

But wait, why are these only IDs and not resources?

## ResourceMirror[T]

A ResourceMirror is the final piece of the puzzle. It's effectively a local cache of the Kubernetes state that we're interested in. You can set up a ResourceMirror for all objects of a given type, or just for a subset (based on labels). It will initially get a list of all such resources, as well as setting up a watch for any future changes. Using this, it maintains an internal state of every watched object, _plus_ a stream of IDs of updated resources.

We don't send full resources into the controller, since a notification about an updated resource might be queued rather than immediately executed. To ensure we don't waste time reconciling old versions of objects, the controller will grab the current version of the relevant resource from the mirror _just before_ it actually runs the reconcile.

Because a ResourceMirror instance corresponds to an ongoing `watch` API query as well as a full local copy of every watched resource, we don't want to make lots of them. In general, a controller process should only have one ResourceMirror type for each resource type, since they can be safely shared.

# Managing multiple resources

Most "hello world" operators (and even many real-world operators) involve a single Kubernetes resource. When a resource spec changes, you do something to make sure "the world" is in sync, then update the resource's status to match. If you're managing resources outside the Kubernetes cluster, this is really all you need. But what about when you're juggling multiple different types of Kubernetes resources?

Consider the ReplicaSet controller, which typically manages pods for a deployment. The ReplicaSet defines "I want 3 of these pods running", and the controller is responsible for making sure there are 3 pods running. Pods can come and go of their own accord, so only watching the ReplicaSet and relying on periodic reconciliation to notice dead Pods isn't very responsive.

It turns out we can use multiple ResourceMirrors for this: The `Reconciler[ReplicaSet]` reconcile function can hold a reference to a secondary `ResourceMirror[Pod]`. Since a ResourceMirror contains a local copy of the state of a whole set of resources, it's cheap for the ReplicaSet reconcile function to simply check every time that the right number of Pods exist in this cache, without having to make API calls to Kubernetes all the time.

That makes reconciliation cheap, but it doesn't make it responsive. For this, my first thought was that "we also need to reconcile Pods" - i.e. when a Pod comes or goes, we should reconcile _it_ to make sure each ReplicaSet's desired state is upheld. But that's a bad idea. In particular, the framework guarantees that only one reconcile will be running for a given ReplicaSet at any time, to prevent conflicts. But if we _also_ do some actions per Pod, we may be reconciling a Pod and its corresponding ReplicaSet concurrently, which is hard to reason about. It also makes the code more complex since we have two reconcile loops to reason about.

Instead, we watch pods _without_ reconciling them, and just use them as an additional trigger for ReplicaSet reconciliation. That is, a ReplicaSet will be reconciled whenever:
 - the ReplicaSet itself is updated, OR
 - a Pod is updated which "relates" to this ReplicaSet

The relationship is simple enough to implement, and is also facilitated by the ResourceMirror. For each Pod update:
 - get all ReplicaSets which _should_ relate to this pod but don't (the selector matches but the ReplicaSet doesn't own the pod)
 - get all ReplicaSets which _do_ reference this pod but shouldn't (the ReplicaSet owns the pod but the selector doesn't match)

The ReplicaSet IDs obtained from these relationships are simply merged with the IDs of updated resources, and either action will cause a reconcile of the ReplicaSet. In this way there is _one_ piece of code responsible for taking action, but we augment it with additional triggers due to changes outside the resource itself. The `ControllerInput` class performs this merging, and has functions for merging updates from multiple ResourceMirrors in this way.
