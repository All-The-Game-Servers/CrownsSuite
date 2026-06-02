package ingress

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/relay/internal/registry"
	"github.com/xkstudios/atgs/relay/internal/routing"
	"github.com/xkstudios/atgs/shared/relayproto"
)

type BedrockManager struct {
	bindHost string
	idle     time.Duration
	cache    *routing.Cache
	state    *registry.State
	log      *slog.Logger

	mu        sync.Mutex
	listeners map[int]*bedrockListener
}

func NewBedrock(bindHost string, idle time.Duration, cache *routing.Cache, state *registry.State, log *slog.Logger) *BedrockManager {
	if bindHost == "" {
		bindHost = "0.0.0.0"
	}
	if idle <= 0 {
		idle = 90 * time.Second
	}
	return &BedrockManager{
		bindHost:  bindHost,
		idle:      idle,
		cache:     cache,
		state:     state,
		log:       log,
		listeners: make(map[int]*bedrockListener),
	}
}

func (m *BedrockManager) Serve(ctx context.Context) error {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	if err := m.reconcile(ctx); err != nil {
		return err
	}
	for {
		select {
		case <-ctx.Done():
			m.closeAll()
			return ctx.Err()
		case <-ticker.C:
			if err := m.reconcile(ctx); err != nil {
				m.log.Warn("bedrock reconcile failed", "err", err)
			}
		}
	}
}

func (m *BedrockManager) reconcile(ctx context.Context) error {
	entries := m.cache.Entries()
	want := make(map[int]routing.Entry)
	for _, e := range entries {
		if e.RouteKind == "bedrock_udp" && e.PublicPort > 0 {
			want[e.PublicPort] = e
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	for port, entry := range want {
		if _, ok := m.listeners[port]; ok {
			continue
		}
		addr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%d", m.bindHost, port))
		if err != nil {
			return err
		}
		conn, err := net.ListenUDP("udp", addr)
		if err != nil {
			return err
		}
		l := &bedrockListener{
			publicPort: port,
			conn:       conn,
			cache:      m.cache,
			state:      m.state,
			log:        m.log.With("bedrock_public_port", port),
			idle:       m.idle,
			sessions:   make(map[string]*udpOutboundSession),
		}
		m.listeners[port] = l
		go l.serve(ctx, entry)
		m.log.Info("bedrock ingress listener up", "public_port", port)
	}
	for port, listener := range m.listeners {
		if _, ok := want[port]; ok {
			continue
		}
		_ = listener.conn.Close()
		delete(m.listeners, port)
		m.log.Info("bedrock ingress listener removed", "public_port", port)
	}
	return nil
}

func (m *BedrockManager) closeAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for port, l := range m.listeners {
		_ = l.conn.Close()
		delete(m.listeners, port)
	}
}

type bedrockListener struct {
	publicPort int
	conn       *net.UDPConn
	cache      *routing.Cache
	state      *registry.State
	log        *slog.Logger
	idle       time.Duration

	mu       sync.Mutex
	sessions map[string]*udpOutboundSession
}

func (l *bedrockListener) serve(ctx context.Context, entry routing.Entry) {
	go l.sweep(ctx)
	buf := make([]byte, relayproto.MaxPayloadSize)
	for {
		n, addr, err := l.conn.ReadFromUDP(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) || ctx.Err() != nil {
				return
			}
			l.log.Warn("bedrock udp read failed", "err", err)
			continue
		}
		current, ok := l.cache.LookupPublicPort(l.publicPort)
		if !ok {
			continue
		}
		payload := make([]byte, n)
		copy(payload, buf[:n])
		l.handlePacket(ctx, current, addr, payload)
	}
}

func (l *bedrockListener) handlePacket(ctx context.Context, entry routing.Entry, addr *net.UDPAddr, payload []byte) {
	key := addr.String()
	l.mu.Lock()
	session := l.sessions[key]
	l.mu.Unlock()
	if session == nil {
		var err error
		session, err = l.openSession(ctx, entry, addr)
		if err != nil {
			l.log.Warn("bedrock session open failed", "remote", addr.String(), "err", err)
			return
		}
		l.mu.Lock()
		l.sessions[key] = session
		l.mu.Unlock()
	}
	session.touch()
	if err := session.sendFrame(ctx, relayproto.Frame{Type: relayproto.FrameData, StreamID: session.streamID, Payload: payload}); err != nil {
		l.log.Warn("bedrock packet forward failed", "remote", addr.String(), "err", err)
		session.close(true)
	}
}

