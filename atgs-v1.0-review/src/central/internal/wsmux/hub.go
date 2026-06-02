// Package wsmux manages live control-channel connections to Keepers.
//
// One Hub per Central process. Keepers are identified by their keeper_id
// (extracted from the client certificate's CN). A Keeper opening a new
// connection while an old one is still live supersedes the old one; the
// previous connection is closed with reason "superseded".
//
// This package does NOT speak task semantics. It's transport + liveness only.
// Task dispatch lives in a separate package to be added in Phase 2.
package wsmux

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/shared/envelope"
	"github.com/xkstudios/atgs/shared/protocol"
)

// Config tunes Hub liveness behavior.
type Config struct {
	PingInterval  time.Duration
	PingTimeout   time.Duration
	ServerVersion string
}

// Hub tracks live Keeper connections.
type Hub struct {
	cfg Config

	mu      sync.RWMutex
	byID    map[uuid.UUID]*Connection
	onEvent EventHandler
	store   *store.Store

	// taskHandler is optional. If set, task-related inbound frames are routed here.
	taskHandler TaskReplyHandler

	// Phase 7: envelope signing. Both are optional; nil disables signing/verification.
	signer         *envelope.Signer // Central's signer for outbound envelopes
	verifierLookup VerifierLookup   // returns a Verifier for a given keeper, or nil
	requireSigned  bool             // when true, unsigned inbound envelopes are dropped
}

// VerifierLookup returns a ready-to-use envelope verifier for the given
// keeper, or nil if that keeper has no key on file. Called once per accepted
// connection; the Verifier is held for the connection's lifetime (so its
// replay cache is per-session, not shared across reconnects — that's fine
// because the skew window rejects stale nonces anyway).
type VerifierLookup func(keeperID uuid.UUID) *envelope.Verifier

// EventHandler receives lifecycle callbacks so the rest of Central can
// observe connections without coupling to the Hub internals.
type EventHandler interface {
	OnKeeperConnected(ctx context.Context, sessionID uuid.UUID, keeperID uuid.UUID, remoteAddr string, hello protocol.KeeperHello)
	OnKeeperDisconnected(ctx context.Context, sessionID uuid.UUID, keeperID uuid.UUID, reason string)
}

// TaskReplyHandler receives task-related frames coming in from Keepers and
// is expected to route them to the dispatcher. Wired up in Phase 2.
type TaskReplyHandler interface {
	HandleReply(ctx context.Context, keeperID uuid.UUID, sessionID uuid.UUID, env protocol.Envelope)
}

func NewHub(cfg Config, st *store.Store, h EventHandler) *Hub {
	return &Hub{
		cfg:     cfg,
		byID:    make(map[uuid.UUID]*Connection),
		onEvent: h,
		store:   st,
	}
}

// SetSigningPolicy installs signer + verifier lookup. Called once at startup
// after the hub is created. Passing a nil signer or lookup disables that
// direction. requireSigned flips strict mode: when true, any inbound
// envelope without a valid signature is dropped.
func (h *Hub) SetSigningPolicy(signer *envelope.Signer, lookup VerifierLookup, requireSigned bool) {
	h.signer = signer
	h.verifierLookup = lookup
	h.requireSigned = requireSigned
}

// Connection represents one live Keeper control channel.
type Connection struct {
	SessionID  uuid.UUID
	KeeperID   uuid.UUID
	RemoteAddr string

	ws       *websocket.Conn
	hub      *Hub
	log      *slog.Logger
	lastPong atomic.Int64 // unix nanoseconds

	// writeMu serializes writes. coder/websocket is NOT safe for concurrent
	// Write calls, so every code path that writes grabs this.
	writeMu sync.Mutex

	closeOnce   sync.Once
	closeReason string

	// Phase 7: per-connection verifier (resolved from the hub at accept time).
	// nil if the keeper has no Ed25519 key on file — strict mode tests this.
	verifier *envelope.Verifier
}

