package datachannel

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/relay/internal/registry"
	"github.com/xkstudios/atgs/shared/relayproto"
)

type Server struct {
	addr  string
	tls   *tls.Config
	state *registry.State
	log   *slog.Logger

	srv *http.Server
}

func New(addr string, tlsCfg *tls.Config, state *registry.State, log *slog.Logger) *Server {
	return &Server{addr: addr, tls: tlsCfg, state: state, log: log}
}

func (s *Server) Serve(ctx context.Context) error {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ws/data", s.handleWS)
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	s.srv = &http.Server{
		Addr:      s.addr,
		Handler:   mux,
		TLSConfig: s.tls,
	}

	go func() {
		<-ctx.Done()
		_ = s.srv.Close()
	}()

	s.log.Info("data channel listener up", "addr", s.addr, "tls", true)
	err := s.srv.ListenAndServeTLS("", "")
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	if r.TLS == nil || len(r.TLS.VerifiedChains) == 0 || len(r.TLS.PeerCertificates) == 0 {
		http.Error(w, "client certificate required", http.StatusUnauthorized)
		return
	}

	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols: []string{relayproto.SubprotocolV1},
	})
	if err != nil {
		s.log.Warn("ws accept failed", "err", err)
		return
	}
	if ws.Subprotocol() != relayproto.SubprotocolV1 {
		_ = ws.Close(websocket.StatusPolicyViolation, "subprotocol mismatch")
		return
	}

	keeperID, err := uuid.Parse(r.TLS.PeerCertificates[0].Subject.CommonName)
	if err != nil {
		_ = ws.Close(websocket.StatusPolicyViolation, "invalid keeper cert cn")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()
	_, payload, err := ws.Read(ctx)
	if err != nil {
		_ = ws.Close(websocket.StatusNormalClosure, "missing hello")
		return
	}
	frame, err := relayproto.Decode(payload)
	if err != nil || frame.Type != relayproto.FrameHello || frame.StreamID != 0 {
		_ = ws.Close(websocket.StatusPolicyViolation, "expected hello")
		return
	}
	hello, err := relayproto.DecodeHello(frame.Payload)
	if err != nil || hello.KeeperID != keeperID || hello.ProtocolVersion != relayproto.ProtocolVersion {
		_ = ws.Close(websocket.StatusPolicyViolation, "bad hello")
		return
	}

	relaySessionID := uuid.New()
	conn := registry.NewKeeperConn(ws, keeperID, hello.SessionID, relaySessionID)
	if replaced := s.state.RegisterKeeper(conn); replaced != nil {
		_ = replaced.SendFrame(context.Background(), relayproto.Frame{
			Type:     relayproto.FrameStreamClose,
			StreamID: 0,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeKeeperDisconnect, Message: "superseded"}),
		})
		_ = replaced.Close(websocket.StatusNormalClosure, "superseded")
	}

	ack := relayproto.Frame{
		Type:     relayproto.FrameHelloAck,
		StreamID: 0,
		Payload:  relayproto.EncodeHelloAck(relayproto.HelloAckPayload{SessionID: relaySessionID}),
	}
	if err := conn.SendFrame(r.Context(), ack); err != nil {
		s.state.UnregisterKeeper(keeperID, relaySessionID)
		_ = ws.Close(websocket.StatusNormalClosure, "hello ack failed")
		return
	}
	s.state.BroadcastAnnouncement(r.Context(), relayproto.FrameXRKeeperOnline, relayproto.KeeperAnnouncement{
		KeeperID:      keeperID,
		RelayID:       s.state.RelayID(),
		SinceUnixNano: time.Now().UnixNano(),
	})

	s.log.Info("keeper data channel connected", "keeper_id", keeperID, "relay_session_id", relaySessionID, "keeper_session_id", hello.SessionID)

	readErr := s.readLoop(r.Context(), conn)
	s.state.UnregisterKeeper(keeperID, relaySessionID)
	s.state.BroadcastAnnouncement(context.Background(), relayproto.FrameXRKeeperOffline, relayproto.KeeperAnnouncement{
		KeeperID:      keeperID,
		RelayID:       s.state.RelayID(),
		SinceUnixNano: time.Now().UnixNano(),
	})
	conn.FailHandlers(readErr)
		_ = conn.Close(websocket.StatusNormalClosure, "session ended")
}

func (s *Server) readLoop(ctx context.Context, conn *registry.KeeperConn) error {
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
		case relayproto.FramePong:
		default:
			conn.DispatchFrame(ctx, frame)
		}
	}
}
