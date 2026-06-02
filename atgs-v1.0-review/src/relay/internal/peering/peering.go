package peering

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/relay/internal/registry"
	"github.com/xkstudios/atgs/relay/internal/routing"
	"github.com/xkstudios/atgs/shared/relayproto"
)

type Server struct {
	addr   string
	tls    *tls.Config
	state  *registry.State
	cache  *routing.Cache
	peers  []string
	log    *slog.Logger
	server *http.Server
}

func NewServer(addr string, tlsCfg *tls.Config, state *registry.State, cache *routing.Cache, peers []string, log *slog.Logger) *Server {
	return &Server{addr: addr, tls: tlsCfg, state: state, cache: cache, peers: peers, log: log}
}

func (s *Server) Serve(ctx context.Context) error {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /peer", s.handlePeer)

	s.server = &http.Server{
		Addr:      s.addr,
		Handler:   mux,
		TLSConfig: s.tls,
	}

	for _, endpoint := range s.peers {
		go s.dialLoop(ctx, endpoint)
	}

	go func() {
		<-ctx.Done()
		_ = s.server.Close()
	}()

	s.log.Info("peer listener up", "addr", s.addr, "tls", true)
	err := s.server.ListenAndServeTLS("", "")
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Server) handlePeer(w http.ResponseWriter, r *http.Request) {
	if r.TLS == nil || len(r.TLS.VerifiedChains) == 0 || len(r.TLS.PeerCertificates) == 0 {
		http.Error(w, "client certificate required", http.StatusUnauthorized)
		return
	}
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols: []string{relayproto.SubprotocolV1},
	})
	if err != nil {
		s.log.Warn("peer ws accept failed", "err", err)
		return
	}
	peerID, err := uuid.Parse(r.TLS.PeerCertificates[0].Subject.CommonName)
	if err != nil {
		_ = ws.Close(websocket.StatusPolicyViolation, "invalid peer cert")
		return
	}
	if err := s.bootstrapSession(r.Context(), ws, peerID, false); err != nil && !errors.Is(err, context.Canceled) {
		s.log.Warn("peer session ended", "peer_id", peerID, "err", err)
	}
}

func (s *Server) dialLoop(ctx context.Context, endpoint string) {
	for {
		if err := ctx.Err(); err != nil {
			return
		}
		if err := s.dialOnce(ctx, endpoint); err != nil && !errors.Is(err, context.Canceled) {
			s.log.Warn("peer dial failed", "endpoint", endpoint, "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}
	}
}

func (s *Server) dialOnce(ctx context.Context, endpoint string) error {
	u := &url.URL{Scheme: "wss", Host: endpoint, Path: "/peer"}
	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate(s.tls.Certificates),
		RootCAs:      s.tls.ClientCAs,
		ServerName:   u.Hostname(),
		MinVersion:   tls.VersionTLS12,
	}
	httpClient := &http.Client{Transport: &http.Transport{TLSClientConfig: tlsCfg}}
	ws, resp, err := websocket.Dial(ctx, u.String(), &websocket.DialOptions{
		HTTPClient:   httpClient,
		Subprotocols: []string{relayproto.SubprotocolV1},
	})
	if err != nil {
		return err
	}

	frame := relayproto.Frame{
		Type:     relayproto.FrameHello,
		StreamID: 0,
		Payload: relayproto.EncodeRelayHello(relayproto.RelayHelloPayload{
			RelayID:         s.state.RelayID(),
			SessionID:       uuid.New(),
			ProtocolVersion: relayproto.ProtocolVersion,
		}),
	}
	buf, err := relayproto.Encode(frame)
	if err != nil {
		return err
	}
	if err := ws.Write(ctx, websocket.MessageBinary, buf); err != nil {
		return err
	}

	peerFrame, err := readFrame(ctx, ws)
	if err != nil {
		return err
	}
	if peerFrame.Type != relayproto.FrameHelloAck {
		return fmt.Errorf("expected peer hello ack, got %s", peerFrame.Type)
	}
	ack, err := relayproto.DecodeHelloAck(peerFrame.Payload)
	if err != nil {
		return err
	}
	peerID, err := peerIDFromResponse(resp)
	if err != nil {
		return err
	}
	return s.bootstrapSessionWithPeerSession(ctx, ws, peerID, ack.SessionID, true)
}

func (s *Server) bootstrapSession(ctx context.Context, ws *websocket.Conn, certPeerID uuid.UUID, outbound bool) error {
	peerID := certPeerID
	var peerSessionID uuid.UUID
	if !outbound {
		frame, err := readFrame(ctx, ws)
		if err != nil {
			return err
		}
		if frame.Type != relayproto.FrameHello || frame.StreamID != 0 {
			return errors.New("expected relay hello")
		}
		hello, err := relayproto.DecodeRelayHello(frame.Payload)
		if err != nil {
			return err
		}
		if hello.RelayID != certPeerID || hello.ProtocolVersion != relayproto.ProtocolVersion {
			return errors.New("peer hello mismatch")
		}
		peerID = hello.RelayID
		peerSessionID = hello.SessionID
	}
	return s.bootstrapSessionWithPeerSession(ctx, ws, peerID, peerSessionID, outbound)
}

func (s *Server) bootstrapSessionWithPeerSession(ctx context.Context, ws *websocket.Conn, peerID uuid.UUID, peerSessionID uuid.UUID, outbound bool) error {
	relaySessionID := uuid.New()
	conn := registry.NewPeerConn(ws, peerID, peerSessionID, relaySessionID)
	if replaced := s.state.RegisterPeer(conn); replaced != nil {
		_ = replaced.Close(websocket.StatusNormalClosure, "superseded")
	}
	if !outbound {
		if err := conn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameHelloAck,
			StreamID: 0,
			Payload:  relayproto.EncodeHelloAck(relayproto.HelloAckPayload{SessionID: relaySessionID}),
		}); err != nil {
			return err
		}
	}
	for _, ann := range s.state.LocalAnnouncements() {
		_ = conn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameXRKeeperOnline,
			StreamID: 0,
			Payload:  relayproto.EncodeKeeperAnnouncement(ann),
		})
	}
	err := s.readLoop(ctx, conn)
	s.state.UnregisterPeer(conn.PeerID, relaySessionID)
	conn.FailHandlers(err)
	_ = conn.Close(websocket.StatusNormalClosure, "session ended")
	return err
}

