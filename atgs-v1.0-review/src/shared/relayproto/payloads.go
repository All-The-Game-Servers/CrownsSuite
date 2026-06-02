package relayproto

import (
	"encoding/binary"
	"errors"
	"fmt"

	"github.com/google/uuid"
)

// ---- HELLO ----

// HelloPayload is the first frame a Keeper sends on stream 0 after the
// WebSocket handshake completes. Carries keeper identity + protocol version.
type HelloPayload struct {
	KeeperID        uuid.UUID
	SessionID       uuid.UUID
	ProtocolVersion uint32
	Reserved        uint32 // reserved for future use; must be 0
}

const helloPayloadSize = 16 + 16 + 4 + 4

func EncodeHello(p HelloPayload) []byte {
	buf := make([]byte, helloPayloadSize)
	copy(buf[0:16], p.KeeperID[:])
	copy(buf[16:32], p.SessionID[:])
	binary.BigEndian.PutUint32(buf[32:36], p.ProtocolVersion)
	binary.BigEndian.PutUint32(buf[36:40], p.Reserved)
	return buf
}

func DecodeHello(payload []byte) (HelloPayload, error) {
	var p HelloPayload
	if len(payload) != helloPayloadSize {
		return p, fmt.Errorf("relayproto: HELLO payload must be %d bytes, got %d", helloPayloadSize, len(payload))
	}
	copy(p.KeeperID[:], payload[0:16])
	copy(p.SessionID[:], payload[16:32])
	p.ProtocolVersion = binary.BigEndian.Uint32(payload[32:36])
	p.Reserved = binary.BigEndian.Uint32(payload[36:40])
	return p, nil
}

// RelayHelloPayload is the relay-to-relay opening frame on stream 0.
type RelayHelloPayload struct {
	RelayID         uuid.UUID
	SessionID       uuid.UUID
	ProtocolVersion uint32
	Reserved        uint32
}

const relayHelloPayloadSize = 16 + 16 + 4 + 4

func EncodeRelayHello(p RelayHelloPayload) []byte {
	buf := make([]byte, relayHelloPayloadSize)
	copy(buf[0:16], p.RelayID[:])
	copy(buf[16:32], p.SessionID[:])
	binary.BigEndian.PutUint32(buf[32:36], p.ProtocolVersion)
	binary.BigEndian.PutUint32(buf[36:40], p.Reserved)
	return buf
}

func DecodeRelayHello(payload []byte) (RelayHelloPayload, error) {
	var p RelayHelloPayload
	if len(payload) != relayHelloPayloadSize {
		return p, fmt.Errorf("relayproto: RELAY_HELLO payload must be %d bytes, got %d", relayHelloPayloadSize, len(payload))
	}
	copy(p.RelayID[:], payload[0:16])
	copy(p.SessionID[:], payload[16:32])
	p.ProtocolVersion = binary.BigEndian.Uint32(payload[32:36])
	p.Reserved = binary.BigEndian.Uint32(payload[36:40])
	return p, nil
}

// ---- HELLO_ACK ----

// HelloAckPayload is the relay's reply to HELLO. SessionID is opaque to the
// keeper; it's included so support logs can correlate.
type HelloAckPayload struct {
	SessionID uuid.UUID
}

const helloAckPayloadSize = 16

func EncodeHelloAck(p HelloAckPayload) []byte {
	buf := make([]byte, helloAckPayloadSize)
	copy(buf[0:16], p.SessionID[:])
	return buf
}

func DecodeHelloAck(payload []byte) (HelloAckPayload, error) {
	var p HelloAckPayload
	if len(payload) != helloAckPayloadSize {
		return p, fmt.Errorf("relayproto: HELLO_ACK payload must be %d bytes, got %d", helloAckPayloadSize, len(payload))
	}
	copy(p.SessionID[:], payload[0:16])
	return p, nil
}

// ---- STREAM_OPEN ----

// StreamOpenPayload asks the Keeper to open a connection to a container.
//
// Layout:
//   instance_id (16B) + host_port (uint16) + server_addr_len (uint16)
//                    + server_addr (UTF-8 bytes)
//
// server_address is the raw string as sent by the Minecraft client,
// including any Velocity/BungeeCord IP-forwarding suffix. The Keeper does
// NOT parse this; it's passed through to the container in the first DATA
// frame so Velocity/Bungee forwarding keeps working.
type StreamOpenPayload struct {
	InstanceID    uuid.UUID
	HostPort      uint16
	ServerAddress string
}

