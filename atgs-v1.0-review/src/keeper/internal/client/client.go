// Package client is the Keeper's persistent control-channel client.
//
// Responsibilities:
//   - Dial Central over mTLS WebSocket using the Keeper's enrolled identity.
//   - Send KeeperHello, receive CentralHello, validate protocol version.
//   - Respond to Pings with Pongs.
//   - Periodically emit ResourcesReport (placeholder in Phase 1).
//   - Reconnect with exponential backoff on any disconnect.
//
// The client does NOT know about tasks. Task handling plugs in through the
// MessageHandler interface (wired up in Phase 2).
package client

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"runtime"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/keeper/internal/enroll"
	"github.com/xkstudios/atgs/keeper/internal/resources"
	"github.com/xkstudios/atgs/shared/envelope"
	"github.com/xkstudios/atgs/shared/protocol"
)

// MessageHandler processes inbound messages the client doesn't handle itself
// (ping/pong/hello are internal). Phase 1: nothing implements this yet.
type MessageHandler interface {
	Handle(ctx context.Context, env protocol.Envelope, out Sender) error
}

// Sender lets message handlers send frames back. Implementations must be
// safe for concurrent use.
type Sender interface {
	Send(ctx context.Context, env protocol.Envelope) error
}

type Config struct {
	Identity     *enroll.Identity
	AgentVersion string
	Log          *slog.Logger
	Handler      MessageHandler // optional, nil is fine for Phase 1

	// ResourcesInterval controls how often the Keeper sends a resources report.
	ResourcesInterval time.Duration

	// RequireSigned (Phase 7) gates strict signature verification on inbound
	// envelopes. When false, unsigned envelopes are accepted with a debug log;
	// when true, they are dropped.
	RequireSigned bool
}

type Client struct {
	cfg Config
}

func New(cfg Config) *Client {
	if cfg.ResourcesInterval == 0 {
		cfg.ResourcesInterval = 30 * time.Second
	}
	return &Client{cfg: cfg}
}

// Run maintains a persistent connection until ctx is cancelled.
// On disconnect it logs and reconnects with backoff.
func (c *Client) Run(ctx context.Context) error {
	backoff := newBackoff()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := c.runOnce(ctx)
		if errors.Is(err, context.Canceled) {
			return err
		}
		if err != nil {
			c.cfg.Log.Warn("control channel ended, will reconnect", "err", err)
		} else {
			c.cfg.Log.Info("control channel ended cleanly, reconnecting")
		}
		sleep := backoff.next()
		c.cfg.Log.Info("reconnect backoff", "wait", sleep)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(sleep):
		}
	}
}

// runOnce performs a single connect-handshake-loop cycle, returning when the
// connection is torn down for any reason.
func (c *Client) runOnce(ctx context.Context) error {
	wsURL, err := url.Parse(c.cfg.Identity.WSEndpoint)
	if err != nil {
		return fmt.Errorf("parse ws endpoint: %w", err)
	}

	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate{c.cfg.Identity.Certificate},
		RootCAs:      c.cfg.Identity.CACertPool,
		ServerName:   wsURL.Hostname(),
		MinVersion:   tls.VersionTLS12,
	}

	httpClient := &http.Client{
		Transport: &http.Transport{
			TLSClientConfig: tlsCfg,
		},
	}

	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(dialCtx, c.cfg.Identity.WSEndpoint, &websocket.DialOptions{
		HTTPClient: httpClient,
	})
	if err != nil {
		return fmt.Errorf("ws dial: %w", err)
	}
	defer ws.Close(websocket.StatusNormalClosure, "client shutdown")

	session := &session{
		ws:            ws,
		log:           c.cfg.Log.With("keeper_id", c.cfg.Identity.KeeperID),
		requireSigned: c.cfg.RequireSigned,
	}

	// Phase 7: wire signing if keys are present on the identity.
	if len(c.cfg.Identity.Ed25519Priv) > 0 {
		s, err := envelope.NewSigner(c.cfg.Identity.Ed25519Priv)
		if err != nil {
			return fmt.Errorf("build keeper signer: %w", err)
		}
		session.signer = s
	}
	if len(c.cfg.Identity.CentralEd25519Pub) > 0 {
		v, err := envelope.NewVerifier(c.cfg.Identity.CentralEd25519Pub, 0)
		if err != nil {
			return fmt.Errorf("build central verifier: %w", err)
		}
		session.verifier = v
	}

	// Send hello.
	hostname, _ := osHostname()
	if err := session.send(ctx, protocol.Envelope{
		Version: protocol.ProtocolVersion,
		ID:      uuid.NewString(),
		Kind:    protocol.KindKeeperHello,
		Data: protocol.KeeperHello{
			ProtocolVersion: protocol.ProtocolVersion,
			AgentVersion:    c.cfg.AgentVersion,
			Platform:        runtime.GOOS,
			Arch:            runtime.GOARCH,
			Hostname:        hostname,
		},
	}); err != nil {
		return fmt.Errorf("send hello: %w", err)
	}

	// Expect CentralHello.
	env, err := session.readEnvelope(ctx, 10*time.Second)
	if err != nil {
		return fmt.Errorf("read central hello: %w", err)
	}
	if env.Kind != protocol.KindCentralHello {
		return fmt.Errorf("expected central.hello, got %s", env.Kind)
	}
	var hello protocol.CentralHello
	if err := decodeData(env.Data, &hello); err != nil {
		return fmt.Errorf("decode central hello: %w", err)
	}
	if hello.ProtocolVersion != protocol.ProtocolVersion {
		return fmt.Errorf("protocol mismatch: server=%d, client=%d", hello.ProtocolVersion, protocol.ProtocolVersion)
	}
	session.log.Info("connected to central",
		"server_version", hello.ServerVersion,
		"session_id", hello.SessionID,
		"ping_interval_s", hello.PingIntervalSecs,
	)

	// Run the read loop and resources loop concurrently.
	runCtx, cancelRun := context.WithCancel(ctx)
	defer cancelRun()

	var wg sync.WaitGroup
	wg.Add(2)
	errCh := make(chan error, 2)

	go func() {
		defer wg.Done()
		errCh <- session.readLoop(runCtx, c.cfg.Handler)
	}()
	go func() {
		defer wg.Done()
		errCh <- session.resourcesLoop(runCtx, c.cfg.ResourcesInterval)
	}()

	// First error kills the session.
	firstErr := <-errCh
	cancelRun()
	// Drain the second, but don't wait forever.
	go func() {
		<-errCh
		wg.Wait()
	}()
	return firstErr
}