func (s *Server) readLoop(ctx context.Context, conn *registry.PeerConn) error {
	for {
		frame, err := conn.ReadBinary(ctx)
		if err != nil {
			return err
		}
		switch frame.Type {
		case relayproto.FramePing:
			if err := conn.SendFrame(ctx, relayproto.Frame{Type: relayproto.FramePong, StreamID: 0}); err != nil {
				return err
			}
		case relayproto.FrameXRKeeperOnline:
			ann, err := relayproto.DecodeKeeperAnnouncement(frame.Payload)
			if err == nil {
				s.state.UpdateAffinity(ann.KeeperID, ann.RelayID, ann.SinceUnixNano)
			}
		case relayproto.FrameXRKeeperOffline:
			ann, err := relayproto.DecodeKeeperAnnouncement(frame.Payload)
			if err == nil {
				s.state.RemoveAffinity(ann.KeeperID, ann.RelayID, ann.SinceUnixNano)
			}
		case relayproto.FrameXRStreamOpen:
			go s.handleXRStreamOpen(ctx, conn, frame)
		case relayproto.FrameXRDatagramOpen:
			go s.handleXRDatagramOpen(ctx, conn, frame)
		default:
			conn.DispatchFrame(ctx, frame)
		}
	}
}

func (s *Server) handleXRStreamOpen(ctx context.Context, peerConn *registry.PeerConn, frame relayproto.Frame) {
	openPayload, err := relayproto.DecodeXRStreamOpen(frame.Payload)
	if err != nil {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{Type: relayproto.FrameXRStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeProtocolError, Message: err.Error()})})
		return
	}

	entry, ok := s.cache.Lookup(routing.NormalizeHostname(openPayload.ServerAddress))
	if !ok {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{Type: relayproto.FrameXRStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeInstanceNotFound, Message: "route not found"})})
		return
	}
	keeperID, err := uuid.Parse(entry.KeeperID)
	if err != nil {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{Type: relayproto.FrameXRStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeProtocolError, Message: err.Error()})})
		return
	}
	keeperEntry, ok := s.state.LocalKeeper(keeperID)
	if !ok {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{Type: relayproto.FrameXRStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeKeeperNotHere, Message: "keeper not connected here"})})
		return
	}
	keeperStreamID := keeperEntry.AllocStreamID()
	proxy := &peerProxy{
		peerConn:       peerConn,
		peerStreamID:   frame.StreamID,
		keeperConn:     keeperEntry,
		keeperStreamID: keeperStreamID,
	}
	peerConn.AddHandler(frame.StreamID, proxy.peerSide())
	keeperEntry.AddHandler(keeperStreamID, proxy.keeperSide())

	payload, err := relayproto.EncodeStreamOpen(relayproto.StreamOpenPayload{
		InstanceID:    openPayload.InstanceID,
		HostPort:      openPayload.HostPort,
		ServerAddress: openPayload.ServerAddress,
	})
	if err != nil {
		return
	}
	if err := keeperEntry.SendFrame(ctx, relayproto.Frame{
		Type:     relayproto.FrameStreamOpen,
		StreamID: keeperStreamID,
		Payload:  payload,
	}); err != nil {
		proxy.cleanup()
	}
}

