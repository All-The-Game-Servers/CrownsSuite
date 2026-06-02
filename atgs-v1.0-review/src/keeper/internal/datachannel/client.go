package datachannel

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/keeper/internal/enroll"
	"github.com/xkstudios/atgs/keeper/internal/localstore"
	"github.com/xkstudios/atgs/shared/relayproto"
)

type Config struct {
	Identity           *enroll.Identity
	RelayDataURLs      []string
	InsecureSkipVerify bool
	Store              *localstore.Store
	Log                *slog.Logger
}

type Client struct {
	cfg Config
}

func New(cfg Config) *Client {
	return &Client{cfg: cfg}
}

func (c *Client) Run(ctx context.Context) error {
	if len(c.cfg.RelayDataURLs) == 0 {
		return nil
	}
	idx := 0
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		target := c.cfg.RelayDataURLs[idx%len(c.cfg.RelayDataURLs)]
		err := c.runOnce(ctx, target)
		if errors.Is(err, context.Canceled) {
			return err
		}
		c.cfg.Log.Warn("relay data channel ended, will reconnect", "target", target, "err", err)
		idx++
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}
}

func (c *Client) runOnce(ctx context.Context, rawURL string) error {
	u, err := url.Parse(rawURL)
	if err != nil {
		return err
	}
	tlsCfg := &tls.Config{
		Certificates:       []tls.Certificate{c.cfg.Identity.Certificate},
		RootCAs:            c.cfg.Identity.CACertPool,
		ServerName:         u.Hostname(),
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: c.cfg.InsecureSkipVerify,
	}
	httpClient := &http.Client{Transport: &http.Transport{TLSClientConfig: tlsCfg}}
	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(dialCtx, rawURL, &websocket.DialOptions{
		HTTPClient:   httpClient,
		Subprotocols: []string{relayproto.SubprotocolV1},
	})
	if err != nil {
		return err
	}
	defer ws.Close(websocket.StatusNormalClosure, "keeper data client shutdown")

	keeperID, err := uuid.Parse(c.cfg.Identity.KeeperID)
	if err != nil {
		return err
	}
	sessionID := uuid.New()
	if err := writeFrame(ctx, ws, relayproto.Frame{
		Type:     relayproto.FrameHello,
		StreamID: 0,
		Payload: relayproto.EncodeHello(relayproto.HelloPayload{
			KeeperID:        keeperID,
			SessionID:       sessionID,
			ProtocolVersion: relayproto.ProtocolVersion,
		}),
	}); err != nil {
		return err
	}

	frame, err := readFrame(ctx, ws)
	if err != nil {
		return err
	}
	if frame.Type != relayproto.FrameHelloAck {
		return fmt.Errorf("expected hello ack, got %s", frame.Type)
	}
	if _, err := relayproto.DecodeHelloAck(frame.Payload); err != nil {
		return err
	}
	c.cfg.Log.Info("relay data channel connected", "target", rawURL, "keeper_id", keeperID, "session_id", sessionID)

	active := &activeSession{
		ws:     ws,
		store:  c.cfg.Store,
		log:    c.cfg.Log.With("relay_target", rawURL),
		streams: make(map[uint32]net.Conn),
		datagrams: make(map[uint32]*net.UDPConn),
	}
	return active.run(ctx)
}

type activeSession struct {
	ws    *websocket.Conn
	store *localstore.Store
	log   *slog.Logger

	writeMu sync.Mutex
	mu      sync.Mutex
	streams map[uint32]net.Conn
	datagrams map[uint32]*net.UDPConn
}

func (s *activeSession) run(ctx context.Context) error {
	for {
		frame, err := readFrame(ctx, s.ws)
		if err != nil {
			s.closeAll()
			return err
		}
		switch frame.Type {
		case relayproto.FramePing:
			if err := s.send(ctx, relayproto.Frame{Type: relayproto.FramePong, StreamID: 0}); err != nil {
				return err
			}
		case relayproto.FrameStreamOpen:
			go s.handleOpen(ctx, frame)
		case relayproto.FrameDatagramOpen:
			go s.handleDatagramOpen(ctx, frame)
		case relayproto.FrameData:
			s.handleData(frame)
		case relayproto.FrameStreamClose:
			s.handleClose(frame)
		}
	}
}

func (s *activeSession) handleOpen(ctx context.Context, frame relayproto.Frame) {
	openPayload, err := relayproto.DecodeStreamOpen(frame.Payload)
	if err != nil {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeProtocolError, Message: err.Error()})})
		return
	}
	inst, err := s.store.GetInstance(ctx, openPayload.InstanceID.String())
	if err != nil {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeInstanceNotFound, Message: err.Error()})})
		return
	}
	port := int(openPayload.HostPort)
	if port <= 0 {
		port = inst.HostPort
	}
	if port <= 0 {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeLocalDialFailed, Message: "missing host port"})})
		return
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 10*time.Second)
	if err != nil {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameStreamOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeLocalDialFailed, Message: err.Error()})})
		return
	}

	s.mu.Lock()
	s.streams[frame.StreamID] = conn
	s.mu.Unlock()

	if err := s.send(ctx, relayproto.Frame{Type: relayproto.FrameStreamOpenAck, StreamID: frame.StreamID}); err != nil {
		_ = conn.Close()
		return
	}

	go s.pumpLocalToRelay(frame.StreamID, conn)
}

