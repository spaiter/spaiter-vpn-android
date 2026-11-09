// Package protectsock is the socket-protection service of the VPN process.
// Android lets only the process that owns the VpnService exclude a socket from
// the tunnel (VpnService.protect), so helpers that live in another process hand
// their sockets over a unix socket, one descriptor per connection, and get a
// one-byte verdict back: 1 protected, 0 failed.
package protectsock

import (
	"io"
	"log"
	"net"
	"os"
	"syscall"
)

// Serve listens on path and protects every descriptor it receives with
// protect. It returns the listener to close, or nil when it could not listen.
func Serve(path string, protect func(fd int) error) io.Closer {
	_ = os.Remove(path)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		log.Println("protect server: listen:", err)
		return nil
	}
	// other apps' processes of the same uid connect here; the directory is private
	_ = os.Chmod(path, 0o777)
	go func() {
		for {
			conn, err := listener.AcceptUnix()
			if err != nil {
				return
			}
			go handle(conn, protect)
		}
	}()
	return listener
}

func handle(conn *net.UnixConn, protect func(fd int) error) {
	defer conn.Close()
	fd, err := receiveFd(conn)
	if err != nil {
		log.Println("protect server: receive fd:", err)
		return
	}
	defer syscall.Close(fd)
	verdict := byte(1)
	if err := protect(fd); err != nil {
		log.Println("protect server: protect:", err)
		verdict = 0
	}
	_, _ = conn.Write([]byte{verdict})
}

// receiveFd reads exactly one descriptor passed as SCM_RIGHTS.
func receiveFd(conn *net.UnixConn) (int, error) {
	oob := make([]byte, syscall.CmsgSpace(4))
	_, oobn, _, _, err := conn.ReadMsgUnix(nil, oob)
	if err != nil {
		return 0, err
	}
	messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return 0, err
	}
	if len(messages) != 1 {
		return 0, errCount("control messages", len(messages))
	}
	fds, err := syscall.ParseUnixRights(&messages[0])
	if err != nil {
		return 0, err
	}
	if len(fds) != 1 {
		return 0, errCount("descriptors", len(fds))
	}
	return fds[0], nil
}

type countError struct {
	what string
	n    int
}

func errCount(what string, n int) error { return &countError{what, n} }
func (e *countError) Error() string {
	return "protect server: expected one of " + e.what + ", got " + itoa(e.n)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var digits []byte
	for ; n > 0; n /= 10 {
		digits = append([]byte{byte('0' + n%10)}, digits...)
	}
	return string(digits)
}
