// Package relayproto is the binary wire format for the Keeper data channel
// and the inter-relay peer channel.
//
// Wire format is described in docs/relay-protocol.md. Summary:
//
//	 0       1       2       3       4       5       6       7       8
//	+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
//	|type |   stream_id (uint32 BE)   |   payload_length (uint32 BE)   |
//	+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
//	| payload ...                                                      |
//	+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
//
// 9-byte header. Payload hard-capped at MaxPayloadSize.
//
// Frame types are enumerated in FrameType. Only one parser exists in the
// codebase (this file); both relay and keeper import it.
package relayproto

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// SubprotocolV1 is the WebSocket subprotocol token for this wire version.
// Negotiated in the Sec-WebSocket-Protocol header.
const SubprotocolV1 = "atgs-data-v1"

// Protocol version carried in HELLO payload. Keeper and relay must match.
const ProtocolVersion = 1

// HeaderSize is the fixed byte size of every frame header.
const HeaderSize = 9

// MaxPayloadSize caps individual frame payloads at 64 KiB. Larger application
// messages are split across multiple DATA frames.
const MaxPayloadSize = 64 * 1024

// FrameType identifies what a frame carries. See docs/relay-protocol.md for
// the authoritative table.
type FrameType uint8

const (
	FrameHello          FrameType = 0x01
	FrameHelloAck       FrameType = 0x02
	FrameStreamOpen     FrameType = 0x03
	FrameStreamOpenAck  FrameType = 0x04
	FrameStreamOpenErr  FrameType = 0x05
	FrameData           FrameType = 0x06
	FrameStreamClose    FrameType = 0x07
	FramePing           FrameType = 0x08
	FramePong           FrameType = 0x09

	// Inter-relay frames. Only seen on the relay-to-relay channel.
	FrameXRStreamOpen     FrameType = 0x10
	FrameXRStreamOpenAck  FrameType = 0x11
	FrameXRStreamOpenErr  FrameType = 0x12
	FrameXRKeeperOnline   FrameType = 0x13
	FrameXRKeeperOffline  FrameType = 0x14
	FrameDatagramOpen     FrameType = 0x15
	FrameDatagramOpenAck  FrameType = 0x16
	FrameDatagramOpenErr  FrameType = 0x17
	FrameXRDatagramOpen   FrameType = 0x18
	FrameXRDatagramOpenAck FrameType = 0x19
	FrameXRDatagramOpenErr FrameType = 0x1A
)

// String returns a human-readable frame type name for logs and error messages.
func (t FrameType) String() string {
	switch t {
	case FrameHello:
		return "HELLO"
	case FrameHelloAck:
		return "HELLO_ACK"
	case FrameStreamOpen:
		return "STREAM_OPEN"
	case FrameStreamOpenAck:
		return "STREAM_OPEN_ACK"
	case FrameStreamOpenErr:
		return "STREAM_OPEN_ERR"
	case FrameData:
		return "DATA"
	case FrameStreamClose:
		return "STREAM_CLOSE"
	case FramePing:
		return "PING"
	case FramePong:
		return "PONG"
	case FrameXRStreamOpen:
		return "XR_STREAM_OPEN"
	case FrameXRStreamOpenAck:
		return "XR_STREAM_OPEN_ACK"
	case FrameXRStreamOpenErr:
		return "XR_STREAM_OPEN_ERR"
	case FrameXRKeeperOnline:
		return "XR_KEEPER_ONLINE"
	case FrameXRKeeperOffline:
		return "XR_KEEPER_OFFLINE"
	case FrameDatagramOpen:
		return "DATAGRAM_OPEN"
	case FrameDatagramOpenAck:
		return "DATAGRAM_OPEN_ACK"
	case FrameDatagramOpenErr:
		return "DATAGRAM_OPEN_ERR"
	case FrameXRDatagramOpen:
		return "XR_DATAGRAM_OPEN"
	case FrameXRDatagramOpenAck:
		return "XR_DATAGRAM_OPEN_ACK"
	case FrameXRDatagramOpenErr:
		return "XR_DATAGRAM_OPEN_ERR"
	default:
		return fmt.Sprintf("UNKNOWN(0x%02x)", uint8(t))
	}
}

// ErrorCode is the single-byte error field used in STREAM_OPEN_ERR and
// STREAM_CLOSE payloads.
type ErrorCode uint8

const (
	ErrCodeNormalClose       ErrorCode = 0x00
	ErrCodeLocalDialFailed   ErrorCode = 0x01
	ErrCodeInstanceNotFound  ErrorCode = 0x02
	ErrCodeContainerNotRunning ErrorCode = 0x03
	ErrCodeProtocolError     ErrorCode = 0x04
	ErrCodeTimeout           ErrorCode = 0x05
	ErrCodeRelayDisconnect   ErrorCode = 0x06
	ErrCodeKeeperDisconnect  ErrorCode = 0x07
	ErrCodeKeeperNotHere     ErrorCode = 0x08 // for XR_STREAM_OPEN_ERR
)