func (s *Server) handleXRDatagramOpen(ctx context.Context, peerConn *registry.PeerConn, frame relayproto.Frame) {
	openPayload, err := relayproto.DecodeXRDatagramOpen(frame.Payload)
	if err != nil {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameXRDatagramOpenErr,
			StreamID: frame.StreamID,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeProtocolError, Message: err.Error()}),
		})
		return
	}

	entry, ok := s.cache.LookupPublicPort(int(openPayload.PublicPort))
	if !ok {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameXRDatagramOpenErr,
			StreamID: frame.StreamID,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeInstanceNotFound, Message: "bedrock route not found"}),
		})
		return
	}
	keeperID, err := uuid.Parse(entry.KeeperID)
	if err != nil {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameXRDatagramOpenErr,
			StreamID: frame.StreamID,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeProtocolError, Message: err.Error()}),
		})
		return
	}
	keeperEntry, ok := s.state.LocalKeeper(keeperID)
	if !ok {
		_ = peerConn.SendFrame(ctx, relayproto.Frame{
			Type:     relayproto.FrameXRDatagramOpenErr,
			StreamID: frame.StreamID,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeKeeperNotHere, Message: "keeper not connected here"}),
		})
		return
	}
	keeperStreamID := keeperEntry.AllocStreamID()
	proxy := &peerDatagramProxy{
		peerConn:       peerConn,
		peerStreamID:   frame.StreamID,
		keeperConn:     keeperEntry,
		keeperStreamID: keeperStreamID,
	}
	peerConn.AddHandler(frame.StreamID, proxy.peerSide())
	keeperEntry.AddHandler(keeperStreamID, proxy.keeperSide())

	if err := keeperEntry.SendFrame(ctx, relayproto.Frame{
		Type:     relayproto.FrameDatagramOpen,
		StreamID: keeperStreamID,
		Payload: relayproto.EncodeDatagramOpen(relayproto.DatagramOpenPayload{
			InstanceID: openPayload.InstanceID,
			HostPort:   openPayload.HostPort,
			PublicPort: openPayload.PublicPort,
		}),
	}); err != nil {
		proxy.cleanup()
	}
}

type peerProxy struct {
	peerConn       *registry.PeerConn
	peerStreamID   uint32
	keeperConn     *registry.KeeperConn
	keeperStreamID uint32
	once           sync.Once
}

func (p *peerProxy) cleanup() {
	p.once.Do(func() {
		p.peerConn.RemoveHandler(p.peerStreamID)
		p.keeperConn.RemoveHandler(p.keeperStreamID)
	})
}

func (p *peerProxy) peerSide() registry.FrameHandler {
	return frameHandlerFuncs{
		handle: func(ctx context.Context, frame relayproto.Frame) {
			switch frame.Type {
			case relayproto.FrameData, relayproto.FrameStreamClose:
				frame.StreamID = p.keeperStreamID
				_ = p.keeperConn.SendFrame(ctx, frame)
				if frame.Type == relayproto.FrameStreamClose {
					p.cleanup()
				}
			}
		},
		disconnect: func(err error) {
			msg := "peer disconnected"
			if err != nil {
				msg = err.Error()
			}
			_ = p.keeperConn.SendFrame(context.Background(), relayproto.Frame{
				Type:     relayproto.FrameStreamClose,
				StreamID: p.keeperStreamID,
				Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeRelayDisconnect, Message: msg}),
			})
			p.cleanup()
		},
	}
}

type peerDatagramProxy struct {
	peerConn       *registry.PeerConn
	peerStreamID   uint32
	keeperConn     *registry.KeeperConn
	keeperStreamID uint32
	once           sync.Once
}

func (p *peerDatagramProxy) cleanup() {
	p.once.Do(func() {
		p.peerConn.RemoveHandler(p.peerStreamID)
		p.keeperConn.RemoveHandler(p.keeperStreamID)
	})
}

