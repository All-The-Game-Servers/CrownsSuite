// fake-minecraft-client sends a Minecraft Java handshake targeting a hostname
// and reads up to 64 bytes of response. Exits 0 if it reads the ATGS_PHASE3_OK
// marker echoed by fake-minecraft-server.
package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"strings"
	"time"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: fake-minecraft-client <addr> <hostname>")
		os.Exit(2)
	}
	addr := os.Args[1]
	hostname := os.Args[2]

	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		fmt.Fprintln(os.Stderr, "dial:", err)
		os.Exit(1)
	}
	defer conn.Close()

	// Build handshake packet:
	//   packet_id (0x00 varint) + protocol_version (varint) + hostname (varint-prefixed string) + port (u16) + next_state (varint)
	body := []byte{}
	body = append(body, 0x00) // packet_id = 0 (Handshake)
	body = appendVarInt(body, 763) // protocol version (1.20.1 example)
	body = appendVarInt(body, len(hostname))
	body = append(body, []byte(hostname)...)
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], 25565)
	body = append(body, portBuf[:]...)
	body = appendVarInt(body, 2) // next_state = login

	// Prefix with packet length (varint).
	packet := append(appendVarInt(nil, len(body)), body...)

	_ = conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write(packet); err != nil {
		fmt.Fprintln(os.Stderr, "write:", err)
		os.Exit(1)
	}
	fmt.Fprintf(os.Stderr, "sent %d-byte handshake for %q\n", len(packet), hostname)

	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 128)
	n, err := conn.Read(buf)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
		os.Exit(1)
	}
	resp := string(buf[:n])
	fmt.Fprintf(os.Stderr, "got %d bytes: %q\n", n, resp)
	if strings.Contains(resp, "ATGS_PHASE3_OK") {
		fmt.Println("OK")
		return
	}
	fmt.Fprintln(os.Stderr, "marker not found in response")
	os.Exit(1)
}

func appendVarInt(dst []byte, v int) []byte {
	u := uint32(v)
	for {
		b := byte(u & 0x7F)
		u >>= 7
		if u != 0 {
			b |= 0x80
		}
		dst = append(dst, b)
		if u == 0 {
			break
		}
	}
	return dst
}
