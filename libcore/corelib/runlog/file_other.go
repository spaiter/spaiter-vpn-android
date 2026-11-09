//go:build !linux && !android

package runlog

import "os"

func lockFile(*os.File)       {}
func unlockFile(*os.File)     {}
func redirectStderr(*os.File) {}
