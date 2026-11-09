package probe

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestURLTestSendsOneHeadAndIgnoresRedirects(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Method != http.MethodHead {
			t.Errorf("method = %s", r.Method)
		}
		http.Redirect(w, r, "/elsewhere", http.StatusFound)
	}))
	defer server.Close()
	if _, err := URLTest(context.Background(), server.Client(), server.URL); err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 1 {
		t.Fatalf("requests = %d", requests.Load())
	}
}

func TestURLTestModeRequestCounts(t *testing.T) {
	for _, tc := range []struct {
		mode Mode
		want int32
	}{{ModeFirstHandshake, 1}, {ModeHandshake, 2}, {ModeRTT, 2}} {
		var requests atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			requests.Add(1)
			w.WriteHeader(http.StatusNoContent)
		}))
		if _, err := URLTestMode(context.Background(), server.Client(), server.URL, tc.mode); err != nil {
			t.Fatal(err)
		}
		server.Close()
		if requests.Load() != tc.want {
			t.Fatalf("mode %d: requests = %d, want %d", tc.mode, requests.Load(), tc.want)
		}
	}
	if _, err := URLTestMode(context.Background(), http.DefaultClient, "http://127.0.0.1:1", Mode(9)); err == nil {
		t.Fatal("unknown mode must be rejected")
	}
}

func TestURLTestHonoursContext(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		time.Sleep(200 * time.Millisecond)
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := URLTest(ctx, server.Client(), server.URL); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v", err)
	}
}

func TestRTTFallsBackOnPartialTrace(t *testing.T) {
	base := time.Unix(0, 0)
	if got := (timing{base, base.Add(57 * time.Millisecond)}).rtt(time.Second); got != 57*time.Millisecond {
		t.Fatalf("rtt = %s", got)
	}
	if got := (timing{}).rtt(time.Second); got != time.Second {
		t.Fatalf("fallback = %s", got)
	}
}

func TestTCPPingClosesTheConnection(t *testing.T) {
	client, server := net.Pipe()
	closed := make(chan struct{})
	go func() {
		defer close(closed)
		_, _ = server.Read(make([]byte, 1))
		server.Close()
	}()
	if _, err := TCPPing(context.Background(), func(context.Context, string, string) (net.Conn, error) { return client, nil }, "x:1"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("connection left open")
	}
}
