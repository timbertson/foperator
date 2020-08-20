package util

import (
	"fmt"
)

func CastError(expected string, obj interface{}) error {
	return fmt.Errorf("Can't cast object to Person, it is a %T: %v", obj, obj)
}
