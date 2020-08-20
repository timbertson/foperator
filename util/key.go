package util

import (
	"fmt"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func QueueKey(meta metav1.ObjectMeta) string {
	return fmt.Sprintf("%s/%s", meta.Namespace, meta.Name)
}
