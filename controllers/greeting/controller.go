package greeting

import (
	"fmt"
	"strings"

	"github.com/go-logr/logr"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/client-go/util/workqueue"
	ctrl "sigs.k8s.io/controller-runtime"

	samplev1alpha1 "github.com/timbertson/operator-bakeoff/api/v1alpha1"
	"github.com/timbertson/operator-bakeoff/controllers"
	"github.com/timbertson/operator-bakeoff/util"
)

type GreetingController struct {
	log   logr.Logger
	ctx   controllers.Ctx
	queue workqueue.RateLimitingInterface
}

func (c *GreetingController) Reconcile(req ctrl.Request) error {
	executionContext := c.ctx.Context
	log := c.log.WithValues("greeting", req.NamespacedName)

	_, exists, err := c.ctx.GreetingInformer.GetStore().GetByKey(req.NamespacedName.String())
	if err != nil {
		log.Error(err, "error fetching object from index for the specified key")
		return err
	}

	if !exists {
		log.Info("pod has gone")
		return nil
	}

	client := c.ctx.Client

	greeting := &samplev1alpha1.Greeting{}
	if err := client.Get(executionContext, req.NamespacedName, greeting); err != nil {
		if errors.IsNotFound(err) {
			return nil
		}
		log.Error(err, "Get error")
		return err
	}

	matchedPeople := []samplev1alpha1.Person{}

	if greeting.Spec.Surname != "" {
		peopleUnsorted, err := c.ctx.ListPeople()
		if err != nil {
			return err
		}

		names := []string{}
		ids := []string{}
		for _, person := range samplev1alpha1.SortByFirstName(peopleUnsorted) {
			log.Info("Checking", "person", person.Spec, "greeting", greeting.Spec)
			if person.ObjectMeta.DeletionTimestamp != nil {
				continue
			}
			if person.Spec.Surname == greeting.Spec.Surname {
				matchedPeople = append(matchedPeople, person)
				names = append(names, person.Spec.FirstName)
				ids = append(ids, person.ObjectMeta.Name)
			}
		}
		greeting.Status.People = ids
		greeting.Status.Message = fmt.Sprintf(
			"Hello to the %s family: %s",
			greeting.Spec.Surname,
			strings.Join(names, ", "),
		)

	} else {
		greeting.Status.Message = fmt.Sprintf("Hello, %s", greeting.Spec.Name)
		greeting.Status.People = []string{}
	}

	log.Info("Updating...", "status", greeting.Status)

	if err := client.Status().Update(executionContext, greeting); err != nil {
		return err
	}

	// TODO update in parallel?
	for _, person := range matchedPeople {
		person.AddFinalizer()
		if err := client.Update(executionContext, &person); err != nil {
			return err
		}
	}

	log.Info("Updated!", "greeting", greeting)

	return nil
}

func setupController(c *GreetingController) error {
	logger := c.log
	addId := func(obj interface{}) {
		controllers.AddId(c, obj)
	}

	// reconcile self when greeting changes
	controllers.AddHandler(c.ctx.GreetingInformer, addId)

	// reconcile greeting when a related person changes
	controllers.AddHandler(c.ctx.PersonInformer, func(personObj interface{}) {
		// Given a person update, figure out what greetings need to be reconciled
		logger.Info("Person updated...", "person", personObj)
		person, success := personObj.(*samplev1alpha1.Person)
		if !success {
			c.ctx.Cancel(util.CastError("Person", personObj))
			return
		}

		needsUpdate := func(greeting *samplev1alpha1.Greeting) bool {
			if person.ObjectMeta.DeletionTimestamp != nil {
				return greeting.References(*person)
			} else {
				shouldAdd := greeting.Matches(*person) && !greeting.References(*person)
				shouldRemove := !greeting.Matches(*person) && greeting.References(*person)
				if shouldAdd || shouldRemove {
					var desc string
					if shouldAdd {
						desc = "add"
					} else {
						desc = "remove"
					}
					logger.Info("Greeting needs update", "action", desc, "person", person)
					return true
				} else {
					return false
				}
			}
		}

		greetings, err := c.ctx.ListGreetings()
		if err != nil {
			c.ctx.Cancel(fmt.Errorf("Can't list greetings: %v", err))
			return
		}
		for _, greeting := range greetings {
			if needsUpdate(&greeting) {
				addId(greeting)
			}
		}
	})

	return nil
}

func NewController(ctx controllers.Ctx) (*GreetingController, error) {
	logger := ctrl.Log.WithName("GreetingController")
	fmt.Printf("logger %v", logger)

	queue := workqueue.NewRateLimitingQueue(workqueue.DefaultControllerRateLimiter())
	c := &GreetingController{
		log:   logger,
		ctx:   ctx,
		queue: queue,
	}
	err := setupController(c)
	return c, err
}

// Implementation of base Controller
func (c GreetingController) Log() logr.Logger {
	return c.log
}

func (c GreetingController) Queue() workqueue.RateLimitingInterface {
	return c.queue
}

func (c GreetingController) Ctx() controllers.Ctx {
	return c.ctx
}
