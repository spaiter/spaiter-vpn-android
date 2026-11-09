package libcore

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestLookupURLTestHostUsesConfiguredResolver(t *testing.T) {
	var resolvedHost string
	addresses, err := lookupURLTestHost(t.Context(), "https://connectivity.example/check", func(_ context.Context, host string) ([]netip.Addr, error) {
		resolvedHost = host
		return []netip.Addr{netip.MustParseAddr("192.0.2.10")}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if resolvedHost != "connectivity.example" {
		t.Fatalf("resolved host = %q", resolvedHost)
	}
	if !slices.Equal(addresses, []netip.Addr{netip.MustParseAddr("192.0.2.10")}) {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestLookupURLTestHostSkipsIPLiteral(t *testing.T) {
	addresses, err := lookupURLTestHost(t.Context(), "https://[2001:db8::10]/check", func(context.Context, string) ([]netip.Addr, error) {
		t.Fatal("lookup called for IP literal")
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if addresses != nil {
		t.Fatalf("addresses = %v", addresses)
	}
}

func TestLookupURLTestHostFailsWithoutAddresses(t *testing.T) {
	_, err := lookupURLTestHost(t.Context(), "https://connectivity.example/check", func(context.Context, string) ([]netip.Addr, error) {
		return nil, nil
	})
	if err == nil {
		t.Fatal("expected empty DNS response to fail")
	}
}

func TestLookupURLTestHostHonorsContextWhenResolverBlocks(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	release := make(chan struct{})
	defer close(release)

	started := time.Now()
	_, err := lookupURLTestHost(ctx, "https://connectivity.example/check", func(context.Context, string) ([]netip.Addr, error) {
		<-release
		return nil, nil
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected deadline exceeded, got %v", err)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("blocking lookup returned too late: %v", elapsed)
	}
}

func TestLookupURLTestHostRecoversResolverPanic(t *testing.T) {
	_, err := lookupURLTestHost(t.Context(), "https://connectivity.example/check", func(context.Context, string) ([]netip.Addr, error) {
		panic("resolver panic")
	})
	if err == nil || !strings.Contains(err.Error(), "resolver panic") {
		t.Fatalf("expected resolver panic error, got %v", err)
	}
}

func TestRunURLTestAsyncHonorsContextWhenTesterBlocks(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	release := make(chan struct{})
	workerDone := make(chan struct{})

	_, err := runURLTestAsync(ctx, "blocking URLTest", func() (int32, error) {
		defer close(workerDone)
		<-release
		return 1, nil
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected deadline exceeded, got %v", err)
	}
	close(release)
	select {
	case <-workerDone:
	case <-time.After(time.Second):
		t.Fatal("late URLTest worker did not finish")
	}
}

func TestRunURLTestAsyncRecoversTesterPanic(t *testing.T) {
	_, err := runURLTestAsync(t.Context(), "panicking URLTest", func() (int32, error) {
		panic("tester panic")
	})
	if err == nil || !strings.Contains(err.Error(), "tester panic") {
		t.Fatalf("expected tester panic error, got %v", err)
	}
}

func TestRunURLTestAttemptsSucceedsAfterFailures(t *testing.T) {
	wantErr := errors.New("probe failed")
	var calls int
	latency, err := runURLTestAttempts(t.Context(), 5, 0, func() (int32, error) {
		calls++
		if calls < 3 {
			return -1, wantErr
		}
		return 42, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if latency != 42 || calls != 3 {
		t.Fatalf("latency = %d, calls = %d", latency, calls)
	}
}

func TestRunURLTestAttemptsReturnsLastError(t *testing.T) {
	errorsByAttempt := []error{errors.New("first"), errors.New("second"), errors.New("last")}
	var calls int
	latency, err := runURLTestAttempts(t.Context(), 3, 0, func() (int32, error) {
		result := errorsByAttempt[calls]
		calls++
		return -1, result
	})
	if latency != -1 || !errors.Is(err, errorsByAttempt[2]) || calls != 3 {
		t.Fatalf("latency = %d, error = %v, calls = %d", latency, err, calls)
	}
}

func TestRunURLTestAttemptsClampsAttemptCount(t *testing.T) {
	var calls int
	_, _ = runURLTestAttempts(t.Context(), 10, 0, func() (int32, error) {
		calls++
		return -1, errors.New("failed")
	})
	if calls != 5 {
		t.Fatalf("calls = %d, want 5", calls)
	}
}

func TestRunURLTestAttemptsHonorsCancellationDuringPause(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	wantErr := errors.New("failed")
	var calls int
	_, err := runURLTestAttempts(ctx, 5, 1000, func() (int32, error) {
		calls++
		cancel()
		return -1, wantErr
	})
	if !errors.Is(err, context.Canceled) || calls != 1 {
		t.Fatalf("error = %v, calls = %d", err, calls)
	}
}

func TestRemainingURLTestTimeoutUsesSharedDeadline(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()
	time.Sleep(20 * time.Millisecond)

	remaining, err := remainingURLTestTimeout(ctx, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if remaining >= 200*time.Millisecond || remaining <= 0 {
		t.Fatalf("unexpected remaining timeout: %v", remaining)
	}
}

func TestURLTestConnectionSetClosesTrackedConnections(t *testing.T) {
	connections := newURLTestConnectionSet()
	client, server := net.Pipe()
	t.Cleanup(func() { _ = server.Close() })
	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return client, nil
		},
	}
	trackURLTestConnections(transport, connections, time.Second)

	conn, err := transport.DialContext(t.Context(), "tcp", "connectivity.example:443")
	if err != nil {
		t.Fatal(err)
	}
	connections.closeAll()
	if _, err = conn.Write([]byte("test")); err == nil {
		t.Fatal("tracked connection remained open")
	}

	lateClient, lateServer := net.Pipe()
	t.Cleanup(func() { _ = lateServer.Close() })
	lateConn := connections.track(lateClient)
	if _, err = lateConn.Write([]byte("test")); err == nil {
		t.Fatal("connection tracked after cleanup remained open")
	}
}

func TestDialResolvedURLTestAddressesUsesResolvedIPs(t *testing.T) {
	wantErr := errors.New("first address failed")
	var dialed []string
	client, server := net.Pipe()
	t.Cleanup(func() { _ = client.Close() })
	t.Cleanup(func() { _ = server.Close() })

	conn, err := dialResolvedURLTestAddresses(
		t.Context(),
		"tcp",
		"connectivity.example:443",
		[]netip.Addr{netip.MustParseAddr("192.0.2.10"), netip.MustParseAddr("2001:db8::10")},
		func(_ context.Context, _ string, address string) (net.Conn, error) {
			dialed = append(dialed, address)
			if len(dialed) == 1 {
				return nil, wantErr
			}
			return client, nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	if conn != client {
		t.Fatal("unexpected connection")
	}
	wantDialed := []string{"192.0.2.10:443", "[2001:db8::10]:443"}
	if !slices.Equal(dialed, wantDialed) {
		t.Fatalf("dialed = %v, want %v", dialed, wantDialed)
	}
}
