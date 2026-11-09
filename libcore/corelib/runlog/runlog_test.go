package runlog

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func TestKeepTailKeepsOnlyTheLastBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "log")
	if err := os.WriteFile(path, []byte("0123456789"), 0o644); err != nil {
		t.Fatal(err)
	}
	file, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if err := keepTail(file, 4); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(path)
	if string(got) != "6789" {
		t.Fatalf("tail = %q", got)
	}
	if err := keepTail(file, 100); err != nil {
		t.Fatal(err)
	}
	got, _ = os.ReadFile(path)
	if string(got) != "6789" {
		t.Fatalf("a file under the limit must be left alone, got %q", got)
	}
}

func TestSinkHonoursEnabled(t *testing.T) {
	var out bytes.Buffer
	sink := &Sink{targets: []io.Writer{&out}}
	SetEnabled(false)
	t.Cleanup(func() { SetEnabled(true) })
	sink.Write([]byte("hidden"))
	if out.Len() != 0 {
		t.Fatalf("disabled sink wrote %q", out.String())
	}
	SetEnabled(true)
	sink.Write([]byte("shown"))
	if out.String() != "shown" {
		t.Fatalf("enabled sink wrote %q", out.String())
	}
}