// Serve is called from the HTTP handler after a successful WebSocket upgrade.
// It blocks until the connection is torn down.
//
// The handshake inside is strictly:
//  1. Read one message; must be keeper.hello
//  2. Validate ProtocolVersion
//  3. Write central.hello
//  4. Enter the read loop and start the ping loop
func (h *Hub) Serve(ctx context.Context, ws *websocket.Conn, keeperID uuid.UUID, remoteAddr string, log *slog.Logger) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	conn := &Connection{
		SessionID:  uuid.New(),
		KeeperID:   keeperID,
		RemoteAddr: remoteAddr,
		ws:         ws,
		hub:        h,
		log:        log.With("session_id", uuid.NewString(), "keeper_id", keeperID.String()),
	}
	conn.lastPong.Store(time.Now().UnixNano())

	// Phase 7: resolve this keeper's Ed25519 verifier. Nil is OK for
	// keepers that haven't re-enrolled yet; read loop handles that case.
	if h.verifierLookup != nil {
		conn.verifier = h.verifierLookup(keeperID)
	}

	// Handshake: read KeeperHello first.
	hello, err := conn.readHello(ctx)
	if err != nil {
		conn.closeWithReason("handshake_failed: " + err.Error())
		return fmt.Errorf("keeper hello: %w", err)
	}
	if hello.ProtocolVersion != protocol.ProtocolVersion {
		_ = conn.sendError(ctx, protocol.ErrProtocolMismatch,
			fmt.Sprintf("server speaks protocol v%d, keeper speaks v%d", protocol.ProtocolVersion, hello.ProtocolVersion))
		conn.closeWithReason("protocol_mismatch")
		return fmt.Errorf("protocol mismatch: keeper=%d server=%d", hello.ProtocolVersion, protocol.ProtocolVersion)
	}

	// Reply with CentralHello.
	if err := conn.send(ctx, protocol.Envelope{
		Version: protocol.ProtocolVersion,
		ID:      uuid.NewString(),
		Kind:    protocol.KindCentralHello,
		Data: protocol.CentralHello{
			ProtocolVersion:  protocol.ProtocolVersion,
			ServerVersion:    h.cfg.ServerVersion,
			PingIntervalSecs: int(h.cfg.PingInterval / time.Second),
			SessionID:        conn.SessionID.String(),
		},
	}); err != nil {
		conn.closeWithReason("central_hello_send_failed")
		return fmt.Errorf("send central hello: %w", err)
	}

	// Register. Supersede any prior connection.
	h.register(conn)
	defer h.unregister(conn)

	if h.onEvent != nil {
		h.onEvent.OnKeeperConnected(ctx, conn.SessionID, conn.KeeperID, conn.RemoteAddr, hello)
	}

	// Start ping loop + read loop.
	pingErrCh := make(chan error, 1)
	go func() { pingErrCh <- conn.pingLoop(ctx) }()

	readErr := conn.readLoop(ctx)
	cancel()

	select {
	case err := <-pingErrCh:
		if err != nil && !errors.Is(err, context.Canceled) {
			conn.log.Debug("ping loop exited", "err", err)
		}
	case <-time.After(2 * time.Second):
		// ping goroutine is stuck; orphan it. Not ideal but safe.
	}

	reason := conn.closeReason
	if reason == "" {
		if readErr != nil {
			reason = "read_error: " + readErr.Error()
		} else {
			reason = "closed"
		}
	}
	if h.onEvent != nil {
		h.onEvent.OnKeeperDisconnected(context.Background(), conn.SessionID, conn.KeeperID, reason)
	}
	return readErr
}

func (h *Hub) register(c *Connection) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if prev, ok := h.byID[c.KeeperID]; ok {
		prev.closeWithReason("superseded")
	}
	h.byID[c.KeeperID] = c
}

func (h *Hub) unregister(c *Connection) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cur, ok := h.byID[c.KeeperID]; ok && cur.SessionID == c.SessionID {
		delete(h.byID, c.KeeperID)
	}
}

// IsConnected reports whether Central currently has a live session for the
// given keeper.
func (h *Hub) IsConnected(keeperID uuid.UUID) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.byID[keeperID]
	return ok
}

