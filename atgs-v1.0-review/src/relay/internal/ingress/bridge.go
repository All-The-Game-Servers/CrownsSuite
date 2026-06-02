package ingress

import (
	"context"
	"errors"
	"net"
	"sync"

	"github.com/xkstudios/atgs/shared/relayproto"
)

type outboundBridge struct {
	conn     net.Conn
	streamID uint32
	send     func(context.Context, relayproto.Frame) error
	remove   func(uint32)

	openOnce sync.Once
	openCh   chan error

	closeOnce sync.Once
}

func newOutboundBridge(conn net.Conn, streamID uint32, send func(context.Context, relayproto.Frame) error, remove func(uint32)) *outboundBridge {
	return &outboundBridge{
		conn:     conn,
		streamID: streamID,
		send:     send,
		remove:   remove,
		openCh:   make(chan error, 1),
	}
}

func (b *outboundBridge) HandleFrame(ctx context.Context, frame relayproto.Frame) {
	switch frame.Type {
	case relayproto.FrameStreamOpenAck:
		b.signalOpen(nil)
	case relayproto.FrameXRStreamOpenAck:
		b.signalOpen(nil)
	case relayproto.FrameStreamOpenErr:
		errPayload, _ := relayproto.DecodeError(frame.Payload)
		b.signalOpen(errors.New(errPayload.Message))
		b.close()
	case relayproto.FrameXRStreamOpenErr:
		errPayload, _ := relayproto.DecodeError(frame.Payload)
		b.signalOpen(errors.New(errPayload.Message))
		b.close()
	case relayproto.FrameData:
		if len(frame.Payload) > 0 {
			_, _ = b.conn.Write(frame.Payload)
		}
	case relayproto.FrameStreamClose:
		b.close()
	}
}

func (b *outboundBridge) HandleDisconnect(err error) {
	if err == nil {
		err = registryErr
	}
	b.signalOpen(err)
	b.close()
}

func (b *outboundBridge) WaitOpen(ctx context.Context) error {
	select {
	case err := <-b.openCh:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (b *outboundBridge) Start(initial []byte) {
	go b.readLoop()
	if len(initial) > 0 {
		_ = b.send(context.Background(), relayproto.Frame{
			Type:     relayproto.FrameData,
			StreamID: b.streamID,
			Payload:  initial,
		})
	}
}

func (b *outboundBridge) readLoop() {
	defer b.close()
	buf := make([]byte, relayproto.MaxPayloadSize)
	for {
		n, err := b.conn.Read(buf)
		if n > 0 {
			payload := make([]byte, n)
			copy(payload, buf[:n])
			if sendErr := b.send(context.Background(), relayproto.Frame{
				Type:     relayproto.FrameData,
				StreamID: b.streamID,
				Payload:  payload,
			}); sendErr != nil {
				return
			}
		}
		if err != nil {
			_ = b.send(context.Background(), relayproto.Frame{
				Type:     relayproto.FrameStreamClose,
				StreamID: b.streamID,
				Payload:  relayproto.EncodeError(relayproto.ErrorPayload{Code: relayproto.ErrCodeNormalClose}),
			})
			return
		}
	}
}

func (b *outboundBridge) signalOpen(err error) {
	b.openOnce.Do(func() {
		b.openCh <- err
	})
}

func (b *outboundBridge) close() {
	b.closeOnce.Do(func() {
		if b.remove != nil {
			b.remove(b.streamID)
		}
		_ = b.conn.Close()
	})
}

var registryErr = errors.New("relay session disconnected")
