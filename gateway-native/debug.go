package hmxgateway

import (
	"fmt"
	"os"
)

func debugLogger(tag string) func(string, ...any) {
	if os.Getenv("HMX_DEBUG") == "" {
		return func(string, ...any) {}
	}
	return func(f string, a ...any) { fmt.Printf("[%s] "+f+"\n", append([]any{tag}, a...)...) }
}
