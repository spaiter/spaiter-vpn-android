package libcore

import (
	"context"
	"errors"
	"fmt"
	"libcore/device"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing/service"
	"libcore/corelib/probe"
)

func NewInstanceURLTest(config, tag, link string, timeout int32, standard int32, attempts int32, pause int32, localTransport LocalDNSTransport) (latency int32, err error) {
	defer device.DeferPanicToError("NewInstanceURLTest", func(err_ error) { err = err_ })

	instance, err := newSingBoxInstance(config, localTransport, true)
	if err != nil {
		return -1, fmt.Errorf("create service: %w", err)
	}
	closeSynchronously := true
	defer func() {
		if closeSynchronously {
			err = errors.Join(err, instance.Close())
		}
	}()

	acquireProtect()
	defer releaseProtect()

	err = instance.Start()
	if err != nil {
		return -1, fmt.Errorf("start service: %w", err)
	}
	if tag != "" {
		latency, err = instance.urlTest(tag, link, timeout, attempts, pause)
	} else {
		latency, err = urlTest(instance, link, timeout, standard, attempts, pause, false)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		closeSynchronously = false
		instance.closeURLTestAsync()
	}
	return latency, err
}

func (b *BoxInstance) urlTest(tag, link string, timeout int32, attempts int32, pause int32) (latency int32, err error) {
	var detour adapter.Outbound
	if tag == "" {
		detour = b.Outbound().Default()
	} else {
		var loaded bool
		detour, loaded = b.Outbound().Outbound(tag)
		if !loaded {
			return -1, fmt.Errorf("%s is not found", tag)
		}
	}

	return runURLTestAttempts(b.ctx, attempts, pause, func() (int32, error) {
		ctx, cancel := context.WithTimeout(b.ctx, time.Duration(timeout)*time.Millisecond)
		defer cancel()
		result, err := runURLTestAsync(ctx, "sing-box URLTest", func() (uint16, error) {
			return urltest.URLTest(ctx, link, detour)
		})
		return int32(result), err
	})
}

func UrlTest(i *BoxInstance, link string, timeout int32, standard int32, attempts int32, pause int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	return urlTest(i, link, timeout, standard, attempts, pause, true)
}

func urlTest(i *BoxInstance, link string, timeout int32, standard int32, attempts int32, pause int32, enforceInstanceMinTimeout bool) (latency int32, err error) {
	instance := i
	if i != nil {
		if enforceInstanceMinTimeout && i != mainInstance {
			if timeout < 10000 {
				timeout = 10000
			}
		}
	} else {
		instance = mainInstance
	}

	parentCtx := context.Background()
	if instance != nil {
		parentCtx = instance.ctx
	}
	return runURLTestAttempts(parentCtx, attempts, pause, func() (int32, error) {
		ctx, cancel := context.WithTimeout(parentCtx, time.Duration(timeout)*time.Millisecond)
		defer cancel()
		connections := newURLTestConnectionSet()
		result, err := runURLTestAsync(ctx, "HTTP URLTest", func() (int32, error) {
			return runHTTPURLTest(ctx, instance, connections, link, time.Duration(timeout)*time.Millisecond, urlTestStandard(standard))
		})
		if errors.Is(err, context.DeadlineExceeded) {
			go connections.closeAllSafely()
		} else {
			connections.closeAllSafely()
		}
		return result, err
	})
}

func runURLTestAttempts(ctx context.Context, attempts int32, pauseMillis int32, test func() (int32, error)) (int32, error) {
	attempts = min(max(attempts, 1), 5)
	pause := time.Duration(max(pauseMillis, 0)) * time.Millisecond
	var lastErr error
	for attempt := range attempts {
		latency, err := test()
		if err == nil {
			return latency, nil
		}
		lastErr = err
		if attempt == attempts-1 {
			break
		}
		select {
		case <-ctx.Done():
			return -1, context.Cause(ctx)
		case <-time.After(pause):
		}
	}
	return -1, lastErr
}

