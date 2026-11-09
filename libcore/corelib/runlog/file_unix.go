//go:build linux || android

package runlog

import (
	"os"
	"syscall"
)

func lockFile(file *os.File)   { _ = syscall.Flock(int(file.Fd()), syscall.LOCK_EX) }
func unlockFile(file *os.File) { _ = syscall.Flock(int(file.Fd()), syscall.LOCK_UN) }

// redirectStderr makes fd 2 point at the log file, so runtime panics and
// anything a C library prints end up in the log instead of being lost.
func redirectStderr(file *os.File) {
	_ = syscall.Dup3(int(file.Fd()), int(os.Stderr.Fd()), 0)
}