func (p *peerDatagramProxy) peerSide() registry.FrameHandler {
	return frameHandlerFuncs{
		handle: func(ctx context.Context, frame relayproto.Frame) {
			switch frame.Type {
			case relayproto.FrameData, relayproto.FrameStreamClose:
				frame.StreamID = p.keeperStreamID
				_ = p.keeperConn.SendFrame(ctx, frame)
				if frame.Type == relayproto.FrameStreamClose {
					p.cleanup()
				}
			}
		},
		disconnect: func(err error) {
			msg := "peer disconnected"
			if err != nil {
				msg = err.Error()
			}
			_ = p.keeperConn.SendFrame(context.Background(), relayproto.Frame{
				Type:     relayproto.FrameStreamClose,
				StreamID: p.keeperStreamID,
				Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeRelayDisconnect, Message: msg}),
			})
			p.cleanup()
		},
	}
}

func (p *peerDatagramProxy) keeperSide() registry.FrameHandler {
	return frameHandlerFuncs{
		handle: func(ctx context.Context, frame relayproto.Frame) {
			switch frame.Type {
			case relayproto.FrameDatagramOpenAck:
				_ = p.peerConn.SendFrame(ctx, relayproto.Frame{
					Type:     relayproto.FrameXRDatagramOpenAck,
					StreamID: p.peerStreamID,
				})
			case relayproto.FrameDatagramOpenErr:
				frame.Type = relayproto.FrameXRDatagramOpenErr
				frame.StreamID = p.peerStreamID
				_ = p.peerConn.SendFrame(ctx, frame)
				p.cleanup()
			case relayproto.FrameData, relayproto.FrameStreamClose:
				frame.StreamID = p.peerStreamID
				_ = p.peerConn.SendFrame(ctx, frame)
				if frame.Type == relayproto.FrameStreamClose {
					p.cleanup()
				}
			}
		},
		disconnect: func(err error) {
			msg := "keeper disconnected"
			if err != nil {
				msg = err.Error()
			}
			_ = p.peerConn.SendFrame(context.Background(), relayproto.Frame{
				Type:     relayproto.FrameStreamClose,
				StreamID: p.peerStreamID,
				Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeKeeperDisconnect, Message: msg}),
			})
			p.cleanup()
		},
	}
}

func (p *peerProxy) keeperSide() registry.FrameHandler {
	return frameHandlerFuncs{
		handle: func(ctx context.Context, frame relayproto.Frame) {
			switch frame.Type {
			case relayproto.FrameStreamOpenAck:
				_ = p.peerConn.SendFrame(ctx, relayproto.Frame{Type: relayproto.FrameXRStreamOpenAck, StreamID: p.peerStreamID})
			case relayproto.FrameStreamOpenErr:
				frame.Type = relayproto.FrameXRStreamOpenErr
				frame.StreamID = p.peerStreamID
				_ = p.peerConn.SendFrame(ctx, frame)
				p.cleanup()
			case relayproto.FrameData, relayproto.FrameStreamClose:
				frame.StreamID = p.peerStreamID
				_ = p.peerConn.SendFrame(ctx, frame)
				if frame.Type == relayproto.FrameStreamClose {
					p.cleanup()
				}
			}
		},
		disconnect: func(err error) {
			msg := "keeper disconnected"
			if err != nil {
				msg = err.Error()
			}
			_ = p.peerConn.SendFrame(context.Background(), relayproto.Frame{
				Type:     relayproto.FrameStreamClose,
				StreamID: p.peerStreamID,
				Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeKeeperDisconnect, Message: msg}),
			})
			p.cleanup()
		},
	}
}

type frameHandlerFuncs struct {
	handle     func(context.Context, relayproto.Frame)
	disconnect func(error)
}

func (f frameHandlerFuncs) HandleFrame(ctx context.Context, frame relayproto.Frame) {
	if f.handle != nil {
		f.handle(ctx, frame)
	}
}

func (f frameHandlerFuncs) HandleDisconnect(err error) {
	if f.disconnect != nil {
		f.disconnect(err)
	}
}

func readFrame(ctx context.Context, ws *websocket.Conn) (relayproto.Frame, error) {
	var zero relayproto.Frame
	_, payload, err := ws.Read(ctx)
	if err != nil {
		return zero, err
	}
	return relayproto.Decode(payload)
}

func peerIDFromResponse(resp *http.Response) (uuid.UUID, error) {
	if resp == nil || resp.TLS == nil || len(resp.TLS.PeerCertificates) == 0 {
		return uuid.Nil, errors.New("missing peer certificate")
	}
	return uuid.Parse(resp.TLS.PeerCertificates[0].Subject.CommonName)
}