func runHTTPURLTest(
	ctx context.Context,
	instance *BoxInstance,
	connections *urlTestConnectionSet,
	link string,
	timeout time.Duration,
	standard int,
) (int32, error) {
	var tracker adapter.ConnectionTracker
	if instance != nil && instance.v2api != nil {
		tracker = instance.v2api.StatsService()
	}
	client, err := newURLTestHTTPClient(ctx, instance, tracker, connections, link, timeout)
	if err != nil {
		return -1, err
	}
	return probe.URLTestMode(ctx, client, link, probe.Mode(standard))
}

func newURLTestHTTPClient(
	ctx context.Context,
	instance *BoxInstance,
	tracker adapter.ConnectionTracker,
	connections *urlTestConnectionSet,
	link string,
	timeout time.Duration,
) (*http.Client, error) {
	if instance == nil {
		_, err := lookupURLTestHost(ctx, link, func(context.Context, string) ([]netip.Addr, error) {
			return nil, errors.New("URLTest DNS router is unavailable")
		})
		if err != nil {
			return nil, fmt.Errorf("resolve URLTest host: %w", err)
		}
		remaining, err := remainingURLTestTimeout(ctx, timeout)
		if err != nil {
			return nil, err
		}
		client := boxapi.CreateProxyHttpClient(nil, nil, remaining)
		trackURLTestConnections(client.Transport.(*http.Transport), connections, remaining)
		return client, nil
	}

	dnsRouter := service.FromContext[adapter.DNSRouter](instance.ctx)
	if dnsRouter == nil {
		return nil, errors.New("URLTest DNS router is unavailable")
	}
	addresses, err := lookupURLTestHost(ctx, link, func(ctx context.Context, host string) ([]netip.Addr, error) {
		return dnsRouter.Lookup(ctx, host, adapter.DNSQueryOptions{})
	})
	if err != nil {
		return nil, fmt.Errorf("resolve URLTest host: %w", err)
	}
	remaining, err := remainingURLTestTimeout(ctx, timeout)
	if err != nil {
		return nil, err
	}
	client := boxapi.CreateProxyHttpClient(instance.Box, tracker, remaining)
	transport := client.Transport.(*http.Transport)

	if len(addresses) > 0 {
		dialContext := transport.DialContext
		transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
			return dialResolvedURLTestAddresses(ctx, network, address, addresses, dialContext)
		}
	}
	trackURLTestConnections(transport, connections, remaining)
	return client, nil
}

func dialResolvedURLTestAddresses(
	ctx context.Context,
	network string,
	address string,
	addresses []netip.Addr,
	dialContext func(context.Context, string, string) (net.Conn, error),
) (net.Conn, error) {
	_, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	var dialErrors []error
	for _, resolvedAddress := range addresses {
		conn, dialErr := dialContext(ctx, network, net.JoinHostPort(resolvedAddress.String(), port))
		if dialErr == nil {
			return conn, nil
		}
		dialErrors = append(dialErrors, dialErr)
	}
	return nil, errors.Join(dialErrors...)
}

type asyncURLTestResult[T any] struct {
	value T
	err   error
}

func runURLTestAsync[T any](ctx context.Context, name string, run func() (T, error)) (T, error) {
	var zero T
	resultChan := make(chan asyncURLTestResult[T], 1)
	go func() {
		defer device.DeferPanicToError(name, func(panicErr error) {
			resultChan <- asyncURLTestResult[T]{err: panicErr}
		})
		value, runErr := run()
		resultChan <- asyncURLTestResult[T]{value: value, err: runErr}
	}()

	select {
	case <-ctx.Done():
		return zero, context.Cause(ctx)
	case result := <-resultChan:
		if ctx.Err() != nil {
			return zero, context.Cause(ctx)
		}
		return result.value, result.err
	}
}

func remainingURLTestTimeout(ctx context.Context, fallback time.Duration) (time.Duration, error) {
	deadline, hasDeadline := ctx.Deadline()
	if !hasDeadline {
		return fallback, nil
	}
	remaining := time.Until(deadline)
	if remaining <= 0 {
		return 0, context.Cause(ctx)
	}
	return min(remaining, fallback), nil
}