// session is the per-connection state.
type session struct {
	ws      *websocket.Conn
	log     *slog.Logger
	writeMu sync.Mutex

	// Phase 7: envelope signing. Both are optional; nil disables.
	signer        *envelope.Signer
	verifier      *envelope.Verifier
	requireSigned bool
}

func (s *session) send(ctx context.Context, env protocol.Envelope) error {
	// Phase 7: sign if we have a signer.
	if s.signer != nil {
		nonce := make([]byte, 16)
		if _, err := rand.Read(nonce); err != nil {
			return fmt.Errorf("nonce: %w", err)
		}
		if err := s.signer.Sign(&env, nonce); err != nil {
			return fmt.Errorf("sign envelope: %w", err)
		}
	}
	buf, err := json.Marshal(env)
	if err != nil {
		return err
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return s.ws.Write(wctx, websocket.MessageText, buf)
}

// Send satisfies Sender for MessageHandler implementations.
func (s *session) Send(ctx context.Context, env protocol.Envelope) error {
	return s.send(ctx, env)
}

func (s *session) readEnvelope(ctx context.Context, timeout time.Duration) (protocol.Envelope, error) {
	var env protocol.Envelope
	rctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	_, data, err := s.ws.Read(rctx)
	if err != nil {
		return env, err
	}
	if err := json.Unmarshal(data, &env); err != nil {
		return env, fmt.Errorf("decode envelope: %w", err)
	}
	return env, nil
}

func (s *session) readLoop(ctx context.Context, handler MessageHandler) error {
	for {
		_, data, err := s.ws.Read(ctx)
		if err != nil {
			return err
		}
		var env protocol.Envelope
		if err := json.Unmarshal(data, &env); err != nil {
			s.log.Warn("malformed envelope from central", "err", err)
			continue
		}
		// Phase 7: verify if we have a verifier.
		if s.verifier != nil {
			if err := s.verifier.Verify(&env); err != nil {
				if s.requireSigned {
					s.log.Warn("rejecting unsigned/invalid envelope from central",
						"kind", env.Kind, "err", err)
					continue
				}
				s.log.Debug("central envelope verification failed (lax mode)",
					"kind", env.Kind, "err", err)
			}
		} else if s.requireSigned {
			s.log.Warn("rejecting central envelope: no verifier configured", "kind", env.Kind)
			continue
		}
		switch env.Kind {
		case protocol.KindPing:
			var p protocol.Ping
			_ = decodeData(env.Data, &p)
			if err := s.send(ctx, protocol.Envelope{
				Version:       protocol.ProtocolVersion,
				ID:            uuid.NewString(),
				CorrelationID: env.ID,
				Kind:          protocol.KindPong,
				Data:          protocol.Pong{Seq: p.Seq},
			}); err != nil {
				return fmt.Errorf("send pong: %w", err)
			}
		case protocol.KindPong:
			// We don't initiate pings in Phase 1; ignore unsolicited pongs.
		case protocol.KindError:
			var e protocol.ErrorPayload
			_ = decodeData(env.Data, &e)
			s.log.Warn("central reported error", "code", e.Code, "message", e.Message)
		case protocol.KindTaskDispatch:
			if handler == nil {
				s.log.Warn("received task dispatch but no handler registered (phase 2+)")
				continue
			}
			if err := handler.Handle(ctx, env, s); err != nil {
				s.log.Warn("task handler error", "err", err)
			}
		default:
			s.log.Debug("unhandled envelope kind", "kind", env.Kind)
		}
	}
}

func (s *session) resourcesLoop(ctx context.Context, interval time.Duration) error {
	// Emit one immediately so Central has a baseline.
	if err := s.send(ctx, buildResourcesReport(ctx)); err != nil {
		return err
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
			if err := s.send(ctx, buildResourcesReport(ctx)); err != nil {
				return err
			}
		}
	}
}

// buildResourcesReport samples real machine state via the resources package.
func buildResourcesReport(ctx context.Context) protocol.Envelope {
	return protocol.Envelope{
		Version: protocol.ProtocolVersion,
		ID:      uuid.NewString(),
		Kind:    protocol.KindResourcesReport,
		Data:    resources.Sample(ctx),
	}
}

func decodeData(data any, out any) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}

// Exponential backoff, capped.
type backoff struct {
	base    time.Duration
	cap     time.Duration
	current time.Duration
}

func newBackoff() *backoff {
	return &backoff{base: 1 * time.Second, cap: 30 * time.Second, current: 1 * time.Second}
}

func (b *backoff) next() time.Duration {
	d := b.current
	b.current *= 2
	if b.current > b.cap {
		b.current = b.cap
	}
	return d
}

func osHostname() (string, error) {
	return os.Hostname()
}
