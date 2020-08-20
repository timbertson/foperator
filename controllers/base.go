package controllers

import (
	"fmt"

	"github.com/go-logr/logr"
	// "k8s.io/apimachinery/pkg/api/meta"
	// metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/cache"
	"k8s.io/client-go/util/workqueue"
	controllerRuntime "sigs.k8s.io/controller-runtime"
)

type Controller interface {
	Ctx() Ctx
	Log() logr.Logger
	Queue() workqueue.RateLimitingInterface
	Reconcile(controllerRuntime.Request) error
}

type Handler func(interface{})

func AddHandler(informer cache.SharedInformer, handler Handler) {
	informer.AddEventHandler(cache.ResourceEventHandlerFuncs{
		AddFunc: handler,
		UpdateFunc: func(old interface{}, new interface{}) {
			handler(new)
		},
		DeleteFunc: handler,
	})
}

type Keyable interface {
	QueueKey() string
}

func AddId(ctrl Controller, obj interface{}) {
	keyable, ok := obj.(Keyable)
	if !ok {
		ctrl.Ctx().Cancel(fmt.Errorf("not keyable (type %T): %v", obj, obj))
		return
	}

	key := keyable.QueueKey()
	ctrl.Log().Info("Adding", "key", key)
	ctrl.Queue().Add(key)
}

func handleErr(ctrl Controller, err error, key interface{}) {
	if err == nil {
		ctrl.Queue().Forget(key)
		return
	}

	ctrl.Log().Error(err, "error during sync", "key", key.(string))
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
	c.Log().Info("Run loop...")
	for processNextItem(c) {
	}
}

func Shutdown(c Controller) {
	defer c.Queue().ShutDown()
}