func (c ErrorCode) String() string {
	switch c {
	case ErrCodeNormalClose:
		return "normal_close"
	case ErrCodeLocalDialFailed:
		return "local_dial_failed"
	case ErrCodeInstanceNotFound:
		return "instance_not_found"
	case ErrCodeContainerNotRunning:
		return "container_not_running"
	case ErrCodeProtocolError:
		return "protocol_error"
	case ErrCodeTimeout:
		return "timeout"
	case ErrCodeRelayDisconnect:
		return "relay_disconnect"
	case ErrCodeKeeperDisconnect:
		return "keeper_disconnect"
	case ErrCodeKeeperNotHere:
		return "keeper_not_here"
	default:
		return fmt.Sprintf("UNKNOWN(0x%02x)", uint8(c))
	}
}

// Frame is a decoded wire frame. Payload is the raw bytes as received; the
// caller is responsible for further decoding based on Type.
type Frame struct {
	Type     FrameType
	StreamID uint32
	Payload  []byte
}

// Common errors returned from Encode/Decode.
var (
	ErrPayloadTooLarge = errors.New("relayproto: payload exceeds MaxPayloadSize")
	ErrShortHeader     = errors.New("relayproto: short header")
	ErrShortPayload    = errors.New("relayproto: payload shorter than declared length")
)

// Encode serializes f into dst. The caller must ensure dst has at least
// HeaderSize + len(f.Payload) capacity; otherwise a new slice is allocated.
// Returns the (possibly grown) slice.
//
// This function does not reuse buffers. Callers in hot paths should use
// EncodeInto with a pooled buffer instead.
func Encode(f Frame) ([]byte, error) {
	if len(f.Payload) > MaxPayloadSize {
		return nil, ErrPayloadTooLarge
	}
	buf := make([]byte, HeaderSize+len(f.Payload))
	buf[0] = byte(f.Type)
	binary.BigEndian.PutUint32(buf[1:5], f.StreamID)
	binary.BigEndian.PutUint32(buf[5:9], uint32(len(f.Payload)))
	copy(buf[HeaderSize:], f.Payload)
	return buf, nil
}

// EncodeInto writes f into dst. Returns the slice of dst actually used. If
// dst is too small a new slice is allocated and returned instead.
func EncodeInto(dst []byte, f Frame) ([]byte, error) {
	if len(f.Payload) > MaxPayloadSize {
		return nil, ErrPayloadTooLarge
	}
	total := HeaderSize + len(f.Payload)
	if cap(dst) < total {
		dst = make([]byte, total)
	} else {
		dst = dst[:total]
	}
	dst[0] = byte(f.Type)
	binary.BigEndian.PutUint32(dst[1:5], f.StreamID)
	binary.BigEndian.PutUint32(dst[5:9], uint32(len(f.Payload)))
	copy(dst[HeaderSize:], f.Payload)
	return dst, nil
}

// Decode parses one frame from a complete message buffer. Returns the decoded
// frame. Does not retain a reference to buf; callers may reuse it immediately.
//
// Trailing bytes beyond the declared payload length are an error, not silently
// ignored. WebSocket messages are already length-framed by the transport, so
// each WebSocket binary message should contain exactly one relayproto frame.
func Decode(buf []byte) (Frame, error) {
	var f Frame
	if len(buf) < HeaderSize {
		return f, ErrShortHeader
	}
	f.Type = FrameType(buf[0])
	f.StreamID = binary.BigEndian.Uint32(buf[1:5])
	payloadLen := binary.BigEndian.Uint32(buf[5:9])
	if payloadLen > MaxPayloadSize {
		return f, ErrPayloadTooLarge
	}
	if len(buf)-HeaderSize < int(payloadLen) {
		return f, ErrShortPayload
	}
	if len(buf)-HeaderSize > int(payloadLen) {
		return f, fmt.Errorf("relayproto: trailing bytes after payload: %d extra", len(buf)-HeaderSize-int(payloadLen))
	}
	if payloadLen > 0 {
		// Copy so the returned frame doesn't alias caller's buffer.
		f.Payload = make([]byte, payloadLen)
		copy(f.Payload, buf[HeaderSize:HeaderSize+payloadLen])
	}
	return f, nil
}

// ReadFrame reads one frame from r. Useful when bytes are being streamed
// from a non-WebSocket source (e.g., inter-relay RPC over raw TCP in tests).
// For WebSocket binary messages, use Decode on the full message buffer.
func ReadFrame(r io.Reader) (Frame, error) {
	var f Frame
	hdr := make([]byte, HeaderSize)
	if _, err := io.ReadFull(r, hdr); err != nil {
		return f, err
	}
	f.Type = FrameType(hdr[0])
	f.StreamID = binary.BigEndian.Uint32(hdr[1:5])
	payloadLen := binary.BigEndian.Uint32(hdr[5:9])
	if payloadLen > MaxPayloadSize {
		return f, ErrPayloadTooLarge
	}
	if payloadLen > 0 {
		f.Payload = make([]byte, payloadLen)
		if _, err := io.ReadFull(r, f.Payload); err != nil {
			return f, err
		}
	}
	return f, nil
}
