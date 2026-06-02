package relayproto

import (
	"bytes"
	"encoding/hex"
	"testing"

	"github.com/google/uuid"
)

// hexBytes parses a space- and newline-tolerant hex string into bytes.
// Panics on error; test-only.
func hexBytes(t *testing.T, s string) []byte {
	t.Helper()
	clean := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case ' ', '\t', '\n', '\r':
			continue
		default:
			clean = append(clean, s[i])
		}
	}
	b, err := hex.DecodeString(string(clean))
	if err != nil {
		t.Fatalf("hex decode: %v", err)
	}
	return b
}

// TestVectorHello is the HELLO frame test vector from docs/relay-protocol.md.
func TestVectorHello(t *testing.T) {
	kid := uuid.MustParse("bbbd01ed-041e-4647-8520-e9beee2ee1e9")
	sid := uuid.MustParse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
	hello := HelloPayload{
		KeeperID:        kid,
		SessionID:       sid,
		ProtocolVersion: 1,
		Reserved:        0,
	}
	frame := Frame{
		Type:     FrameHello,
		StreamID: 0,
		Payload:  EncodeHello(hello),
	}
	got, err := Encode(frame)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}

	want := hexBytes(t, `
		01
		00 00 00 00
		00 00 00 28
		bb bd 01 ed 04 1e 46 47 85 20 e9 be ee 2e e1 e9
		aa aa aa aa bb bb cc cc dd dd ee ee ee ee ee ee
		00 00 00 01
		00 00 00 00
	`)
	if !bytes.Equal(got, want) {
		t.Errorf("HELLO frame mismatch\ngot:  %x\nwant: %x", got, want)
	}

	// Round-trip: decode produces equal payload.
	decoded, err := Decode(got)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if decoded.Type != FrameHello {
		t.Errorf("type: got %s, want HELLO", decoded.Type)
	}
	if decoded.StreamID != 0 {
		t.Errorf("stream_id: got %d, want 0", decoded.StreamID)
	}
	back, err := DecodeHello(decoded.Payload)
	if err != nil {
		t.Fatalf("decode hello: %v", err)
	}
	if back.KeeperID != kid {
		t.Errorf("keeper_id: got %s, want %s", back.KeeperID, kid)
	}
	if back.SessionID != sid {
		t.Errorf("session_id: got %s, want %s", back.SessionID, sid)
	}
	if back.ProtocolVersion != 1 {
		t.Errorf("protocol_version: got %d, want 1", back.ProtocolVersion)
	}
}

func TestRelayHelloRoundTrip(t *testing.T) {
	rid := uuid.MustParse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
	sid := uuid.MustParse("11111111-2222-3333-4444-555555555555")
	in := RelayHelloPayload{
		RelayID:         rid,
		SessionID:       sid,
		ProtocolVersion: 1,
	}
	out, err := DecodeRelayHello(EncodeRelayHello(in))
	if err != nil {
		t.Fatalf("decode relay hello: %v", err)
	}
	if out != in {
		t.Errorf("round-trip mismatch\ngot:  %+v\nwant: %+v", out, in)
	}
}

// TestVectorStreamOpen is the STREAM_OPEN frame test vector from the design
// doc. Note the doc vector was written before the host_port field was added;
// the post-v1.1 payload includes host_port (2B) between instance_id and the
// length-prefixed server_address. We verify the corrected layout.
func TestVectorStreamOpen(t *testing.T) {
	iid := uuid.MustParse("73945d9c-0d21-43fa-a01d-e7951feb0587")
	open := StreamOpenPayload{
		InstanceID:    iid,
		HostPort:      49173,
		ServerAddress: "lowlight.mine.bz",
	}
	payload, err := EncodeStreamOpen(open)
	if err != nil {
		t.Fatalf("encode stream open payload: %v", err)
	}
	frame := Frame{
		Type:     FrameStreamOpen,
		StreamID: 7,
		Payload:  payload,
	}
	got, err := Encode(frame)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}

	// 9-byte header + 16 (uuid) + 2 (port) + 2 (addr_len) + 16 ("lowlight.mine.bz") = 45 bytes total
	want := hexBytes(t, `
		03
		00 00 00 07
		00 00 00 24
		73 94 5d 9c 0d 21 43 fa a0 1d e7 95 1f eb 05 87
		c0 15
		00 10
		6c 6f 77 6c 69 67 68 74 2e 6d 69 6e 65 2e 62 7a
	`)
	if !bytes.Equal(got, want) {
		t.Errorf("STREAM_OPEN frame mismatch\ngot:  %x\nwant: %x", got, want)
	}

	// Round-trip.
	decoded, err := Decode(got)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	back, err := DecodeStreamOpen(decoded.Payload)
	if err != nil {
		t.Fatalf("decode stream open: %v", err)
	}
	if back.InstanceID != iid {
		t.Errorf("instance_id: got %s, want %s", back.InstanceID, iid)
	}
	if back.HostPort != 49173 {
		t.Errorf("host_port: got %d, want 49173", back.HostPort)
	}
	if back.ServerAddress != "lowlight.mine.bz" {
		t.Errorf("server_address: got %q, want %q", back.ServerAddress, "lowlight.mine.bz")
	}
}

// TestVectorData is the DATA frame test vector: 32 arbitrary bytes on stream 7.
func TestVectorData(t *testing.T) {
	payload := make([]byte, 32)
	for i := range payload {
		payload[i] = byte(i)
	}
	frame := Frame{
		Type:     FrameData,
		StreamID: 7,
		Payload:  payload,
	}
	got, err := Encode(frame)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}

	header := hexBytes(t, `06 00 00 00 07 00 00 00 20`)
	want := append(append([]byte{}, header...), payload...)
	if !bytes.Equal(got, want) {
		t.Errorf("DATA frame mismatch\ngot:  %x\nwant: %x", got, want)
	}
}

