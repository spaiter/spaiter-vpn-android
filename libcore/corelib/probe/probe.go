// Package probe measures how a proxy performs: an HTTP URL test through a
// client that already dials via the proxy, and a bare TCP connect time.
package probe

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptrace"
	"time"
)

// Mode selects what a URL test measures.
type Mode int

const (
	// ModeRTT reports the time from the request headers leaving to the first
	// response byte, on a warm connection: the second of two requests.
	ModeRTT Mode = 0
	// ModeHandshake reports a full request on a fresh connection, keep-alive
	// off, measured on the second attempt.
	ModeHandshake Mode = 1
	// ModeFirstHandshake reports the very first full request, connection
	// setup included.
	ModeFirstHandshake Mode = 2
)

// URLTest is the quick form: one HEAD request, RTT measured by the trace.
func URLTest(ctx context.Context, client *http.Client, link string) (int32, error) {
	c, err := probeClient(client, false)
	if err != nil {
		return 0, err
	}
	defer c.CloseIdleConnections()
	started := time.Now()
	trace, err := head(ctx, c, link)
	if err != nil {
		return 0, err
	}
	return millis(trace.rtt(time.Since(started))), nil
}

// URLTestMode runs the test the way mode asks for.
func URLTestMode(ctx context.Context, client *http.Client, link string, mode Mode) (int32, error) {
	var attempts int
	switch mode {
	case ModeRTT, ModeHandshake:
		attempts = 2
	case ModeFirstHandshake:
		attempts = 1
	default:
		return 0, errors.New("probe: unknown URL test mode")
	}
	c, err := probeClient(client, mode == ModeHandshake)
	if err != nil {
		return 0, err
	}
	defer c.CloseIdleConnections()
	var result time.Duration
	for i := 0; i < attempts; i++ {
		started := time.Now()
		trace, err := head(ctx, c, link)
		if err != nil {
			return 0, err
		}
		result = time.Since(started)
		if mode == ModeRTT {
			result = trace.rtt(result)
		}
	}
	return millis(result), nil
}

// probeClient copies the caller's client so redirects are not followed and, for
// handshake modes, every request opens its own connection.
func probeClient(client *http.Client, freshConnections bool) (*http.Client, error) {
	if client == nil {
		return nil, errors.New("probe: no HTTP client")
	}
	c := *client
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if transport, ok := client.Transport.(*http.Transport); ok {
		t := transport.Clone()
		t.DisableKeepAlives = freshConnections
		c.Transport = t
	} else if freshConnections {
		return nil, errors.New("probe: handshake modes need an *http.Transport")
	}
	return &c, nil
}

type timing struct {
	headersWritten time.Time
	firstByte      time.Time
}

// rtt is first byte minus headers written; fallback when the trace is partial.
func (t timing) rtt(fallback time.Duration) time.Duration {
	if t.headersWritten.IsZero() || t.firstByte.IsZero() || t.firstByte.Before(t.headersWritten) {
		return fallback
	}
	return t.firstByte.Sub(t.headersWritten)
}

func head(ctx context.Context, client *http.Client, link string) (timing, error) {
	var t timing
	request, err := http.NewRequestWithContext(ctx, http.MethodHead, link, nil)
	if err != nil {
		return t, err
	}
	request = request.WithContext(httptrace.WithClientTrace(request.Context(), &httptrace.ClientTrace{
		WroteHeaders:         func() { t.headersWritten = time.Now() },
		GotFirstResponseByte: func() { t.firstByte = time.Now() },
	}))
	response, err := client.Do(request)
	if err != nil {
		return t, err
	}
	if response.Body != nil {
		err = response.Body.Close()
	}
	return t, err
}

// TCPPing reports how long dial takes to connect to address, then closes.
func TCPPing(ctx context.Context, dial func(context.Context, string, string) (net.Conn, error), address string) (int32, error) {
	if dial == nil {
		return 0, errors.New("probe: no dialer")
	}
	started := time.Now()
	conn, err := dial(ctx, "tcp", address)
	if err != nil {
		return 0, err
	}
	elapsed := time.Since(started)
	if err := conn.Close(); err != nil {
		return 0, err
	}
	return millis(elapsed), nil
}

func millis(d time.Duration) int32 {
	ms := d.Milliseconds()
	if ms < 0 {
		ms = 0
	}
	if ms > int64(^uint32(0)>>1) {
		ms = int64(^uint32(0) >> 1)
	}
	return int32(ms)
}
