package ingress

import (
	"bytes"
	"testing"
)

func TestReadHandshake(t *testing.T) {
	raw := []byte{
		0x16,       // packet length
		0x00,       // packet id
		0x2f,       // protocol version 47
		0x10,       // hostname length 16
		'l', 'o', 'w', 'l', 'i', 'g', 'h', 't', '.', 'm', 'i', 'n', 'e', '.', 'b', 'z',
		0x63, 0xdd, // port 25565
		0x02, // next state login
	}
	info, err := readHandshake(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("readHandshake: %v", err)
	}
	if info.Hostname != "lowlight.mine.bz" {
		t.Fatalf("hostname = %q", info.Hostname)
	}
	if info.Protocol != 47 {
		t.Fatalf("protocol = %d", info.Protocol)
	}
	if !bytes.Equal(info.RawHandshake, raw) {
		t.Fatalf("raw handshake mismatch")
	}
}

func TestStripForwardingSuffix(t *testing.T) {
	in := "lowlight.mine.bz\x00127.0.0.1\x00player"
	if got := stripForwardingSuffix(in); got != "lowlight.mine.bz" {
		t.Fatalf("got %q", got)
	}
}
