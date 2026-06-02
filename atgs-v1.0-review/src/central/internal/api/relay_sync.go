package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/routing"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/shared/syncproto"
)

// RelaySyncHandler is a standalone HTTP handler used for /api/v1/relay-sync.
// Mounted on the keeper listener (the one with mTLS already configured) and
// gated on the client cert's OU being "ATGS Relay" to distinguish a relay
// connection from a keeper connection.
type RelaySyncHandler struct {
	Store     *store.Store
	Publisher *routing.Publisher
	Log       *slog.Logger
	Version   string

	// SnapshotThreshold is how many deltas behind a relay can be before we
	// send a full snapshot instead of replaying deltas. Tunable; 1000 is
	// a reasonable default for Phase 3.
	SnapshotThreshold int64
}

const (
	relaySyncPingInterval = 15 * time.Second
	relaySyncPingTimeout  = 45 * time.Second
)

func (h *RelaySyncHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// Require a client cert with OU=ATGS Relay.
	if r.TLS == nil || len(r.TLS.VerifiedChains) == 0 || len(r.TLS.PeerCertificates) == 0 {
		http.Error(w, "client certificate required", http.StatusUnauthorized)
		return
	}
	leaf := r.TLS.PeerCertificates[0]
	if !hasOU(leaf.Subject.OrganizationalUnit, "ATGS Relay") {
		http.Error(w, "this endpoint requires a relay certificate (OU=ATGS Relay)", http.StatusForbidden)
		return
	}
	relayID, err := uuid.Parse(leaf.Subject.CommonName)
	if err != nil {
		http.Error(w, "invalid relay id in cert CN", http.StatusUnauthorized)
		return
	}

	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{})
	if err != nil {
		h.Log.Warn("relay-sync ws accept", "err", err)
		return
	}
	defer ws.Close(websocket.StatusNormalClosure, "session ending")

	ctx := r.Context()
	if err := h.serveSession(ctx, ws, relayID); err != nil && !errors.Is(err, context.Canceled) {
		h.Log.Info("relay-sync session ended", "relay_id", relayID, "err", err)
	}
}

func (h *RelaySyncHandler) serveSession(ctx context.Context, ws *websocket.Conn, relayID uuid.UUID) error {
	log := h.Log.With("relay_id", relayID.String())

	// 1. Read RelayHello.
	helloCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	helloEnv, err := readEnvelope(helloCtx, ws)
	cancel()
	if err != nil {
		return err
	}
	if helloEnv.Kind != syncproto.KindRelayHello {
		return errors.New("expected relay.hello")
	}
	var hello syncproto.RelayHello
	if err := decodeData(helloEnv.Data, &hello); err != nil {
		return err
	}
	if hello.ProtocolVersion != syncproto.ProtocolVersion {
		return errors.New("protocol version mismatch")
	}
	log.Info("relay connected to sync channel",
		"known_version", hello.KnownVersion,
		"relay_version", hello.RelayVersion)

	// 2. Decide snapshot vs delta, send CentralHello + data.
	currentVersion, err := h.currentVersion(ctx)
	if err != nil {
		return err
	}

	useSnapshot := hello.KnownVersion == 0 ||
		(currentVersion-hello.KnownVersion > h.SnapshotThreshold)

	mode := "delta"
	if useSnapshot {
		mode = "snapshot"
	}

	// Subscribe BEFORE sending snapshot/delta so we don't miss anything
	// that happens between snapshot and live stream.
	subID, subCh := h.Publisher.Subscribe()
	defer h.Publisher.Unsubscribe(subID)

	// Writer serialization. coder/websocket is not concurrent-safe.
	var writeMu sync.Mutex
	send := func(env syncproto.Envelope) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		buf, err := json.Marshal(env)
		if err != nil {
			return err
		}
		wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		return ws.Write(wctx, websocket.MessageText, buf)
	}

	if err := send(syncproto.Envelope{
		Version: syncproto.ProtocolVersion,
		Kind:    syncproto.KindCentralHello,
		Data: syncproto.CentralHello{
			ProtocolVersion: syncproto.ProtocolVersion,
			Mode:            mode,
			ServerVersion:   h.Version,
		},
	}); err != nil {
		return err
	}

	// 3. Deliver snapshot or delta replay.
	if useSnapshot {
		entries, snapVersion, err := h.Store.RoutingSnapshot(ctx)
		if err != nil {
			return err
		}
		protoEntries := make([]syncproto.RoutingEntry, 0, len(entries))
		for _, e := range entries {
			protoEntries = append(protoEntries, syncproto.RoutingEntry{
				RouteKind:  e.RouteKind,
				Protocol:   e.Protocol,
				Hostname:   e.Hostname,
				PublicPort: e.PublicPort,
				InstanceID: e.InstanceID.String(),
				KeeperID:   e.KeeperID.String(),
				HostPort:   e.HostPort,
				Version:    e.Version,
			})
		}
		if err := send(syncproto.Envelope{
			Version: syncproto.ProtocolVersion,
			Kind:    syncproto.KindRoutingSnapshot,
			Data: syncproto.RoutingSnapshot{
				CurrentVersion: snapVersion,
				Entries:        protoEntries,
			},
		}); err != nil {
			return err
		}
		log.Info("sent routing snapshot", "entries", len(protoEntries), "current_version", snapVersion)
	} else {
		events, err := h.Store.RoutingEventsSince(ctx, hello.KnownVersion)
		if err != nil {
			return err
		}
		for _, e := range events {
			if err := send(syncproto.Envelope{
				Version: syncproto.ProtocolVersion,
				Kind:    syncproto.KindRoutingDelta,
				Data:    eventToDelta(e),
			}); err != nil {
				return err
			}
		}
		log.Info("replayed routing deltas", "count", len(events), "from_version", hello.KnownVersion)
	}

	// 4. Start reader for pings. Run concurrently with live delta fan-out.
	readErrCh := make(chan error, 1)
	go func() { readErrCh <- h.readLoop(ctx, ws, send) }()

	// 5. Live delta fan-out from the publisher.
	pingTicker := time.NewTicker(relaySyncPingInterval)
	defer pingTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case err := <-readErrCh:
			return err
		case <-pingTicker.C:
			if err := send(syncproto.Envelope{
				Version: syncproto.ProtocolVersion,
				Kind:    syncproto.KindPing,
			}); err != nil {
				return err
			}
		case ev, ok := <-subCh:
			if !ok {
				// Publisher dropped us (channel full → too slow).
				return errors.New("dropped by publisher: too slow")
			}
			if err := send(syncproto.Envelope{
				Version: syncproto.ProtocolVersion,
				Kind:    syncproto.KindRoutingDelta,
				Data:    eventToDelta(ev),
			}); err != nil {
				return err
			}
		}
	}
}

