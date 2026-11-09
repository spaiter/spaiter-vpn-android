package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"libcore/device"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
	"libcore/corelib/probe"
)

const (
	defaultURLTestLink = "https://www.gstatic.com/generate_204"
	probeRetryDelay    = 250 * time.Millisecond
)

var errGroupURLTestStart = errors.New("start group URLTest service")

// GroupURLTester reuses the same test endpoint for every temporary profile
// instance in a group run. Domain endpoints are resolved through each tested
// outbound instead of the underlying network.
type GroupURLTester struct {
	link           string
	host           string
	destination    M.Socksaddr
	timeout        time.Duration
	attempts       int32
	pauseMillis    int32
	localTransport LocalDNSTransport
}

func NewGroupURLTester(link string, timeout int32, attempts int32, pause int32, localTransport LocalDNSTransport) (tester *GroupURLTester, err error) {
	defer device.DeferPanicToError("NewGroupURLTester", func(panicErr error) { err = panicErr })
	if localTransport == nil {
		return nil, errors.New("group URLTest local DNS transport is unavailable")
	}
	if link == "" {
		link = defaultURLTestLink
	}
	linkURL, err := url.Parse(link)
	if err != nil {
		return nil, fmt.Errorf("parse group URLTest URL: %w", err)
	}
	if linkURL.Scheme != "http" && linkURL.Scheme != "https" {
		return nil, fmt.Errorf("unsupported group URLTest scheme %q", linkURL.Scheme)
	}
	host := linkURL.Hostname()
	if host == "" {
		return nil, errors.New("group URLTest URL has no host")
	}
	port, err := urlTestPort(linkURL)
	if err != nil {
		return nil, err
	}
	probeTimeout := time.Duration(timeout) * time.Millisecond
	if probeTimeout <= 0 {
		return nil, errors.New("group URLTest timeout must be positive")
	}

	return &GroupURLTester{
		link:           link,
		host:           host,
		destination:    M.ParseSocksaddrHostPort(host, port),
		timeout:        probeTimeout,
		attempts:       attempts,
		pauseMillis:    pause,
		localTransport: localTransport,
	}, nil
}

func (t *GroupURLTester) Test(config, tag string) (latency int32, err error) {
	defer device.DeferPanicToError("GroupURLTester.Test", func(panicErr error) { err = panicErr })
	if t == nil || !t.destination.IsValid() {
		return -1, errors.New("group URLTester is not initialized")
	}
	return runGroupURLTestStartRetry(func() (int32, error) {
		return t.testProfile(config, tag)
	})
}

func (t *GroupURLTester) testProfile(config, tag string) (latency int32, err error) {
	instance, err := newSingBoxInstanceWithProtect(config, t.localTransport, true, true)
	if err != nil {
		return -1, fmt.Errorf("create group URLTest service: %w", err)
	}
	closeSynchronously := true
	defer func() {
		if closeSynchronously {
			err = errors.Join(err, instance.Close())
		}
	}()

	acquireProtect()
	defer releaseProtect()
	if err = instance.Start(); err != nil {
		return -1, fmt.Errorf("%w: %w", errGroupURLTestStart, err)
	}
	detour, err := instance.urlTestOutbound(tag)
	if err != nil {
		return -1, err
	}
	latency, err = t.testWithRetry(instance.ctx, detour)
	if errors.Is(err, context.DeadlineExceeded) {
		closeSynchronously = false
		instance.closeURLTestAsync()
	}
	return latency, err
}

func runGroupURLTestStartRetry(test func() (int32, error)) (int32, error) {
	for attempt := range 2 {
		latency, err := test()
		if err == nil || !errors.Is(err, errGroupURLTestStart) || attempt == 1 {
			return latency, err
		}
		time.Sleep(probeRetryDelay)
	}
	panic("unreachable")
}