func (s *activeSession) handleData(frame relayproto.Frame) {
	s.mu.Lock()
	conn := s.streams[frame.StreamID]
	udpConn := s.datagrams[frame.StreamID]
	s.mu.Unlock()
	if conn != nil {
		_, _ = conn.Write(frame.Payload)
	}
	if udpConn != nil {
		_, _ = udpConn.Write(frame.Payload)
	}
}

func (s *activeSession) handleClose(frame relayproto.Frame) {
	s.mu.Lock()
	conn := s.streams[frame.StreamID]
	udpConn := s.datagrams[frame.StreamID]
	delete(s.streams, frame.StreamID)
	delete(s.datagrams, frame.StreamID)
	s.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
	if udpConn != nil {
		_ = udpConn.Close()
	}
}

func (s *activeSession) handleDatagramOpen(ctx context.Context, frame relayproto.Frame) {
	openPayload, err := relayproto.DecodeDatagramOpen(frame.Payload)
	if err != nil {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameDatagramOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeProtocolError, Message: err.Error()})})
		return
	}
	inst, err := s.store.GetInstance(ctx, openPayload.InstanceID.String())
	if err != nil {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameDatagramOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeInstanceNotFound, Message: err.Error()})})
		return
	}
	port := int(openPayload.HostPort)
	if port <= 0 {
		port = inst.HostPort
	}
	if port <= 0 {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameDatagramOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeLocalDialFailed, Message: "missing host port"})})
		return
	}
	remoteAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameDatagramOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeLocalDialFailed, Message: err.Error()})})
		return
	}
	conn, err := net.DialUDP("udp", nil, remoteAddr)
	if err != nil {
		_ = s.send(ctx, relayproto.Frame{Type: relayproto.FrameDatagramOpenErr, StreamID: frame.StreamID, Payload: relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeLocalDialFailed, Message: err.Error()})})
		return
	}

	s.mu.Lock()
	s.datagrams[frame.StreamID] = conn
	s.mu.Unlock()

	if err := s.send(ctx, relayproto.Frame{Type: relayproto.FrameDatagramOpenAck, StreamID: frame.StreamID}); err != nil {
		_ = conn.Close()
		return
	}
	go s.pumpLocalDatagramToRelay(frame.StreamID, conn)
}

func (s *activeSession) pumpLocalToRelay(streamID uint32, conn net.Conn) {
	defer func() {
		s.mu.Lock()
		delete(s.streams, streamID)
		s.mu.Unlock()
		_ = conn.Close()
		_ = s.send(context.Background(), relayproto.Frame{
			Type:     relayproto.FrameStreamClose,
			StreamID: streamID,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeNormalClose}),
		})
	}()

	buf := make([]byte, relayproto.MaxPayloadSize)
	for {
		n, err := conn.Read(buf)
		if n > 0 {
			payload := make([]byte, n)
			copy(payload, buf[:n])
			if sendErr := s.send(context.Background(), relayproto.Frame{Type: relayproto.FrameData, StreamID: streamID, Payload: payload}); sendErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

func (s *activeSession) send(ctx context.Context, frame relayproto.Frame) error {
	buf, err := relayproto.Encode(frame)
	if err != nil {
		return err
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.ws.Write(ctx, websocket.MessageBinary, buf)
}

func (s *activeSession) closeAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, conn := range s.streams {
		_ = conn.Close()
	}
	for _, conn := range s.datagrams {
		_ = conn.Close()
	}
	s.streams = make(map[uint32]net.Conn)
	s.datagrams = make(map[uint32]*net.UDPConn)
}

func (s *activeSession) pumpLocalDatagramToRelay(streamID uint32, conn *net.UDPConn) {
	defer func() {
		s.mu.Lock()
		delete(s.datagrams, streamID)
		s.mu.Unlock()
		_ = conn.Close()
		_ = s.send(context.Background(), relayproto.Frame{
			Type:     relayproto.FrameStreamClose,
			StreamID: streamID,
			Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeNormalClose}),
		})
	}()
	buf := make([]byte, relayproto.MaxPayloadSize)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		n, err := conn.Read(buf)
		if n > 0 {
			payload := make([]byte, n)
			copy(payload, buf[:n])
			if sendErr := s.send(context.Background(), relayproto.Frame{Type: relayproto.FrameData, StreamID: streamID, Payload: payload}); sendErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
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

func writeFrame(ctx context.Context, ws *websocket.Conn, frame relayproto.Frame) error {
	buf, err := relayproto.Encode(frame)
	if err != nil {
		return err
	}
	return ws.Write(ctx, websocket.MessageBinary, buf)
}