func (h *RelaySyncHandler) readLoop(ctx context.Context, ws *websocket.Conn, send func(syncproto.Envelope) error) error {
	for {
		env, err := readEnvelope(ctx, ws)
		if err != nil {
			return err
		}
		switch env.Kind {
		case syncproto.KindPing:
			if err := send(syncproto.Envelope{Version: syncproto.ProtocolVersion, Kind: syncproto.KindPong}); err != nil {
				return err
			}
		case syncproto.KindPong:
			// Phase 3 doesn't track RTT yet; ignore.
		default:
			// Unexpected on this direction. Log and continue.
			h.Log.Debug("unexpected inbound kind on relay-sync", "kind", env.Kind)
		}
	}
}

func (h *RelaySyncHandler) currentVersion(ctx context.Context) (int64, error) {
	snap, version, err := h.Store.RoutingSnapshot(ctx)
	_ = snap
	return version, err
}

func eventToDelta(e store.RoutingEvent) syncproto.RoutingDelta {
	d := syncproto.RoutingDelta{
		Version:   e.Version,
		At:        e.At,
		EventType: e.EventType,
		RouteKind: e.RouteKind,
		Protocol:  e.Protocol,
		Hostname:  e.Hostname,
		PublicPort: e.PublicPort,
	}
	if e.EventType == "upsert" {
		d.InstanceID = e.InstanceID.String()
		d.KeeperID = e.KeeperID.String()
		d.HostPort = e.HostPort
	}
	return d
}

func readEnvelope(ctx context.Context, ws *websocket.Conn) (syncproto.Envelope, error) {
	var env syncproto.Envelope
	_, data, err := ws.Read(ctx)
	if err != nil {
		return env, err
	}
	if err := json.Unmarshal(data, &env); err != nil {
		return env, err
	}
	return env, nil
}

func hasOU(ous []string, want string) bool {
	for _, ou := range ous {
		if ou == want {
			return true
		}
	}
	return false
}

// decodeData round-trips env.Data (which arrives as map[string]any from JSON)
// through json.Marshal into a typed struct.
func decodeData(data any, out any) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}