func EncodeStreamOpen(p StreamOpenPayload) ([]byte, error) {
	addrBytes := []byte(p.ServerAddress)
	if len(addrBytes) > 0xFFFF {
		return nil, errors.New("relayproto: server_address too long for uint16 length prefix")
	}
	buf := make([]byte, 16+2+2+len(addrBytes))
	copy(buf[0:16], p.InstanceID[:])
	binary.BigEndian.PutUint16(buf[16:18], p.HostPort)
	binary.BigEndian.PutUint16(buf[18:20], uint16(len(addrBytes)))
	copy(buf[20:], addrBytes)
	return buf, nil
}

func DecodeStreamOpen(payload []byte) (StreamOpenPayload, error) {
	var p StreamOpenPayload
	if len(payload) < 20 {
		return p, errors.New("relayproto: STREAM_OPEN payload truncated")
	}
	copy(p.InstanceID[:], payload[0:16])
	p.HostPort = binary.BigEndian.Uint16(payload[16:18])
	addrLen := int(binary.BigEndian.Uint16(payload[18:20]))
	if len(payload) != 20+addrLen {
		return p, fmt.Errorf("relayproto: STREAM_OPEN addr length mismatch: declared %d, got %d", addrLen, len(payload)-20)
	}
	p.ServerAddress = string(payload[20:])
	return p, nil
}

// ---- STREAM_OPEN_ERR / STREAM_CLOSE ----

// ErrorPayload is shared by STREAM_OPEN_ERR and STREAM_CLOSE. A
// STREAM_CLOSE with code NormalClose is a clean shutdown.
type ErrorPayload struct {
	Code    ErrorCode
	Message string
}

func EncodeError(p ErrorPayload) []byte {
	buf := make([]byte, 1+len(p.Message))
	buf[0] = byte(p.Code)
	copy(buf[1:], []byte(p.Message))
	return buf
}

func DecodeError(payload []byte) (ErrorPayload, error) {
	var p ErrorPayload
	if len(payload) < 1 {
		return p, errors.New("relayproto: error payload empty")
	}
	p.Code = ErrorCode(payload[0])
	if len(payload) > 1 {
		p.Message = string(payload[1:])
	}
	return p, nil
}

// ---- XR_KEEPER_ONLINE / XR_KEEPER_OFFLINE ----

// KeeperAnnouncement propagates keeper-to-relay affinity between relay
// peers. The timestamp is the announcing relay's monotonic moment-of-event
// in unix nanoseconds; readers break ties by highest timestamp wins.
type KeeperAnnouncement struct {
	KeeperID       uuid.UUID
	RelayID        uuid.UUID // the relay the keeper is now at (ONLINE) or was at (OFFLINE)
	SinceUnixNano  int64
}

const keeperAnnouncementSize = 16 + 16 + 8

func EncodeKeeperAnnouncement(a KeeperAnnouncement) []byte {
	buf := make([]byte, keeperAnnouncementSize)
	copy(buf[0:16], a.KeeperID[:])
	copy(buf[16:32], a.RelayID[:])
	binary.BigEndian.PutUint64(buf[32:40], uint64(a.SinceUnixNano))
	return buf
}

func DecodeKeeperAnnouncement(payload []byte) (KeeperAnnouncement, error) {
	var a KeeperAnnouncement
	if len(payload) != keeperAnnouncementSize {
		return a, fmt.Errorf("relayproto: keeper announcement must be %d bytes, got %d", keeperAnnouncementSize, len(payload))
	}
	copy(a.KeeperID[:], payload[0:16])
	copy(a.RelayID[:], payload[16:32])
	a.SinceUnixNano = int64(binary.BigEndian.Uint64(payload[32:40]))
	return a, nil
}

// ---- XR_STREAM_OPEN (inter-relay cross-routing) ----

// XRStreamOpenPayload is sent from relay A to relay B when a player has
// arrived at A but the target keeper is connected to B. Carries the same
// essential info as StreamOpen plus a relay-local stream id that identifies
// the cross-relay stream for subsequent DATA/CLOSE frames.
type XRStreamOpenPayload struct {
	RemoteStreamID uint32 // relay A's local stream id for this player connection
	InstanceID     uuid.UUID
	HostPort       uint16
	ServerAddress  string
}