// ConnectedKeepers returns a snapshot of currently live keeper IDs.
func (h *Hub) ConnectedKeepers() []uuid.UUID {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]uuid.UUID, 0, len(h.byID))
	for k := range h.byID {
		out = append(out, k)
	}
	return out
}

// SetTaskHandler installs the handler that receives task.ack/progress/result
// frames. Usually called once at startup. Safe to call before any keepers
// have connected.
func (h *Hub) SetTaskHandler(handler TaskReplyHandler) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.taskHandler = handler
}

// SendTo sends an envelope to a specific Keeper if it is currently connected.
// The first return value reports whether a live connection was found and
// the send was attempted; the second is the send error (if any).
//
// If the Keeper is offline, returns (false, nil) - this is not an error,
// it's the normal "queue for later" signal.
func (h *Hub) SendTo(ctx context.Context, keeperID uuid.UUID, env protocol.Envelope) (bool, error) {
	h.mu.RLock()
	conn, ok := h.byID[keeperID]
	h.mu.RUnlock()
	if !ok {
		return false, nil
	}
	return true, conn.send(ctx, env)
}

// --- Connection internals ---

func (c *Connection) readHello(ctx context.Context) (protocol.KeeperHello, error) {
	var hello protocol.KeeperHello
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	_, data, err := c.ws.Read(ctx)
	if err != nil {
		return hello, err
	}
	var env protocol.Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return hello, fmt.Errorf("decode envelope: %w", err)
	}
	if env.Kind != protocol.KindKeeperHello {
		return hello, fmt.Errorf("expected %s, got %s", protocol.KindKeeperHello, env.Kind)
	}
	raw, err := json.Marshal(env.Data)
	if err != nil {
		return hello, err
	}
	if err := json.Unmarshal(raw, &hello); err != nil {
		return hello, fmt.Errorf("decode keeper hello data: %w", err)
	}
	return hello, nil
}

func (c *Connection) readLoop(ctx context.Context) error {
	for {
		_, data, err := c.ws.Read(ctx)
		if err != nil {
			return err
		}
		var env protocol.Envelope
		if err := json.Unmarshal(data, &env); err != nil {
			c.log.Warn("malformed envelope from keeper", "err", err)
			_ = c.sendError(ctx, protocol.ErrMalformedEnvelope, err.Error())
			continue
		}
		// Phase 7: verify signature if we have a verifier. Policy: if
		// requireSigned is set and the envelope fails verification, drop
		// it with an error message. If requireSigned is off, log and accept.
		if c.verifier != nil {
			if err := c.verifier.Verify(&env); err != nil {
				if c.hub.requireSigned {
					c.log.Warn("rejecting unsigned/invalid envelope", "kind", env.Kind, "err", err)
					_ = c.sendError(ctx, "bad_signature", err.Error())
					continue
				}
				// Lax mode: log but proceed.
				c.log.Debug("envelope verification failed in lax mode", "kind", env.Kind, "err", err)
			}
		} else if c.hub.requireSigned {
			c.log.Warn("rejecting envelope from keeper without Ed25519 key", "kind", env.Kind)
			_ = c.sendError(ctx, "bad_signature", "keeper has no ed25519 key on file")
			continue
		}
		if err := c.handleMessage(ctx, env); err != nil {
			c.log.Warn("handle message failed", "kind", env.Kind, "err", err)
		}
	}
}

