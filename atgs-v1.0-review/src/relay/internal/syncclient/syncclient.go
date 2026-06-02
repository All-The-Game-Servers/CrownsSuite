// Package syncclient maintains the relay's persistent connection to Central's
// /api/v1/relay-sync WebSocket. On connect it presents its known_version;
// Central replies with snapshot or deltas; after catchup Central streams live
// deltas as they happen. On disconnect the client reconnects with backoff.
package syncclient

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/relay/internal/routing"
	"github.com/xkstudios/atgs/shared/syncproto"
)

type Config struct {
	CentralSyncURL string // e.g. wss://central:8443/api/v1/relay-sync
	TLSConfig      *tls.Config
	RelayID        uuid.UUID
	RelayVersion   string
	Cache          *routing.Cache
	Log            *slog.Logger
}

type Client struct {
	cfg Config
}

func New(cfg Config) *Client { return &Client{cfg: cfg} }

// Run maintains the sync connection until ctx is cancelled. Reconnects on
// failure with exponential backoff.
func (c *Client) Run(ctx context.Context) error {
	backoff := 1 * time.Second
	const maxBackoff = 30 * time.Second
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := c.runOnce(ctx)
		if errors.Is(err, context.Canceled) {
			return err
		}
		if err != nil {
			c.cfg.Log.Warn("relay-sync disconnected, will reconnect", "err", err, "backoff", backoff)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}
		backoff *= 2
		if backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

func (c *Client) runOnce(ctx context.Context) error {
	u, err := url.Parse(c.cfg.CentralSyncURL)
	if err != nil {
		return fmt.Errorf("parse url: %w", err)
	}

	httpClient := &http.Client{
		Transport: &http.Transport{TLSClientConfig: c.cfg.TLSConfig},
	}

	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(dialCtx, c.cfg.CentralSyncURL, &websocket.DialOptions{
		HTTPClient: httpClient,
	})
	if err != nil {
		return fmt.Errorf("dial %s: %w", u.Host, err)
	}
	defer ws.Close(websocket.StatusNormalClosure, "client shutdown")

	// Serialize writes.
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

	// 1. Send RelayHello.
	if err := send(syncproto.Envelope{
		Version: syncproto.ProtocolVersion,
		Kind:    syncproto.KindRelayHello,
		Data: syncproto.RelayHello{
			ProtocolVersion: syncproto.ProtocolVersion,
			RelayID:         c.cfg.RelayID.String(),
			KnownVersion:    c.cfg.Cache.KnownVersion(),
			RelayVersion:    c.cfg.RelayVersion,
		},
	}); err != nil {
		return fmt.Errorf("send hello: %w", err)
	}

	// 2. Read CentralHello.
	helloEnv, err := readEnvelope(ctx, ws, 10*time.Second)
	if err != nil {
		return fmt.Errorf("read central hello: %w", err)
	}
	if helloEnv.Kind != syncproto.KindCentralHello {
		return fmt.Errorf("expected central.hello, got %s", helloEnv.Kind)
	}
	var centralHello syncproto.CentralHello
	if err := decodeData(helloEnv.Data, &centralHello); err != nil {
		return fmt.Errorf("decode central hello: %w", err)
	}
	c.cfg.Log.Info("relay-sync connected",
		"mode", centralHello.Mode,
		"server_version", centralHello.ServerVersion,
		"known_version", c.cfg.Cache.KnownVersion())

	// 3. If snapshot mode, the next message is the snapshot.
	if centralHello.Mode == "snapshot" {
		snapEnv, err := readEnvelope(ctx, ws, 30*time.Second)
		if err != nil {
			return fmt.Errorf("read snapshot: %w", err)
		}
		if snapEnv.Kind != syncproto.KindRoutingSnapshot {
			return fmt.Errorf("expected routing.snapshot, got %s", snapEnv.Kind)
		}
		var snap syncproto.RoutingSnapshot
		if err := decodeData(snapEnv.Data, &snap); err != nil {
			return err
		}
		entries := make([]routing.Entry, 0, len(snap.Entries))
		for _, e := range snap.Entries {
			entries = append(entries, routing.Entry{
				RouteKind:  e.RouteKind,
				Protocol:   e.Protocol,
				Hostname:   e.Hostname,
				PublicPort: e.PublicPort,
				InstanceID: e.InstanceID,
				KeeperID:   e.KeeperID,
				HostPort:   e.HostPort,
				Version:    e.Version,
			})
		}
		if err := c.cfg.Cache.Replace(ctx, entries, snap.CurrentVersion); err != nil {
			return fmt.Errorf("apply snapshot: %w", err)
		}
		c.cfg.Log.Info("applied routing snapshot", "entries", len(entries), "version", snap.CurrentVersion)
	}

	// 4. From here: stream of deltas + pings. Read loop.
	for {
		env, err := readEnvelope(ctx, ws, 60*time.Second)
		if err != nil {
			return err
		}
		switch env.Kind {
		case syncproto.KindRoutingDelta:
			var d syncproto.RoutingDelta
			if err := decodeData(env.Data, &d); err != nil {
				c.cfg.Log.Warn("decode delta", "err", err)
				continue
			}
			entry := routing.Entry{
				RouteKind:  d.RouteKind,
				Protocol:   d.Protocol,
				Hostname:   d.Hostname,
				PublicPort: d.PublicPort,
				InstanceID: d.InstanceID,
				KeeperID:   d.KeeperID,
				HostPort:   d.HostPort,
				Version:    d.Version,
			}
			if err := c.cfg.Cache.Apply(ctx, d.EventType, entry); err != nil {
				c.cfg.Log.Warn("apply delta", "err", err)
				continue
			}
			c.cfg.Log.Debug("routing delta applied",
				"type", d.EventType, "route_kind", d.RouteKind, "hostname", d.Hostname, "public_port", d.PublicPort, "version", d.Version)
		case syncproto.KindPing:
			if err := send(syncproto.Envelope{
				Version: syncproto.ProtocolVersion,
				Kind:    syncproto.KindPong,
			}); err != nil {
				return err
			}
		case syncproto.KindPong:
			// Phase 3 doesn't initiate client-side pings yet; ignore.
		default:
			c.cfg.Log.Debug("unhandled relay-sync kind", "kind", env.Kind)
		}
	}
}

func readEnvelope(ctx context.Context, ws *websocket.Conn, timeout time.Duration) (syncproto.Envelope, error) {
	var env syncproto.Envelope
	rctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	_, data, err := ws.Read(rctx)
	if err != nil {
		return env, err
	}
	if err := json.Unmarshal(data, &env); err != nil {
		return env, err
	}
	return env, nil
}

func decodeData(data any, out any) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}
