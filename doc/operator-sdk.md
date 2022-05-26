# Downsides of `operator-sdk`

**Disclaimer**: What follows is some biased notes after implementing a [sample operator](../sample/) with `operator-sdk`, which I had also written in `foperator`. My points are all fact-based, but I've only listed things which bother me. If you love Go, maybe you'd end up with a similar list after trying to write an operator in Scala.

The actual code is hosted in the detached [`operator-sdk` git branch](https://github.com/timbertson/foperator/tree/operator-sdk) if you'd like to take a look, because it doesn't really deserve its own repository.

### Background:

I like Functional Programming and Scala so I didn't expect to love Go. But I wanted to ensure I was familiar with the status quo. From my previous experience with Go, I expected it to be tedious and primitive. Go fans call this straightforward and beginner-friendly, which is conceivably a matter of preference. What _shouldn't_ be a matter of preference however is how hard it is to write correct code.

FP folks like to talk about the "pit of success", where the easiest (or only) way of using an abstraction is also the correct way. Go contains many "pits of failure" where you must be careful where you tread - it's up to your constant diligence to end up with a correct program. Prominent examples of this are:
 - error handling: if you forget to check for `error != nil`, you can still use the value you got but it won't work
 - initialization: if you forget to initialize some value, it'll often work but be incorrect
 - mutability: idiomatic go makes use of mutability, it's up to you to know when it's safe to do so
 - untyped APIs: you need to do your own type casting at runtime, reducing the ability of the compiler to catch bugs
 - unchecked indexing: array indexing panics if the index is out of bounds

Below I'll go over some of the concrete issues I encountered. This is by no means an exhaustive analysis, it's just things I stumbled over when building a relatively simple operator.

# Golang

The biggest effect of choosing `operator-sdk` is that you obviously have to write your code in Go. So I'll start with observations on Go itself.

### Object initialization:

 - Zero values: go doesn't have the concept of absence, if you have an uninitialized Foo it will be valid but all its fields recursively set to the "empty value"
   - You need make a wrapper struct with a `string` field in order to have a "nullable" string. People often don't bother, leading to bugs when a legitimately empty string is interpreted as "missing", or vice versa.
   - If you forget to initialize some values they'll quietly end up `0`, `""`, etc. This is the cause for many subtle bugs.
 - There's no way to say "hey compiler, please tell me if I forget to initialize some fields". Just... try not to forget anything.
 - If you export a `struct` type, anyone can instantiate it. This includes `structs` with private fields, which are literally impossible for others to set on initialization. Whether or not users should initialize your structs is typically left to documentation.

### Goroutines

The _implementation_ of goroutines (in that everything has nonblocking performance with blocking semantics) is the one thing I actually envy about Go. But the API exposed to developers? Not so much.

The behaviour of goroutines are what [Monix](https://monix.io/) calls `runAsyncAndForget`. That is, if it fails then nobody notices, and the rest of your app carries on blissfully unaware. Which is one of the terrible things about threads.

[`errgroup`](https://godoc.org/golang.org/x/sync/errgroup) makes this better, by letting you spawn goroutines attached to a context, and if any coroutine errors then that's reflected on the context object. But it's still far from foolproof:

  - You need to manually thread the `context` around
  - Every goroutine you spawn must periodically check for cancellation (by checking `Err() != nil` or consuming from the `Done()` channel) in order to respect cancellation
  - On my first attempt I accidentally used the root context in one place instead of deriving a new context. That meant cancellation was not actually propagated to any goroutines.

By comparison, [Monix](https://monix.io/) bakes in error propagation and cancellation to [`Task`](https://monix.io/docs/current/eval/task.html). Every boundary created by `map`, `flatMap` etc is automatically a cancellation boundary, and you can also use `onCancel` to attach custom cancellation to a given `Task`. All aggregation functions (`zip`, `parSequenceUnordered`, etc) will propagate cancellation automatically. The end result is that you almost never have to think about cancellation, unless you're doing low-level plumbing.
 
### (Lack of) Generics

 - Go made me write my own `contains` function (to find a string in a list of strings).

 - Speaking of generics, `sortBy` is a heck of an effort. I followed this: https://gobyexample.com/sorting-by-functions. I quote:
> By following this same pattern of creating a custom type, implementing the three Interface methods on that type, and then calling sort.Sort on a collection of that custom type, we can sort Go slices by arbitrary functions

In practice, this looks like:

```go
type byFirstName []Person

func (s byFirstName) Len() int {
	return len(s)
}

func (s byFirstName) Swap(i, j int) {
	s[i], s[j] = s[j], s[i]
}

func (s byFirstName) Less(i, j int) bool {
	return s[i].Spec.FirstName < s[j].Spec.FirstName
}

func SortByFirstName(people []Person) []Person {
	dest := make([]Person, len(people))
	copy(dest, people)
	sort.Sort(byFirstName(dest))
	return dest
}
```

(in scala, this is written `sortBy(_.firstName)`)

There are two easy bugs to make here:

 - Firstly if you don't make the `dest` slice the same size as the input, `copy` will quietly do a partial copy (it'll copy `n` elements, where `n` is the smaller length of `dest` / `people`).
 - Secondly if you get the argument order to `copy` wrong (as I did), you'll actually mutate the input slice and return a slice full of zero objects.

It's interesting that generics are [being worked on](https://blog.golang.org/why-generics) for Go. I personally believe the lack of generics prevents Go developers from providing better abstractions for most of the issues in this document, so once generics are available it'll be interesting to see if that happens.

### Annoyances

 - If you return an error, you still have to return a valid "success" value and hope nobody uses it. As a user, you always get a "success" value back but have to remember not to use it if you _also_ got a non-`nil` error.

 - Logging (with `logr`) causes a panic if you pass an even number of arguments because it wants key-value pairs after the message, but Go lacks tuples (even though functions can return a pair of values).

 - I had to get uncomfortably familiar with type-casting semantics in Go. There are multiple forms, one panics and one returns both the value and a boolean indicating success. An extra little trap is that `Foo` and `*Foo` are distinct types - that's good, but it's an easy thing to fat-finger and end up with a runtime failure.
 
 - Always thinking "Should I be checking for `nil`?" Is exhausting...

 - Is it an empty slice or a `nil` slice?

   ```go
   var a []string
   b := []string{}
   ```
   They both work like an empty slice in go, but serialize differently in JSON


# operator-sdk

As I understand it, operator-sdk is a toolkit for making operators, but it doesn't actually provide runtime code - it generates code that leans on libraries (particularly `controller-runtime`). This is a good thing, though it wasn't all that clear to me.

Code generation is common in go, often as a workaround for lack of advanced language features like generics. One downside is that you often need a custom build system, and that brings its own issues:

### Build system bugs:

 - `operator-sdk init` failed a few times (improper go setup), then failed because files already existed. I had to manually remove everything.

 - It's weird that when I have a syntax error, it gets repeated 5x in the console output. Maybe multiple generators are invoking `go` concurrently?

 - If I import a module that doesn't exist, I get:

   ```
   Error: -: go: finding module for package k8s.io/apimachinery/pkg/reconcile
   go build k8s.io/apimachinery/pkg/reconcile: no Go files in 
   ```
   Followed by a screenful of usage docs for `controller-gen` because I guess it didn't get any arguments. This is not surprising for a build system implemented in `bash`.

 - Some errors reported at the generator stage don't report the file, e.g.:
   ```
   github.com/timbertson/operator-bakeoff/controllers:-: use of unimported package "logr"
   ```

 - The code generator itself panicked at one point when I wrote some code it didn't like:
   ```
   panic: interface conversion: types.Type is nil, not *types.Named
   
   goroutine 1 [running]:
   sigs.k8s.io/controller-tools/pkg/deepcopy.shouldBeCopied(0xc0005a14e0, 0xc00056ee40, 0x1004101)
   ```

### Extra notes:

 - I bet `zz_generated.deepcopy.go` could be a fun source of bugs if you forget to regenerate.

 - The code generation makes use of annotations in unstructured comments, like `+kubebuilder:subresource:status`, which is not exactly elegant.

 - I got the "group" wrong first time (I used a model name, not a module name). I manually renamed to "sample", which required editing 30 references in various files.

 - There sure is a bunch of mutation:
   - adding event handlers
   - registering the mapping of types -> schemas
   - setting a root logger
   - pushing / popping things on workqueues

 - If I `Get()` in the reconcile (seems pretty typical), I have to remember to handle 404 myself.

 - I'm also doing a lot of "remember to check for deleted objects", which is a likely source of bugs.
 
## Breaking free of controller-runtime's manager

Since the beginning of this project, I've [read about](https://engineering.bitnami.com/articles/a-deep-dive-into-kubernetes-controllers.html) a SharedInformer which seemed to be perfect for my use case (wanting to know the state of related resources during a reconcile), but it's hard to tell whether that's a thing I should actually be using. I ignored this initially, doing the dumb thing where I just do a `List()` on the relevant resources every time.

After getting a working controller, I still couldn't figure out how to integrate a SharedInformer with controller-runtime's Manager. The only path forward I could find is to follow [this one blog post](https://itnext.io/what-i-learnt-about-kubernetes-controllers-db7591531973) and roll my own controller without the manager. I don't _think_ I missed an easier way to integrate these, and I did ask around.

Ditching the Manager caused me to have to write a whole bunch of orchestration code myself. It may be that there's a way to do this that involves me doing less work. But part of the value of "use what everyone else is using" is that paths should be well-worn, and there should be plenty of examples and experience to lean on. In my case, that wasn't true at all.

Here's some base controller stuff I needed, to interact with the workqueue:

```go
func AddId(ctrl Controller, obj interface{}) {
	keyable, ok := obj.(Keyable)
	if !ok {
		ctrl.Ctx().Cancel(fmt.Errorf("not keyable (type %T): %v", obj, obj))
		return
	}

	key := keyable.QueueKey()
	ctrl.Queue().Add(key)
}

func handleErr(ctrl Controller, err error, key interface{}) {
	if err == nil {
		ctrl.Queue().Forget(key)
		return
	}

	ctrl.Log().Error(err, "error during reconcile", "key", key)
	ctrl.Queue().AddRateLimited(key)
}

func processNextItem(ctrl Controller) bool {
	queue := ctrl.Queue()
	key, quit := queue.Get()
	if quit {
		return false
	}
	defer queue.Done(key)

	namespace, name, err := cache.SplitMetaNamespaceKey(key.(string))
	if err == nil {
		err = ctrl.Reconcile(controllerRuntime.Request{
			NamespacedName: types.NamespacedName{
				Namespace: namespace,
				Name:      name,
			},
		})
	}
	handleErr(ctrl, err, key)
	return true
}

func Run(c Controller) {
	for processNextItem(c) {
	}
}
```

Here's the plumbing I wrote to setup the shared indexers:

```go
func NewCtx(scheme *runtime.Scheme) (Ctx, error) {
	log := ctrl.Log.WithName("ControllerCtx")
	empty := Ctx{}
	rules := clientcmd.NewDefaultClientConfigLoadingRules()
	overrides := &clientcmd.ConfigOverrides{}
	config, err := clientcmd.NewNonInteractiveDeferredLoadingClientConfig(rules, overrides).ClientConfig()
	if err != nil {
		return empty, err
	}

	client, err := client.New(config, client.Options{
		Scheme: scheme,
	})
	if err != nil {
		return empty, err
	}

	greetingRestClient, err := apiutil.RESTClientForGVK(
		schema.GroupVersionKind{
			Group:   samplev1alpha1.GroupVersion.Group,
			Version: samplev1alpha1.GroupVersion.Version,
			Kind:    "Greeting",
		},
		config,
		serializer.NewCodecFactory(scheme),
	)

	personRestClient, err := apiutil.RESTClientForGVK(
		schema.GroupVersionKind{
			Group:   samplev1alpha1.GroupVersion.Group,
			Version: samplev1alpha1.GroupVersion.Version,
			Kind:    "Person",
		},
		config,
		serializer.NewCodecFactory(scheme),
	)

	personLW := cache.NewListWatchFromClient(personRestClient, "people", v1.NamespaceDefault, fields.Everything())
	personInformer := cache.NewSharedInformer(personLW, &samplev1alpha1.Person{}, 0)

	greetingLW := cache.NewListWatchFromClient(greetingRestClient, "greetings", v1.NamespaceDefault, fields.Everything())
	greetingInformer := cache.NewSharedInformer(greetingLW, &samplev1alpha1.Greeting{}, 0)

	executionContext, cancel := context.WithCancel(context.Background())

	return Ctx{
		Log:      log,
		cancelFn: cancel,
		Context:  executionContext,

		Scheme: scheme,
		Client: client,

		PersonInformer:   personInformer,
		GreetingInformer: greetingInformer,
	}, nil
}
```

And here's what I had to do in order to spawn everything in the right order, with cancellation etc:

```go
func RunControllers(parallelism int) error {
	ctrl.SetLogger(zap.New())
	ctx, err := controllers.NewCtx(scheme)
	if err != nil {
		return err
	}

	greetingCtrl, err := greeting.NewController(ctx)
	if err != nil {
		return err
	}
	defer controllers.Shutdown(greetingCtrl)

	personCtrl, err := person.NewController(ctx)
	if err != nil {
		return err
	}
	defer controllers.Shutdown(personCtrl)

	initGroup, initCtx := errgroup.WithContext(ctx.Context)

	ctx.Log.Info("starting reflectors")
	initGroup.Go(func() error {
		ctx.GreetingInformer.Run(initCtx.Done())
		return nil
	})
	initGroup.Go(func() error {
		ctx.PersonInformer.Run(initCtx.Done())
		return nil
	})

	if !(cache.WaitForCacheSync(initCtx.Done(), ctx.GreetingInformer.HasSynced) && cache.WaitForCacheSync(initCtx.Done(), ctx.PersonInformer.HasSynced)) {
		return fmt.Errorf("Caches did not sync")
	}

	spawn := func(c controllers.Controller) {
		for i := 0; i < parallelism; i++ {
			initGroup.Go(func() error {
				c.Log().Info("thread start", "index", i)
				defer c.Log().Info("thread end", "index", i)
				controllers.Run(c)
				return nil
			})
		}
	}

	spawn(greetingCtrl)
	spawn(personCtrl)
	defer ctx.Log.Info("group terminated")
	return initGroup.Wait()
}
```

This was all quite nontrivial, and feels like it should be common to many controllers. Wiring up contexts, making sure various things are start in the correct order, delaying controller startup until all caches are synced.

Again, there could be abstractions or utilities that I don't know about which would do some of this for me, but I did look pretty hard. In particular, some of this straight up isn't easy to abstract well in go due to the lack of generics.

Also note that the above boilerplate was not simply something `foperator` handles internally - I didn't need to write this functionality in `foperator` either. By reusing generic abstractions like [Resource](https://typelevel.org/cats-effect/datatypes/resource.html) for resource acquisition and [Semaphore](https://typelevel.org/cats-effect/concurrency/semaphore.html) for managing parallelism, it's _hard to use them incorrectly_, in stark contrast to the above Go code.

As a final point on struggling to navigate what seemed like it should be a well-known path, I needed to write my own function to convert a custom resource object into a string key, for the workqueue. I found this:

> `MetaNamespaceKeyFunc` is a convenient default KeyFunc which knows how to make keys for API objects which implement meta.Interface

That seems relevant, but I couldn't get it to work (it failed at runtime). The documented interface also doesn't appear to exist, which didn't help. So I wrote my own `Keyable` interface and copied the implementation of `MetaNamespaceKeyFunc`. I've yet to find an object outside the core kubernetes resources which implements that.

# General oddities

These aren't super problematic but just surprising or awkward.

 - All the code I've seen imports k8s' `meta/v1` so it ends up as the symbol `v1`. That seems really bizarre - surely there are multiple APIs at v1, the version is the least relevant part of the whole name. Strange that people just use that default naming instead of aliasing it.
 - Reconcile: why am I given a request instead of an object?
 - Whoa, `context.TODO()` is a real method? I thought it was a doc placeholder.
 - Deciding out when to use pointers vs raw objects feels weird. It usually doesn't matter, unless you're mutating, but even then interior mutation typically works (since there's a pointer somewhere). It's strange to read that one of the benefits of using plain structs is they can't be mutated, since idiomatic go is full of mutation.
 - Go talks about "zero" values a lot, as in `Whatever{}`. Which threw me off when it wants a `time.Duration` which is _literally_ zero (it's just an int64 alias)

# Conclusion

I thought I knew roughly how this would go, based on my previous experience with Go. In all honestly, the use of code generation was better than I expected (it rarely got in the way), but **most other aspects were worse**. I'm not interested in trying to convince Go fans they've made a terrible mistake or anything like that, but I hope that this document serves as a good answer to the obvious question of "Why not just use the existing tools to write your operator?"
