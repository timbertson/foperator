package v1alpha1

import (
	"github.com/timbertson/operator-bakeoff/util"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type GreetingSpec struct {
	Name    string `json:"name,omitempty"`
	Surname string `json:"surname,omitempty"`
}

type GreetingStatus struct {
	Message string `json:"message,omitEmpty"`

	// +kubebuilder:validation:Optional
	People []string `json:"people,omitEmpty"`
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
type Greeting struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   GreetingSpec   `json:"spec,omitempty"`
	Status GreetingStatus `json:"status,omitempty"`
}

// +kubebuilder:object:root=true
type GreetingList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []Greeting `json:"items"`
}

func init() {
	SchemeBuilder.Register(&Greeting{}, &GreetingList{})
}

// should this greeting match this person?
func (greeting *Greeting) Matches(person Person) bool {
	return greeting.Spec.Surname == person.Spec.Surname
}

func ContainsString(s []string, e string) bool {
	for _, a := range s {
		if a == e {
			return true
		}
	}
	return false
}

// does this greeting's status currently reference this person?
func (greeting *Greeting) References(person Person) bool {
	return ContainsString(greeting.Status.People, person.ObjectMeta.Name)
}

func (greeting Greeting) QueueKey() string {
	return util.QueueKey(greeting.ObjectMeta)
}
