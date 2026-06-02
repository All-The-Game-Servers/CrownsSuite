package registry

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/shared/relayproto"
)

type FrameHandler interface {
	HandleFrame(ctx context.Context, frame relayproto.Frame)
	HandleDisconnect(err error)
}

type Affinity struct {
	RelayID uuid.UUID
	Since   int64
}

type State struct {
	relayID uuid.UUID
	log     *slog.Logger

	mu         sync.RWMutex
	keepers    map[uuid.UUID]*KeeperConn
	peers      map[uuid.UUID]*PeerConn
	affinities map[uuid.UUID]Affinity
}

func NewState(relayID uuid.UUID, log *slog.Logger) *State {
	return &State{
		relayID:    relayID,
		log:        log,
		keepers:    make(map[uuid.UUID]*KeeperConn),
		peers:      make(map[uuid.UUID]*PeerConn),
		affinities: make(map[uuid.UUID]Affinity),
	}
}

func (s *State) RelayID() uuid.UUID {
	return s.relayID
}

func (s *State) RegisterKeeper(conn *KeeperConn) (replaced *KeeperConn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if prev, ok := s.keepers[conn.KeeperID]; ok {
		replaced = prev
	}
	s.keepers[conn.KeeperID] = conn
	s.affinities[conn.KeeperID] = Affinity{RelayID: s.relayID, Since: time.Now().UnixNano()}
	return replaced
}

func (s *State) UnregisterKeeper(keeperID uuid.UUID, relaySessionID uuid.UUID) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cur, ok := s.keepers[keeperID]
	if !ok || cur.RelaySessionID != relaySessionID {
		return
	}
	delete(s.keepers, keeperID)
	if aff, ok := s.affinities[keeperID]; ok && aff.RelayID == s.relayID {
		delete(s.affinities, keeperID)
	}
}

func (s *State) LocalKeeper(keeperID uuid.UUID) (*KeeperConn, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	conn, ok := s.keepers[keeperID]
	return conn, ok
}

func (s *State) RegisterPeer(conn *PeerConn) (replaced *PeerConn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if prev, ok := s.peers[conn.PeerID]; ok {
		replaced = prev
	}
	s.peers[conn.PeerID] = conn
	return replaced
}

func (s *State) UnregisterPeer(peerID uuid.UUID, relaySessionID uuid.UUID) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cur, ok := s.peers[peerID]
	if !ok || cur.RelaySessionID != relaySessionID {
		return
	}
	delete(s.peers, peerID)
	for keeperID, aff := range s.affinities {
		if aff.RelayID == peerID {
			delete(s.affinities, keeperID)
		}
	}
}

func (s *State) Peer(peerID uuid.UUID) (*PeerConn, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	conn, ok := s.peers[peerID]
	return conn, ok
}

func (s *State) UpdateAffinity(keeperID uuid.UUID, relayID uuid.UUID, since int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cur, ok := s.affinities[keeperID]
	if !ok || since >= cur.Since {
		s.affinities[keeperID] = Affinity{RelayID: relayID, Since: since}
	}
}

func (s *State) RemoveAffinity(keeperID uuid.UUID, relayID uuid.UUID, since int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cur, ok := s.affinities[keeperID]
	if !ok {
		return
	}
	if cur.RelayID == relayID && since >= cur.Since {
		delete(s.affinities, keeperID)
	}
}

func (s *State) ResolvePeerForKeeper(keeperID uuid.UUID) (*PeerConn, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	aff, ok := s.affinities[keeperID]
	if !ok || aff.RelayID == s.relayID {
		return nil, false
	}
	peer, ok := s.peers[aff.RelayID]
	return peer, ok
}

func (s *State) LocalAnnouncements() []relayproto.KeeperAnnouncement {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]relayproto.KeeperAnnouncement, 0, len(s.keepers))
	now := time.Now().UnixNano()
	for keeperID := range s.keepers {
		out = append(out, relayproto.KeeperAnnouncement{
			KeeperID:      keeperID,
			RelayID:       s.relayID,
			SinceUnixNano: now,
		})
	}
	return out
}