func (l *bedrockListener) openSession(ctx context.Context, entry routing.Entry, addr *net.UDPAddr) (*udpOutboundSession, error) {
	keeperID, err := uuid.Parse(entry.KeeperID)
	if err != nil {
		return nil, err
	}
	instanceID, err := uuid.Parse(entry.InstanceID)
	if err != nil {
		return nil, err
	}
	if keeperConn, ok := l.state.LocalKeeper(keeperID); ok {
		streamID := keeperConn.AllocStreamID()
		session := newUDPOutboundSession(l, addr, streamID, keeperConn.SendFrame, keeperConn.RemoveHandler, false)
		keeperConn.AddHandler(streamID, session)
		if err := keeperConn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameDatagramOpen,
			StreamID: streamID,
			Payload: relayproto.EncodeDatagramOpen(relayproto.DatagramOpenPayload{
				InstanceID: instanceID,
				HostPort:   uint16(entry.HostPort),
				PublicPort: uint16(l.publicPort),
			}),
		}); err != nil {
			keeperConn.RemoveHandler(streamID)
			return nil, err
		}
		return session, nil
	}
	if peerConn, ok := l.state.ResolvePeerForKeeper(keeperID); ok {
		streamID := peerConn.AllocStreamID()
		session := newUDPOutboundSession(l, addr, streamID, peerConn.SendFrame, peerConn.RemoveHandler, true)
		peerConn.AddHandler(streamID, session)
		if err := peerConn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameXRDatagramOpen,
			StreamID: streamID,
			Payload: relayproto.EncodeXRDatagramOpen(relayproto.XRDatagramOpenPayload{
				RemoteStreamID: streamID,
				InstanceID:     instanceID,
				HostPort:       uint16(entry.HostPort),
				PublicPort:     uint16(l.publicPort),
			}),
		}); err != nil {
			peerConn.RemoveHandler(streamID)
			return nil, err
		}
		return session, nil
	}
	return nil, fmt.Errorf("no active keeper affinity for %s", entry.KeeperID)
}

func (l *bedrockListener) removeSession(addr string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.sessions, addr)
}

func (l *bedrockListener) sweep(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			now := time.Now()
			l.mu.Lock()
			sessions := make([]*udpOutboundSession, 0, len(l.sessions))
			for _, session := range l.sessions {
				if now.Sub(session.lastSeen) > l.idle {
					sessions = append(sessions, session)
				}
			}
			l.mu.Unlock()
			for _, session := range sessions {
				session.close(true)
			}
		}
	}
}

type udpOutboundSession struct {
	listener *bedrockListener
	clientAddr *net.UDPAddr
	streamID uint32
	send func(context.Context, relayproto.Frame) error
	remove func(uint32)
	isPeer bool

	mu sync.Mutex
	lastSeen time.Time
	closed bool
}

func newUDPOutboundSession(listener *bedrockListener, clientAddr *net.UDPAddr, streamID uint32, send func(context.Context, relayproto.Frame) error, remove func(uint32), isPeer bool) *udpOutboundSession {
	return &udpOutboundSession{
		listener: listener,
		clientAddr: clientAddr,
		streamID: streamID,
		send: send,
		remove: remove,
		isPeer: isPeer,
		lastSeen: time.Now(),
	}
}

func (s *udpOutboundSession) HandleFrame(ctx context.Context, frame relayproto.Frame) {
	s.touch()
	switch frame.Type {
	case relayproto.FrameDatagramOpenAck, relayproto.FrameXRDatagramOpenAck:
		return
	case relayproto.FrameDatagramOpenErr, relayproto.FrameXRDatagramOpenErr:
		s.close(false)
	case relayproto.FrameData:
		_, _ = s.listener.conn.WriteToUDP(frame.Payload, s.clientAddr)
	case relayproto.FrameStreamClose:
		s.close(false)
	}
}

func (s *udpOutboundSession) HandleDisconnect(err error) {
	s.close(false)
}

func (s *udpOutboundSession) touch() {
	s.mu.Lock()
	s.lastSeen = time.Now()
	s.mu.Unlock()
}

func (s *udpOutboundSession) sendFrame(ctx context.Context, frame relayproto.Frame) error {
	return s.send(ctx, frame)
}

func (s *udpOutboundSession) close(notify bool) {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	s.mu.Unlock()
	if notify {
		_ = s.send(context.Background(), relayproto.Frame{
			Type:     relayproto.FrameStreamClose,
			StreamID: s.streamID,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeNormalClose}),
		})
	}
	if s.remove != nil {
		s.remove(s.streamID)
	}
	s.listener.removeSession(s.clientAddr.String())
}
