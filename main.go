package main

import (
	"fmt"
	"os"

	"golang.org/x/sync/errgroup"
	"k8s.io/apimachinery/pkg/runtime"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	clientgoscheme "k8s.io/client-go/kubernetes/scheme"
	_ "k8s.io/client-go/plugin/pkg/client/auth/gcp"
	"k8s.io/client-go/tools/cache"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"

	samplev1alpha1 "github.com/timbertson/operator-bakeoff/api/v1alpha1"
	"github.com/timbertson/operator-bakeoff/controllers"
	"github.com/timbertson/operator-bakeoff/controllers/greeting"
	"github.com/timbertson/operator-bakeoff/controllers/person"
	// +kubebuilder:scaffold:imports
)

var (
	scheme   = runtime.NewScheme()
	setupLog = ctrl.Log.WithName("setup")
)

func init() {
	utilruntime.Must(clientgoscheme.AddToScheme(scheme))

	utilruntime.Must(samplev1alpha1.AddToScheme(scheme))
	// +kubebuilder:scaffold:scheme
}

func main() {
	defer utilruntime.HandleCrash()
	err := RunControllers(1)
	if err != nil {
		setupLog.Error(err, "fatal")
	}
	os.Exit(1)
}

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
		ctx.Log.Info("GreetingInformer done")
		return nil
	})
	initGroup.Go(func() error {
		ctx.PersonInformer.Run(initCtx.Done())
		ctx.Log.Info("PersonInformer done")
		return nil
	})

	if !(cache.WaitForCacheSync(initCtx.Done(), ctx.GreetingInformer.HasSynced) && cache.WaitForCacheSync(initCtx.Done(), ctx.PersonInformer.HasSynced)) {
		return fmt.Errorf("Caches did not sync")
	}
	ctx.Log.Info("starting controllers")

	spawn := func(c controllers.Controller) {
		c.Log().Info("starting controller")
		fmt.Printf("test %v\n", c.Log())
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