func (s *State) BroadcastAnnouncement(ctx context.Context, frameType relayproto.FrameType, ann relayproto.KeeperAnnouncement) {
	s.mu.RLock()
	peers := make([]*PeerConn, 0, len(s.peers))
	for _, peer := range s.peers {
		peers = append(peers, peer)
	}
	s.mu.RUnlock()

	payload := relayproto.EncodeKeeperAnnouncement(ann)
	frame := relayproto.Frame{Type: frameType, StreamID: 0, Payload: payload}
	for _, peer := range peers {
		if err := peer.SendFrame(ctx, frame); err != nil {
			s.log.Warn("broadcast peer announcement failed", "peer_id", peer.PeerID, "err", err)
		}
	}
}

type wsSession struct {
	ws             *websocket.Conn
	writeMu        sync.Mutex
	relaySessionID uuid.UUID
	nextStreamID   atomic.Uint32

	handlersMu sync.RWMutex
	handlers   map[uint32]FrameHandler
}

func newWSSession(ws *websocket.Conn, relaySessionID uuid.UUID) wsSession {
	s := wsSession{
		ws:             ws,
		relaySessionID: relaySessionID,
		handlers:       make(map[uint32]FrameHandler),
	}
	s.nextStreamID.Store(1)
	return s
}

func (s *wsSession) SendFrame(ctx context.Context, frame relayproto.Frame) error {
	buf, err := relayproto.Encode(frame)
	if err != nil {
		return err
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.ws.Write(ctx, websocket.MessageBinary, buf)
}

func (s *wsSession) ReadBinary(ctx context.Context) (relayproto.Frame, error) {
	var zero relayproto.Frame
	_, payload, err := s.ws.Read(ctx)
	if err != nil {
		return zero, err
	}
	return relayproto.Decode(payload)
}

func (s *wsSession) Close(status websocket.StatusCode, reason string) error {
	return s.ws.Close(status, reason)
}

func (s *wsSession) AddHandler(streamID uint32, handler FrameHandler) {
	s.handlersMu.Lock()
	defer s.handlersMu.Unlock()
	s.handlers[streamID] = handler
}

func (s *wsSession) RemoveHandler(streamID uint32) {
	s.handlersMu.Lock()
	defer s.handlersMu.Unlock()
	delete(s.handlers, streamID)
}

func (s *wsSession) DispatchFrame(ctx context.Context, frame relayproto.Frame) {
	s.handlersMu.RLock()
	handler := s.handlers[frame.StreamID]
	s.handlersMu.RUnlock()
	if handler != nil {
		handler.HandleFrame(ctx, frame)
	}
}

func (s *wsSession) FailHandlers(err error) {
	s.handlersMu.Lock()
	handlers := s.handlers
	s.handlers = make(map[uint32]FrameHandler)
	s.handlersMu.Unlock()
	for _, handler := range handlers {
		handler.HandleDisconnect(err)
	}
}

func (s *wsSession) AllocStreamID() uint32 {
	return s.nextStreamID.Add(1)
}

type KeeperConn struct {
	wsSession
	KeeperID         uuid.UUID
	KeeperSessionID  uuid.UUID
	RelaySessionID   uuid.UUID
	LastSeen         time.Time
}

func NewKeeperConn(ws *websocket.Conn, keeperID uuid.UUID, keeperSessionID uuid.UUID, relaySessionID uuid.UUID) *KeeperConn {
	return &KeeperConn{
		wsSession:       newWSSession(ws, relaySessionID),
		KeeperID:        keeperID,
		KeeperSessionID: keeperSessionID,
		RelaySessionID:  relaySessionID,
		LastSeen:        time.Now().UTC(),
	}
}

type PeerConn struct {
	wsSession
	PeerID         uuid.UUID
	PeerSessionID  uuid.UUID
	RelaySessionID uuid.UUID
}

func NewPeerConn(ws *websocket.Conn, peerID uuid.UUID, peerSessionID uuid.UUID, relaySessionID uuid.UUID) *PeerConn {
	return &PeerConn{
		wsSession:       newWSSession(ws, relaySessionID),
		PeerID:          peerID,
		PeerSessionID:   peerSessionID,
		RelaySessionID:  relaySessionID,
	}
}

var ErrUnexpectedClose = errors.New("session closed")
