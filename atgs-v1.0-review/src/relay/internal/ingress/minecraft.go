package ingress

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
)

type handshakeInfo struct {
	Hostname     string
	Protocol     int
	RawHandshake []byte
}

func readHandshake(r io.Reader) (*handshakeInfo, error) {
	packetLen, packetLenRaw, err := readVarInt(r)
	if err != nil {
		return nil, err
	}
	if packetLen <= 0 || packetLen > 4096 {
		return nil, fmt.Errorf("invalid handshake packet length %d", packetLen)
	}
	packet := make([]byte, packetLen)
	if _, err := io.ReadFull(r, packet); err != nil {
		return nil, err
	}

	raw := append(packetLenRaw, packet...)
	br := bytes.NewReader(packet)
	packetID, _, err := readVarInt(br)
	if err != nil {
		return nil, err
	}
	if packetID != 0 {
		return nil, fmt.Errorf("expected handshake packet id 0, got %d", packetID)
	}
	protocolVersion, _, err := readVarInt(br)
	if err != nil {
		return nil, err
	}
	host, err := readMCString(br)
	if err != nil {
		return nil, err
	}
	var port uint16
	if err := binary.Read(br, binary.BigEndian, &port); err != nil {
		return nil, err
	}
	_, _, err = readVarInt(br)
	if err != nil {
		return nil, err
	}
	_ = port

	return &handshakeInfo{
		Hostname:     stripForwardingSuffix(host),
		Protocol:     protocolVersion,
		RawHandshake: raw,
	}, nil
}

func stripForwardingSuffix(host string) string {
	for i := 0; i < len(host); i++ {
		if host[i] == 0 {
			return host[:i]
		}
	}
	return host
}

func readMCString(r io.Reader) (string, error) {
	size, _, err := readVarInt(r)
	if err != nil {
		return "", err
	}
	if size < 0 || size > 4096 {
		return "", fmt.Errorf("invalid string length %d", size)
	}
	buf := make([]byte, size)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

func readVarInt(r io.Reader) (int, []byte, error) {
	var num int
	var shift uint
	raw := make([]byte, 0, 5)
	for i := 0; i < 5; i++ {
		var one [1]byte
		if _, err := io.ReadFull(r, one[:]); err != nil {
			return 0, nil, err
		}
		raw = append(raw, one[0])
		num |= int(one[0]&0x7F) << shift
		if one[0]&0x80 == 0 {
			return num, raw, nil
		}
		shift += 7
	}
	return 0, nil, fmt.Errorf("varint too long")
}