func (c *Connection) handleMessage(ctx context.Context, env protocol.Envelope) error {
	switch env.Kind {
	case protocol.KindPong:
		c.lastPong.Store(time.Now().UnixNano())
		return nil
	case protocol.KindPing:
		// A Keeper-initiated ping. Respond with pong echoing seq.
		var p protocol.Ping
		if err := decodeData(env.Data, &p); err != nil {
			return err
		}
		return c.send(ctx, protocol.Envelope{
			Version:       protocol.ProtocolVersion,
			ID:            uuid.NewString(),
			CorrelationID: env.ID,
			Kind:          protocol.KindPong,
			Data:          protocol.Pong{Seq: p.Seq},
		})
	case protocol.KindResourcesReport:
		var r protocol.ResourcesReport
		if err := decodeData(env.Data, &r); err != nil {
			return err
		}
		if c.hub.store != nil {
			reportedAt := time.Unix(r.ReportedAtUnix, 0).UTC()
			if r.ReportedAtUnix == 0 {
				reportedAt = time.Now().UTC()
			}
			if err := c.hub.store.UpsertKeeperResourcesSnapshot(ctx, store.UpsertKeeperResourcesSnapshotParams{
				KeeperID:       c.KeeperID,
				ReportedAt:     reportedAt,
				CPUCores:       r.CPUCores,
				CPUPercentUsed: r.CPUPercentUsed,
				MemTotalBytes:  r.MemTotalBytes,
				MemUsedBytes:   r.MemUsedBytes,
				DiskTotalBytes: r.DiskTotalBytes,
				DiskUsedBytes:  r.DiskUsedBytes,
			}); err != nil {
				c.log.Warn("persist resources report failed", "err", err)
			}
		}
		c.log.Debug("resources report", "cpu%", r.CPUPercentUsed, "mem_used", r.MemUsedBytes)
		return nil
	case protocol.KindError:
		var e protocol.ErrorPayload
		_ = decodeData(env.Data, &e)
		c.log.Warn("keeper reported error", "code", e.Code, "message", e.Message)
		return nil
	case protocol.KindTaskAck, protocol.KindTaskProgress, protocol.KindTaskResult:
		if c.hub.taskHandler != nil {
			c.hub.taskHandler.HandleReply(ctx, c.KeeperID, c.SessionID, env)
		}
		return nil
	default:
		return c.sendError(ctx, protocol.ErrUnknownKind, string(env.Kind))
	}
}

func (c *Connection) pingLoop(ctx context.Context) error {
	ticker := time.NewTicker(c.hub.cfg.PingInterval)
	defer ticker.Stop()
	var seq int64
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			// Liveness check first: is the last pong within timeout?
			last := time.Unix(0, c.lastPong.Load())
			if time.Since(last) > c.hub.cfg.PingTimeout {
				c.closeWithReason("ping_timeout")
				return fmt.Errorf("ping timeout (last pong %s ago)", time.Since(last))
			}
			seq++
			if err := c.send(ctx, protocol.Envelope{
				Version: protocol.ProtocolVersion,
				ID:      uuid.NewString(),
				Kind:    protocol.KindPing,
				Data:    protocol.Ping{Seq: seq},
			}); err != nil {
				return err
			}
		}
	}
}

func (c *Connection) send(ctx context.Context, env protocol.Envelope) error {
	// Phase 7: sign if we have a signer.
	if c.hub.signer != nil {
		nonce := make([]byte, 16)
		if _, err := rand.Read(nonce); err != nil {
			return fmt.Errorf("nonce: %w", err)
		}
		if err := c.hub.signer.Sign(&env, nonce); err != nil {
			return fmt.Errorf("sign envelope: %w", err)
		}
	}
	buf, err := json.Marshal(env)
	if err != nil {
		return err
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return c.ws.Write(writeCtx, websocket.MessageText, buf)
}

func (c *Connection) sendError(ctx context.Context, code, msg string) error {
	return c.send(ctx, protocol.Envelope{
		Version: protocol.ProtocolVersion,
		ID:      uuid.NewString(),
		Kind:    protocol.KindError,
		Data:    protocol.ErrorPayload{Code: code, Message: msg},
	})
}

func (c *Connection) closeWithReason(reason string) {
	c.closeOnce.Do(func() {
		c.closeReason = reason
		// NormalClosure so clients see a clean shutdown; reason goes in the
		// close frame and is surfaced in client logs.
		_ = c.ws.Close(websocket.StatusNormalClosure, reason)
	})
}

// decodeData round-trips env.Data (which arrives as map[string]any from JSON)
// through json.Marshal into a typed struct. Simpler than manual asserts.
func decodeData(data any, out any) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}
