package ingress

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/relay/internal/registry"
	"github.com/xkstudios/atgs/relay/internal/routing"
	"github.com/xkstudios/atgs/shared/relayproto"
)

type Listener struct {
	addr  string
	cache *routing.Cache
	state *registry.State
	log   *slog.Logger

	ln net.Listener
}

func New(addr string, cache *routing.Cache, state *registry.State, log *slog.Logger) *Listener {
	return &Listener{addr: addr, cache: cache, state: state, log: log}
}

func (l *Listener) Serve(ctx context.Context) error {
	ln, err := net.Listen("tcp", l.addr)
	if err != nil {
		return err
	}
	l.ln = ln
	l.log.Info("ingress listener up", "addr", l.addr)

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			l.log.Warn("ingress accept error", "err", err)
			continue
		}
		go l.handle(ctx, conn)
	}
}

func (l *Listener) handle(ctx context.Context, conn net.Conn) {
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	hello, err := readHandshake(conn)
	if err != nil {
		_ = conn.Close()
		l.log.Warn("handshake parse failed", "remote", conn.RemoteAddr().String(), "err", err)
		return
	}
	_ = conn.SetDeadline(time.Time{})

	entry, ok := l.cache.Lookup(routing.NormalizeHostname(hello.Hostname))
	if !ok {
		_ = conn.Close()
		l.log.Warn("no route for hostname", "hostname", hello.Hostname, "remote", conn.RemoteAddr().String())
		return
	}

	keeperID, err := uuid.Parse(entry.KeeperID)
	if err != nil {
		_ = conn.Close()
		l.log.Warn("invalid keeper id in routing entry", "keeper_id", entry.KeeperID, "err", err)
		return
	}
	if keeperConn, ok := l.state.LocalKeeper(keeperID); ok {
		l.handleLocal(ctx, conn, keeperConn, entry, hello)
		return
	}
	if peerConn, ok := l.state.ResolvePeerForKeeper(keeperID); ok {
		l.handleRemote(ctx, conn, peerConn, entry, hello)
		return
	}

	_ = conn.Close()
	l.log.Warn("route exists but no active keeper affinity", "hostname", hello.Hostname, "keeper_id", entry.KeeperID)
}

func (l *Listener) handleLocal(ctx context.Context, conn net.Conn, keeperConn *registry.KeeperConn, entry routing.Entry, hello *handshakeInfo) {
	streamID := keeperConn.AllocStreamID()
	bridge := newOutboundBridge(conn, streamID, keeperConn.SendFrame, keeperConn.RemoveHandler)
	keeperConn.AddHandler(streamID, bridge)

	instanceID, _ := uuid.Parse(entry.InstanceID)
	payload, err := relayproto.EncodeStreamOpen(relayproto.StreamOpenPayload{
		InstanceID:    instanceID,
		HostPort:      uint16(entry.HostPort),
		ServerAddress: hello.Hostname,
	})
	if err != nil {
		_ = conn.Close()
		return
	}
	if err := keeperConn.SendFrame(ctx, relayproto.Frame{
		Type:     relayproto.FrameStreamOpen,
		StreamID: streamID,
		Payload:  payload,
	}); err != nil {
		keeperConn.RemoveHandler(streamID)
		_ = conn.Close()
		return
	}
	if err := bridge.WaitOpen(ctx); err != nil {
		keeperConn.RemoveHandler(streamID)
		_ = conn.Close()
		l.log.Warn("local stream open failed", "hostname", hello.Hostname, "err", err)
		return
	}
	bridge.Start(hello.RawHandshake)
}

func (l *Listener) handleRemote(ctx context.Context, conn net.Conn, peerConn *registry.PeerConn, entry routing.Entry, hello *handshakeInfo) {
	streamID := peerConn.AllocStreamID()
	bridge := newOutboundBridge(conn, streamID, peerConn.SendFrame, peerConn.RemoveHandler)
	peerConn.AddHandler(streamID, bridge)

	instanceID, _ := uuid.Parse(entry.InstanceID)
	payload, err := relayproto.EncodeXRStreamOpen(relayproto.XRStreamOpenPayload{
		RemoteStreamID: streamID,
		InstanceID:     instanceID,
		HostPort:       uint16(entry.HostPort),
		ServerAddress:  hello.Hostname,
	})
	if err != nil {
		peerConn.RemoveHandler(streamID)
		_ = conn.Close()
		return
	}
	if err := peerConn.SendFrame(ctx, relayproto.Frame{
		Type:     relayproto.FrameXRStreamOpen,
		StreamID: streamID,
		Payload:  payload,
	}); err != nil {
		peerConn.RemoveHandler(streamID)
		_ = conn.Close()
		return
	}
	if err := bridge.WaitOpen(ctx); err != nil {
		peerConn.RemoveHandler(streamID)
		_ = conn.Close()
		l.log.Warn("remote stream open failed", "hostname", hello.Hostname, "err", err)
		return
	}
	bridge.Start(hello.RawHandshake)
}