// TestRoundTripErrorPayload verifies STREAM_CLOSE with a message.
func TestRoundTripErrorPayload(t *testing.T) {
	in := ErrorPayload{Code: ErrCodeLocalDialFailed, Message: "connection refused to 127.0.0.1:49173"}
	encoded := EncodeError(in)
	out, err := DecodeError(encoded)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if out.Code != in.Code {
		t.Errorf("code: got %s, want %s", out.Code, in.Code)
	}
	if out.Message != in.Message {
		t.Errorf("message: got %q, want %q", out.Message, in.Message)
	}
}

// TestPayloadTooLarge ensures we reject oversized frames.
func TestPayloadTooLarge(t *testing.T) {
	_, err := Encode(Frame{
		Type:     FrameData,
		StreamID: 1,
		Payload:  make([]byte, MaxPayloadSize+1),
	})
	if err != ErrPayloadTooLarge {
		t.Errorf("want ErrPayloadTooLarge, got %v", err)
	}
}

// TestShortHeader covers buffers smaller than 9 bytes.
func TestShortHeader(t *testing.T) {
	for _, n := range []int{0, 1, 4, 8} {
		_, err := Decode(make([]byte, n))
		if err != ErrShortHeader {
			t.Errorf("Decode %d bytes: want ErrShortHeader, got %v", n, err)
		}
	}
}

// TestDeclaredLengthMismatch: payload shorter than the length field claims.
func TestDeclaredLengthMismatch(t *testing.T) {
	buf := hexBytes(t, `06 00 00 00 01 00 00 00 08 aa bb cc dd`) // claims 8 bytes, gives 4
	_, err := Decode(buf)
	if err != ErrShortPayload {
		t.Errorf("want ErrShortPayload, got %v", err)
	}
}

// TestTrailingBytesRejected ensures we reject extra bytes after payload.
func TestTrailingBytesRejected(t *testing.T) {
	buf := hexBytes(t, `06 00 00 00 01 00 00 00 02 aa bb cc dd`) // 2 declared, 4 actual
	_, err := Decode(buf)
	if err == nil {
		t.Fatal("expected error on trailing bytes")
	}
}

// TestKeeperAnnouncementRoundTrip ensures the inter-relay gossip payload
// survives encode/decode.
func TestKeeperAnnouncementRoundTrip(t *testing.T) {
	kid := uuid.MustParse("bbbd01ed-041e-4647-8520-e9beee2ee1e9")
	rid := uuid.MustParse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
	in := KeeperAnnouncement{
		KeeperID:      kid,
		RelayID:       rid,
		SinceUnixNano: 1713312000000000000,
	}
	encoded := EncodeKeeperAnnouncement(in)
	out, err := DecodeKeeperAnnouncement(encoded)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if out != in {
		t.Errorf("round-trip mismatch\ngot:  %+v\nwant: %+v", out, in)
	}
}

// TestXRStreamOpenRoundTrip ensures the cross-relay frame survives.
func TestXRStreamOpenRoundTrip(t *testing.T) {
	iid := uuid.MustParse("73945d9c-0d21-43fa-a01d-e7951feb0587")
	in := XRStreamOpenPayload{
		RemoteStreamID: 42,
		InstanceID:     iid,
		HostPort:       49173,
		ServerAddress:  "bravo.mine.bz",
	}
	payload, err := EncodeXRStreamOpen(in)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	out, err := DecodeXRStreamOpen(payload)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if out != in {
		t.Errorf("round-trip mismatch\ngot:  %+v\nwant: %+v", out, in)
	}
}

func TestDatagramOpenRoundTrip(t *testing.T) {
	iid := uuid.MustParse("73945d9c-0d21-43fa-a01d-e7951feb0587")
	in := DatagramOpenPayload{
		InstanceID: iid,
		HostPort:   19132,
		PublicPort: 19140,
	}
	out, err := DecodeDatagramOpen(EncodeDatagramOpen(in))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if out != in {
		t.Errorf("round-trip mismatch\ngot:  %+v\nwant: %+v", out, in)
	}
}

func TestXRDatagramOpenRoundTrip(t *testing.T) {
	iid := uuid.MustParse("73945d9c-0d21-43fa-a01d-e7951feb0587")
	in := XRDatagramOpenPayload{
		RemoteStreamID: 77,
		InstanceID:     iid,
		HostPort:       19132,
		PublicPort:     19140,
	}
	out, err := DecodeXRDatagramOpen(EncodeXRDatagramOpen(in))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if out != in {
		t.Errorf("round-trip mismatch\ngot:  %+v\nwant: %+v", out, in)
	}
}

// TestReadFrameFromStream verifies the streaming reader works identically to
// the one-shot Decode.
func TestReadFrameFromStream(t *testing.T) {
	// Concatenate two frames back to back. ReadFrame should return them in order.
	first, _ := Encode(Frame{Type: FramePing, StreamID: 0, Payload: nil})
	second, _ := Encode(Frame{Type: FrameData, StreamID: 5, Payload: []byte("hello")})
	r := bytes.NewReader(append(first, second...))

	f1, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("first ReadFrame: %v", err)
	}
	if f1.Type != FramePing {
		t.Errorf("first type: got %s, want PING", f1.Type)
	}

	f2, err := ReadFrame(r)
	if err != nil {
		t.Fatalf("second ReadFrame: %v", err)
	}
	if f2.Type != FrameData {
		t.Errorf("second type: got %s, want DATA", f2.Type)
	}
	if string(f2.Payload) != "hello" {
		t.Errorf("second payload: got %q, want %q", f2.Payload, "hello")
	}
}
