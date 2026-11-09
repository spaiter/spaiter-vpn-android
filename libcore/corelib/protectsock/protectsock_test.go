package protectsock

import (
	"net"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

// A client passes one socket and gets 1 back when protect succeeds, 0 when it
// fails; the descriptor protect sees is a working duplicate of the client's.
func TestServeProtectsReceivedDescriptor(t *testing.T) {
	path := filepath.Join(t.TempDir(), "protect")
	seen := make(chan int, 1)
	closer := Serve(path, func(fd int) error {
		seen <- fd
		return nil
	})
	if closer == nil {
		t.Fatal("listen failed")
	}
	defer closer.Close()

	verdict := passSocket(t, path)
	if verdict != 1 {
		t.Fatalf("verdict = %d", verdict)
	}
	select {
	case fd := <-seen:
		if fd <= 0 {
			t.Fatalf("protect saw fd %d", fd)
		}
	case <-time.After(time.Second):
		t.Fatal("protect was not called")
	}
}

func TestServeReportsFailure(t *testing.T) {
	path := filepath.Join(t.TempDir(), "protect")
	closer := Serve(path, func(int) error { return syscall.EPERM })
	defer closer.Close()
	if verdict := passSocket(t, path); verdict != 0 {
		t.Fatalf("verdict = %d", verdict)
	}
}

func passSocket(t *testing.T, path string) byte {
	t.Helper()
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	sock, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(sock)
	conn.SetDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := conn.WriteMsgUnix(nil, syscall.UnixRights(sock), nil); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 1)
	if _, err := conn.Read(reply); err != nil {
		t.Fatal(err)
	}
	return reply[0]
}