func (b *BoxInstance) urlTestOutbound(tag string) (adapter.Outbound, error) {
	if tag == "" {
		detour := b.Outbound().Default()
		if detour == nil {
			return nil, errors.New("group URLTest default outbound is unavailable")
		}
		return detour, nil
	}
	detour, loaded := b.Outbound().Outbound(tag)
	if !loaded {
		return nil, fmt.Errorf("group URLTest outbound %q is not found", tag)
	}
	return detour, nil
}

func (t *GroupURLTester) testWithRetry(parent context.Context, detour N.Dialer) (int32, error) {
	return runURLTestAttempts(parent, t.attempts, t.pauseMillis, func() (int32, error) {
		return t.testOnce(parent, detour)
	})
}

func (t *GroupURLTester) testOnce(parent context.Context, detour N.Dialer) (int32, error) {
	ctx, cancel := context.WithTimeout(parent, t.timeout)
	defer cancel()
	connections := newURLTestConnectionSet()
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			conn, err := detour.DialContext(ctx, network, t.destination)
			if err != nil {
				return nil, err
			}
			return connections.track(conn), nil
		},
		TLSClientConfig: &tls.Config{
			ServerName: t.host,
			Time:       ntp.TimeFuncFromContext(ctx),
			RootCAs:    adapter.RootPoolFromContext(ctx),
		},
	}
	client := &http.Client{Transport: transport, Timeout: t.timeout}
	result, err := runURLTestAsync(ctx, "group HTTP URLTest", func() (int32, error) {
		return probe.URLTest(ctx, client, t.link)
	})
	connections.closeAllSafely()
	if err != nil {
		return -1, err
	}
	return result, nil
}

func urlTestPort(linkURL *url.URL) (uint16, error) {
	port := linkURL.Port()
	if port == "" {
		if linkURL.Scheme == "http" {
			return 80, nil
		}
		return 443, nil
	}
	value, err := strconv.ParseUint(port, 10, 16)
	if err != nil || value == 0 {
		return 0, fmt.Errorf("invalid group URLTest port %q", port)
	}
	return uint16(value), nil
}

func shouldRetryProbe(err error, elapsed, timeout time.Duration) bool {
	if err == nil || errors.Is(err, context.Canceled) || errors.Is(err, syscall.ECONNREFUSED) {
		return false
	}
	fastLimit := min(timeout/2, time.Second)
	if fastLimit <= 0 || elapsed >= fastLimit {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) ||
		errors.Is(err, syscall.ENETDOWN) ||
		errors.Is(err, syscall.ENETUNREACH) ||
		errors.Is(err, syscall.EHOSTUNREACH) ||
		errors.Is(err, syscall.ECONNRESET) {
		return true
	}
	if _, ok := errors.AsType[*net.DNSError](err); ok {
		return true
	}
	if networkErr, ok := errors.AsType[net.Error](err); ok {
		return networkErr.Timeout() || networkErr.Temporary()
	}
	return false
}

func TcpPing(host, port string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("TCPPing", func(panicErr error) { err = panicErr })
	if host == "" {
		return -1, errors.New("TCP ping host is empty")
	}
	portNumber, parseErr := strconv.ParseUint(port, 10, 16)
	if parseErr != nil || portNumber == 0 {
		return -1, fmt.Errorf("invalid TCP ping port %q", port)
	}
	probeTimeout := time.Duration(timeout) * time.Millisecond
	if probeTimeout <= 0 {
		return -1, errors.New("TCP ping timeout must be positive")
	}
	address := net.JoinHostPort(host, port)
	dialer := &net.Dialer{Control: protectSocketControl}

	acquireProtect()
	defer releaseProtect()
	for attempt := range 2 {
		ctx, cancel := context.WithTimeout(context.Background(), probeTimeout)
		started := time.Now()
		latency, err = probe.TCPPing(ctx, dialer.DialContext, address)
		cancel()
		if err == nil {
			return latency, nil
		}
		if attempt == 1 || !shouldRetryProbe(err, time.Since(started), probeTimeout) {
			return -1, err
		}
		time.Sleep(probeRetryDelay)
	}
	panic("unreachable")
}
