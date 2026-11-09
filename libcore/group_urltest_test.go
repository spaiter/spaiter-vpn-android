package libcore

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type fakeGroupURLTestResolver struct {
	lookups atomic.Int32
	err     error
}

func (r *fakeGroupURLTestResolver) Raw() bool            { return false }
func (r *fakeGroupURLTestResolver) NetworkHandle() int64 { return 0 }

func (r *fakeGroupURLTestResolver) Lookup(ctx *ExchangeContext, network, domain string) error {
	r.lookups.Add(1)
	return r.err
}

func (*fakeGroupURLTestResolver) Exchange(*ExchangeContext, []byte) error {
	return errors.New("unexpected raw DNS exchange")
}

func TestNewGroupURLTesterKeepsDomainForOutboundResolution(t *testing.T) {
	resolver := &fakeGroupURLTestResolver{err: errors.New("local DNS is unavailable")}
	tester, err := NewGroupURLTester("https://connectivity.example/check", 1000, 1, 50, resolver)
	if err != nil {
		t.Fatal(err)
	}
	if got := resolver.lookups.Load(); got != 0 {
		t.Fatalf("lookups = %d", got)
	}
	if got := tester.destination.Fqdn; got != "connectivity.example" {
		t.Fatalf("destination = %v", got)
	}
	if tester.destination.Addr.IsValid() {
		t.Fatalf("destination address = %v", tester.destination.Addr)
	}
	if tester.destination.Port != 443 {
		t.Fatalf("port = %d", tester.destination.Port)
	}
	if tester.host != "connectivity.example" {
		t.Fatalf("host = %q", tester.host)
	}
}

func TestNewGroupURLTesterSkipsLookupForIPLiteral(t *testing.T) {
	resolver := &fakeGroupURLTestResolver{err: errors.New("lookup should not run")}
	tester, err := NewGroupURLTester("http://[2001:db8::10]/check", 1000, 1, 50, resolver)
	if err != nil {
		t.Fatal(err)
	}
	if got := resolver.lookups.Load(); got != 0 {
		t.Fatalf("lookups = %d", got)
	}
	if got := tester.destination.Addr; got != netip.MustParseAddr("2001:db8::10") {
		t.Fatalf("destination = %v", got)
	}
	if tester.destination.Port != 80 {
		t.Fatalf("port = %d", tester.destination.Port)
	}
}

type groupURLTestDialer struct {
	destination chan M.Socksaddr
	host        chan string
}

func (d *groupURLTestDialer) DialContext(_ context.Context, _ string, destination M.Socksaddr) (net.Conn, error) {
	client, server := net.Pipe()
	d.destination <- destination
	go func() {
		defer server.Close()
		request, err := http.ReadRequest(bufio.NewReader(server))
		if err != nil {
			d.host <- ""
			return
		}
		d.host <- request.Host
		_ = request.Body.Close()
		_, _ = server.Write([]byte("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"))
	}()
	return client, nil
}

func (*groupURLTestDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("unexpected packet listener")
}

func TestGroupURLTesterPassesDomainToOutboundAndPreservesHost(t *testing.T) {
	tester := &GroupURLTester{
		link:        "http://connectivity.example/check",
		host:        "connectivity.example",
		destination: M.ParseSocksaddrHostPort("connectivity.example", 80),
		timeout:     time.Second,
	}
	dialer := &groupURLTestDialer{
		destination: make(chan M.Socksaddr, 1),
		host:        make(chan string, 1),
	}
	if _, err := tester.testOnce(t.Context(), dialer); err != nil {
		t.Fatal(err)
	}
	if got := <-dialer.destination; got != tester.destination {
		t.Fatalf("destination = %v", got)
	}
	if got := <-dialer.host; got != tester.host {
		t.Fatalf("Host = %q", got)
	}
}

func TestRunGroupURLTestStartRetrySucceedsWithFreshAttempt(t *testing.T) {
	var events []string
	latency, err := runGroupURLTestStartRetry(func() (_ int32, err error) {
		events = append(events, "start")
		defer func() { events = append(events, "cleanup") }()
		if len(events) == 1 {
			return -1, fmt.Errorf("%w: temporary failure", errGroupURLTestStart)
		}
		return 42, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if latency != 42 {
		t.Fatalf("latency = %d", latency)
	}
	wantEvents := []string{"start", "cleanup", "start", "cleanup"}
	if !slices.Equal(events, wantEvents) {
		t.Fatalf("events = %v, want %v", events, wantEvents)
	}
}

func TestRunGroupURLTestStartRetryReturnsFinalStartError(t *testing.T) {
	errorsByAttempt := []error{
		fmt.Errorf("%w: first failure", errGroupURLTestStart),
		fmt.Errorf("%w: final failure", errGroupURLTestStart),
	}
	var calls int
	latency, err := runGroupURLTestStartRetry(func() (int32, error) {
		result := errorsByAttempt[calls]
		calls++
		return -1, result
	})
	if latency != -1 || !errors.Is(err, errorsByAttempt[1]) || calls != 2 {
		t.Fatalf("latency = %d, error = %v, calls = %d", latency, err, calls)
	}
}

func TestRunGroupURLTestStartRetryDoesNotRetryOtherFailures(t *testing.T) {
	wantErr := errors.New("create or probe failed")
	var calls int
	latency, err := runGroupURLTestStartRetry(func() (int32, error) {
		calls++
		return -1, wantErr
	})
	if latency != -1 || !errors.Is(err, wantErr) || calls != 1 {
		t.Fatalf("latency = %d, error = %v, calls = %d", latency, err, calls)
	}
}

func TestShouldRetryProbeOnlyFastTransientErrors(t *testing.T) {
	timeout := 2 * time.Second
	if !shouldRetryProbe(syscall.ENETUNREACH, 100*time.Millisecond, timeout) {
		t.Fatal("fast network error was not retryable")
	}
	if !shouldRetryProbe(&net.DNSError{Err: "temporary", IsTemporary: true}, 100*time.Millisecond, timeout) {
		t.Fatal("temporary DNS error was not retryable")
	}
	if shouldRetryProbe(syscall.ECONNREFUSED, 100*time.Millisecond, timeout) {
		t.Fatal("connection refusal was retryable")
	}
	if shouldRetryProbe(syscall.ENETUNREACH, time.Second, timeout) {
		t.Fatal("slow network error was retryable")
	}
	if shouldRetryProbe(context.Canceled, 100*time.Millisecond, timeout) {
		t.Fatal("cancellation was retryable")
	}
}
