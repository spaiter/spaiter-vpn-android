// Package runlog is the process log sink of the core: one file in the app's
// cache, shared by the foreground and the background process, that keeps only
// its tail across restarts and also receives whatever the runtime prints to
// stderr (panics, the Go runtime's own complaints). The GUI reads the same
// stream through GUIWriter.
package runlog

import (
	"fmt"
	"io"
	"log"
	"os"
	"sync"
	"sync/atomic"
)

// Writer is the sink Setup installs; nil before Setup. The core's platform log
// writers and the UI's "clear log" both go through it.
var Writer *Sink

// TruncateOnStart decides whether Setup trims the file to maxSize. Only the
// background process should: the foreground opens the same file and must not
// cut what the service is writing.
var TruncateOnStart = true

// GUIWriter, when set before Setup, receives every line as well.
var GUIWriter io.Writer

var disabled atomic.Bool

// SetEnabled turns writing on or off without closing anything.
func SetEnabled(enabled bool) {
	disabled.Store(!enabled)
}

// Setup opens (or creates) the log file at path, keeps at most the last
// maxSize bytes of it when TruncateOnStart is set, redirects stderr into it,
// and points the standard logger at the sink. A second call is a no-op.
func Setup(maxSize int, path string) error {
	if Writer != nil {
		return nil
	}
	file, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		err = fmt.Errorf("open log file: %w", err)
		log.Println(err)
	} else {
		if TruncateOnStart {
			if trimErr := keepTail(file, int64(maxSize)); trimErr != nil {
				log.Println("trim log file:", trimErr)
			}
		}
		redirectStderr(file)
	}
	sink := &Sink{}
	if GUIWriter != nil {
		sink.targets = append(sink.targets, GUIWriter)
	}
	if file != nil {
		sink.targets = append(sink.targets, file)
		sink.file = file
	}
	Writer = sink
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.SetOutput(sink)
	return err
}

// keepTail leaves only the last limit bytes of the file in place. The file is
// locked while that happens because another process may be appending.
func keepTail(file *os.File, limit int64) error {
	if limit <= 0 {
		return nil
	}
	lockFile(file)
	defer unlockFile(file)
	size, err := file.Seek(0, io.SeekEnd)
	if err != nil || size <= limit {
		return err
	}
	if _, err = file.Seek(size-limit, io.SeekStart); err != nil {
		return err
	}
	tail, err := io.ReadAll(file)
	if err != nil {
		return err
	}
	if err = file.Truncate(0); err != nil {
		return err
	}
	_, err = file.Write(tail)
	return err
}

// Sink fans one write out to the GUI and the file. Writes to the file are
// serialised with an advisory lock so two processes do not interleave lines.
type Sink struct {
	mu      sync.Mutex
	targets []io.Writer
	file    *os.File
}

func (s *Sink) Write(p []byte) (int, error) {
	if disabled.Load() {
		return len(p), nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, target := range s.targets {
		if target == s.file && s.file != nil {
			lockFile(s.file)
			_, _ = s.file.Write(p)
			unlockFile(s.file)
			continue
		}
		_, _ = target.Write(p)
	}
	return len(p), nil
}

// Truncate empties the log file.
func (s *Sink) Truncate() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file != nil {
		_ = s.file.Truncate(0)
	}
}

// Close closes the log file; the GUI writer is not ours to close.
func (s *Sink) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file != nil {
		return s.file.Close()
	}
	return nil
}