func EncodeXRStreamOpen(p XRStreamOpenPayload) ([]byte, error) {
	addrBytes := []byte(p.ServerAddress)
	if len(addrBytes) > 0xFFFF {
		return nil, errors.New("relayproto: server_address too long for uint16 length prefix")
	}
	buf := make([]byte, 4+16+2+2+len(addrBytes))
	binary.BigEndian.PutUint32(buf[0:4], p.RemoteStreamID)
	copy(buf[4:20], p.InstanceID[:])
	binary.BigEndian.PutUint16(buf[20:22], p.HostPort)
	binary.BigEndian.PutUint16(buf[22:24], uint16(len(addrBytes)))
	copy(buf[24:], addrBytes)
	return buf, nil
}

func DecodeXRStreamOpen(payload []byte) (XRStreamOpenPayload, error) {
	var p XRStreamOpenPayload
	if len(payload) < 24 {
		return p, errors.New("relayproto: XR_STREAM_OPEN payload truncated")
	}
	p.RemoteStreamID = binary.BigEndian.Uint32(payload[0:4])
	copy(p.InstanceID[:], payload[4:20])
	p.HostPort = binary.BigEndian.Uint16(payload[20:22])
	addrLen := int(binary.BigEndian.Uint16(payload[22:24]))
	if len(payload) != 24+addrLen {
		return p, fmt.Errorf("relayproto: XR_STREAM_OPEN addr length mismatch: declared %d, got %d", addrLen, len(payload)-24)
	}
	p.ServerAddress = string(payload[24:])
	return p, nil
}

// ---- DATAGRAM_OPEN ----

type DatagramOpenPayload struct {
	InstanceID uuid.UUID
	HostPort   uint16
	PublicPort uint16
}

const datagramOpenPayloadSize = 16 + 2 + 2

func EncodeDatagramOpen(p DatagramOpenPayload) []byte {
	buf := make([]byte, datagramOpenPayloadSize)
	copy(buf[0:16], p.InstanceID[:])
	binary.BigEndian.PutUint16(buf[16:18], p.HostPort)
	binary.BigEndian.PutUint16(buf[18:20], p.PublicPort)
	return buf
}

func DecodeDatagramOpen(payload []byte) (DatagramOpenPayload, error) {
	var p DatagramOpenPayload
	if len(payload) != datagramOpenPayloadSize {
		return p, fmt.Errorf("relayproto: DATAGRAM_OPEN payload must be %d bytes, got %d", datagramOpenPayloadSize, len(payload))
	}
	copy(p.InstanceID[:], payload[0:16])
	p.HostPort = binary.BigEndian.Uint16(payload[16:18])
	p.PublicPort = binary.BigEndian.Uint16(payload[18:20])
	return p, nil
}

type XRDatagramOpenPayload struct {
	RemoteStreamID uint32
	InstanceID     uuid.UUID
	HostPort       uint16
	PublicPort     uint16
}

const xrDatagramOpenPayloadSize = 4 + 16 + 2 + 2

func EncodeXRDatagramOpen(p XRDatagramOpenPayload) []byte {
	buf := make([]byte, xrDatagramOpenPayloadSize)
	binary.BigEndian.PutUint32(buf[0:4], p.RemoteStreamID)
	copy(buf[4:20], p.InstanceID[:])
	binary.BigEndian.PutUint16(buf[20:22], p.HostPort)
	binary.BigEndian.PutUint16(buf[22:24], p.PublicPort)
	return buf
}

func DecodeXRDatagramOpen(payload []byte) (XRDatagramOpenPayload, error) {
	var p XRDatagramOpenPayload
	if len(payload) != xrDatagramOpenPayloadSize {
		return p, fmt.Errorf("relayproto: XR_DATAGRAM_OPEN payload must be %d bytes, got %d", xrDatagramOpenPayloadSize, len(payload))
	}
	p.RemoteStreamID = binary.BigEndian.Uint32(payload[0:4])
	copy(p.InstanceID[:], payload[4:20])
	p.HostPort = binary.BigEndian.Uint16(payload[20:22])
	p.PublicPort = binary.BigEndian.Uint16(payload[22:24])
	return p, nil
}
