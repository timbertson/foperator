package controllers

import (
	"context"

	"k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/fields"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	// utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	// "k8s.io/apimachinery/pkg/util/wait"
	"github.com/go-logr/logr"
	"k8s.io/client-go/tools/cache"
	"k8s.io/client-go/tools/clientcmd"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/apiutil"

	samplev1alpha1 "github.com/timbertson/operator-bakeoff/api/v1alpha1"
	"github.com/timbertson/operator-bakeoff/util"
)

type Ctx struct {
	Log              logr.Logger
	cancelFn         context.CancelFunc
	Context          context.Context
	Scheme           *runtime.Scheme
	Client           client.Client
	PersonInformer   cache.SharedInformer
	GreetingInformer cache.SharedInformer
}

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

func (ctx Ctx) ListPeople() ([]samplev1alpha1.Person, error) {
	objs := ctx.PersonInformer.GetStore().List()
	result := make([]samplev1alpha1.Person, len(objs))
	for i, obj := range objs {
		typed, success := obj.(*samplev1alpha1.Person)
		if !success {
			return result, util.CastError("Person", obj)
		}
		result[i] = *typed
	}
	return result, nil
}

func (ctx Ctx) ListGreetings() ([]samplev1alpha1.Greeting, error) {
	objs := ctx.GreetingInformer.GetStore().List()
	result := make([]samplev1alpha1.Greeting, len(objs))
	for i, obj := range objs {
		typed, success := obj.(*samplev1alpha1.Greeting)
		if !success {
			return result, util.CastError("Greeting", obj)
		}
		result[i] = *typed
	}
	return result, nil
}

func (ctx Ctx) Cancel(err error) {
	ctx.Log.Error(err, "Cancelling")
	ctx.cancelFn()
}
