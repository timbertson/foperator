# Sample operators

# Simple: Hello, world

The `SimpleMain.scala` operator implements more or less a "hello world" operator. It has a `Greeting` type with a `name` spec field, and it ensures the `status.message` field is set to `"Hello, $name"`.

# Advanced: Hello, [people]

To demonstrate how more complex operators work, `AdvancedMain.scala` implements a multi-resource operator. The basic premise is that a greeting can instead have a `surname`, and it should greet every person with that surname in its `status.message` field.

This is a somewhat common pattern when multiple kubernetes resources are involved - often an operator will reconcile their own type, but also need to react to changes in related types. For a real example you can think of the `ReplicaSet` reconciler, which needs to respond when a `ReplicaSet` changes, but also when there are changes (especially removals) of related `Pod` resources.

### Person:

 - Spec: contains `firstName` and `surname` (both mandatory)
 - Status: empty (unused)

### Greeting:

 - Spec: contains two fields (`name` and `surname`), exactly one of which should be set.
 - Status: contains two fields, `message` and `people`.

If `name` is set, that's a simple greeting (i.e. it acts just like the Simple operator).

If `surname` is set, that's a complex greeting. The message should greet all of the people with a matching surname. For tracking, the `people` status field should contain the k8s names of all referenced people.

For stability, the names in the message as well as the `people` list should be sorted by the firstname of the people involved.

**Example:**

If there are people named "John Doe" (name: `johnd`) and "Jane Doe" (object: `janed`), then a greeting with surname "Doe" should end up in the following state:

 - message: "Hello to the Doe family: Jane, John"
 - people: ["janed", "johnd"]

## Resilience:

The advanced operator makes use of finalizers to ensure that the correct state is always maintained. That is:

 - a `Person` cannot be deleted while any `Greeting` references it
 - a `Greeting` must stop referencing a `Person` when that person becomes soft-deleted (i.e. its `deletionTimestamp` is set)
 - the finalizer should be removed once no `Greeting`s refer to the given `Person`

This is obviously a little over-engineered for this use case, but we're trying to imidate real world complexities.

To simplify ownership, I've implemented this as a second reconciler on `Person` which simply removes the finalizer once no Greetings are referencing the Person. This is a little inelegant, as it uses the error backoff mechanism to perform retries while the person is still referenced. But it's conceptually simpler than having a reconciler that mutates multiple objects.

## Performance:

When reconciling a `Greeting`, the operator should not `List()` all `People` every time. Instead, there should be a local, up-to-date cache of `People` that the reconciler can pull from.

# Mutator:

As part of the development of the framework and sample operators, I built a `mutator` which will take random actions and then assert that the resulting state (after reconcile) is valid. This forms a convenient way to generate test data, as well as a primitive fuzzer to ensure the framework handles concurrent updates correctly.