func lookupURLTestHost(ctx context.Context, link string, lookup func(context.Context, string) ([]netip.Addr, error)) ([]netip.Addr, error) {
	linkURL, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	host := linkURL.Hostname()
	if host == "" {
		return nil, errors.New("URLTest URL has no host")
	}
	if _, err = netip.ParseAddr(host); err == nil {
		return nil, nil
	}
	addresses, err := runURLTestAsync(ctx, "URLTest DNS lookup", func() ([]netip.Addr, error) {
		return lookup(ctx, host)
	})
	if err != nil {
		return nil, err
	}
	if len(addresses) == 0 {
		return nil, errors.New("remote DNS returned no addresses")
	}
	return addresses, nil
}

type urlTestConnectionSet struct {
	access      sync.Mutex
	connections map[*urlTestTrackedConn]struct{}
	closed      bool
}

func newURLTestConnectionSet() *urlTestConnectionSet {
	return &urlTestConnectionSet{connections: make(map[*urlTestTrackedConn]struct{})}
}

func (s *urlTestConnectionSet) track(conn net.Conn) net.Conn {
	tracked := &urlTestTrackedConn{Conn: conn, owner: s}
	s.access.Lock()
	if s.closed {
		s.access.Unlock()
		_ = tracked.closeWithoutRemove()
		return tracked
	}
	s.connections[tracked] = struct{}{}
	s.access.Unlock()
	return tracked
}

func (s *urlTestConnectionSet) remove(conn *urlTestTrackedConn) {
	s.access.Lock()
	delete(s.connections, conn)
	s.access.Unlock()
}

func (s *urlTestConnectionSet) closeAll() {
	s.access.Lock()
	s.closed = true
	connections := make([]*urlTestTrackedConn, 0, len(s.connections))
	for conn := range s.connections {
		connections = append(connections, conn)
	}
	clear(s.connections)
	s.access.Unlock()
	for _, conn := range connections {
		_ = conn.closeWithoutRemove()
	}
}

func (s *urlTestConnectionSet) closeAllSafely() {
	defer device.DeferPanicToError("close URLTest connections", nil)
	s.closeAll()
}

type urlTestTrackedConn struct {
	net.Conn
	owner *urlTestConnectionSet
	once  sync.Once
}

func (c *urlTestTrackedConn) Close() error {
	c.owner.remove(c)
	return c.closeWithoutRemove()
}

func (c *urlTestTrackedConn) closeWithoutRemove() (err error) {
	c.once.Do(func() {
		err = c.Conn.Close()
	})
	return err
}

func trackURLTestConnections(transport *http.Transport, connections *urlTestConnectionSet, timeout time.Duration) {
	dialContext := transport.DialContext
	if dialContext == nil {
		dialContext = (&net.Dialer{Timeout: timeout}).DialContext
	}
	transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		conn, err := dialContext(ctx, network, address)
		if err != nil {
			return nil, err
		}
		return connections.track(conn), nil
	}
}

func (b *BoxInstance) closeURLTestAsync() {
	b.access.Lock()
	cancel := b.cancel
	instanceCtx := b.ctx
	b.access.Unlock()
	if cancel != nil {
		cancel()
	}
	go func() {
		defer device.DeferPanicToError("close timed out URLTest instance", nil)
		if instanceCtx != nil {
			if connectionManager := service.FromContext[adapter.ConnectionManager](instanceCtx); connectionManager != nil {
				go func() {
					defer device.DeferPanicToError("close timed out URLTest connections", nil)
					connectionManager.CloseAll()
				}()
			}
		}
		_ = b.Close()
	}()
}

func urlTestStandard(standard int32) int {
	switch standard {
	case int32(probe.ModeHandshake):
		return int(probe.ModeHandshake)
	case int32(probe.ModeFirstHandshake):
		return int(probe.ModeFirstHandshake)
	default:
		return int(probe.ModeRTT)
	}
}

// URLTestTag tests one outbound of an already running instance by tag, without
// starting a core per node. The group URL test builds every node into one config
// and calls this concurrently for each tag.
func (b *BoxInstance) URLTestTag(tag, link string, timeout int32, attempts int32, pause int32) (latency int32, err error) {
	defer device.DeferPanicToError("BoxInstance.URLTestTag", func(err_ error) { err = err_ })
	if b == nil || b.Box == nil {
		return -1, errors.New("box is not initialized")
	}
	if tag == "" {
		return -1, errors.New("outbound tag is required")
	}
	return b.urlTest(tag, link, timeout, attempts, pause)
}
