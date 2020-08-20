package person

import (
	"context"
	"errors"

	"github.com/go-logr/logr"
	k8sErrors "k8s.io/apimachinery/pkg/api/errors"
	// "k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"
	// "sigs.k8s.io/controller-runtime/pkg/client"
	"github.com/timbertson/operator-bakeoff/controllers"

	samplev1alpha1 "github.com/timbertson/operator-bakeoff/api/v1alpha1"
)

// PersonController reconciles a Person object
type PersonController struct {
	log   logr.Logger
	ctx   controllers.Ctx
	queue workqueue.RateLimitingInterface
}

func (c *PersonController) Reconcile(req ctrl.Request) error {
	ctx := context.Background()
	log := c.log.WithValues("person", req.NamespacedName)

	person := &samplev1alpha1.Person{}
	if err := c.ctx.Client.Get(ctx, req.NamespacedName, person); err != nil {
		if k8sErrors.IsNotFound(err) {
			return nil
		}
		log.Error(err, "Get error")
		return err
	}

	if person.ObjectMeta.DeletionTimestamp != nil {
		if person.HasFinalizer() {
			// We only allow deletions once all references have been removed
			greetings, err := c.ctx.ListGreetings()
			if err != nil {
				return err
			}

			for _, greeting := range greetings {
				// we only care about live objects which reference this person
				if greeting.ObjectMeta.DeletionTimestamp == nil && greeting.References(*person) {
					return errors.New("This object is still referenced")
				}
			}
			person.RemoveFinalizer()
			log.Info("Removed finalizer", "person.ObjectMeta", person.ObjectMeta)
			if err := c.ctx.Client.Update(ctx, person); err != nil {
				return err
			}
		}
	}

	return nil
}

func setupController(c *PersonController) error {
	addId := func(obj interface{}) {
		controllers.AddId(c, obj)
	}
	controllers.AddHandler(c.ctx.PersonInformer, addId)
	return nil
}

func NewController(ctx controllers.Ctx) (*PersonController, error) {
	logger := ctrl.Log.WithName("PersonController")
	queue := workqueue.NewRateLimitingQueue(workqueue.DefaultControllerRateLimiter())
	c := &PersonController{
		log:   logger,
		ctx:   ctx,
		queue: queue,
	}
	err := setupController(c)
	return c, err
}

// Implementation of base Controller
func (c PersonController) Log() logr.Logger {
	return c.log
}

func (c PersonController) Queue() workqueue.RateLimitingInterface {
	return c.queue
}

func (c PersonController) Ctx() controllers.Ctx {
	return c.ctx
}
