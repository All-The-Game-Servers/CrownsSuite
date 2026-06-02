// fake-minecraft-server is a tiny echo server the Phase 3 e2e uses in place
// of a real Minecraft Java server. It binds on the port the fake runtime
// claims to have assigned, accepts one TCP connection, reads the first 32
// bytes, then echoes a fixed "pong" response.
package main

import (
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: fake-minecraft-server <port>")
		os.Exit(2)
	}
	addr := "127.0.0.1:" + os.Args[1]
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "listen:", err)
		os.Exit(1)
	}
	defer ln.Close()
	fmt.Fprintln(os.Stderr, "fake minecraft server listening on", addr)

	_ = ln.(*net.TCPListener).SetDeadline(time.Now().Add(30 * time.Second))
	conn, err := ln.Accept()
	if err != nil {
		fmt.Fprintln(os.Stderr, "accept:", err)
		os.Exit(1)
	}
	defer conn.Close()

	buf := make([]byte, 256)
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	n, err := conn.Read(buf)
	if err != nil && err != io.EOF {
		fmt.Fprintln(os.Stderr, "read:", err)
	}
	fmt.Fprintf(os.Stderr, "got %d bytes: %x\n", n, buf[:n])
	// Signal success by writing a known marker.
	_, _ = conn.Write([]byte("ATGS_PHASE3_OK\n"))
	fmt.Fprintln(os.Stderr, "wrote marker, closing")
	time.Sleep(200 * time.Millisecond)
}
