package v1alpha1

import (
	"sort"

	"github.com/timbertson/operator-bakeoff/util"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// PersonSpec defines the desired state of Person
type PersonSpec struct {
	FirstName string `json:"firstName,omitempty"`
	Surname   string `json:"surname,omitempty"`
}

// PersonStatus defines the observed state of Person
type PersonStatus struct {
}

// +kubebuilder:object:root=true
// +kubebuilder:subresource:status

// Person is the Schema for the people API
type Person struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata"`

	Spec   PersonSpec   `json:"spec"`
	Status PersonStatus `json:"status"`
}

// +kubebuilder:object:root=true

// PersonList contains a list of Person
type PersonList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata"`
	Items           []Person `json:"items"`
}

func init() {
	SchemeBuilder.Register(&Person{}, &PersonList{})
}

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

func (person *Person) AddFinalizer() {
	person.ObjectMeta.Finalizers = append(person.ObjectMeta.Finalizers, FinalizerName)
}

func (person *Person) RemoveFinalizer() {
	newFinalizers := []string{}
	for _, finalizer := range person.ObjectMeta.Finalizers {
		if finalizer != FinalizerName {
			newFinalizers = append(newFinalizers, finalizer)
		}
	}
	person.ObjectMeta.Finalizers = newFinalizers
}

func (person *Person) HasFinalizer() bool {
	return ContainsString(person.ObjectMeta.Finalizers, FinalizerName)
}

func (person Person) QueueKey() string {
	return util.QueueKey(person.ObjectMeta)
}
