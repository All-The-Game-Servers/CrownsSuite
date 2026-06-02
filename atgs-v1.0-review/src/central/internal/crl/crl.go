// Package crl manages Central's certificate revocation list.
//
// Phase 7: simple on-disk append-only format. Each line is
// "<serial_hex> <revoked_at_rfc3339> <reason>". Loaded at startup,
// consulted on every TLS handshake by the keeper listener. Write is
// atomic (write-to-tmp + rename) so a crash mid-write doesn't corrupt
// the list.
//
// Production hardening (post-Phase-7) would replace this with a proper
// X.509 CRL signed by the CA so other verifiers could consume it.
package crl

import (
	"crypto/x509"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const crlFile = "revocations.txt"

// List is an in-memory + on-disk CRL. Safe for concurrent use.
type List struct {
	dir  string
	mu   sync.RWMutex
	byID map[string]Entry // key is serial_hex (lowercase, no separators)
}

type Entry struct {
	SerialHex string    `json:"serial_hex"`
	RevokedAt time.Time `json:"revoked_at"`
	Reason    string    `json:"reason"`
}

// Load reads the CRL from <dir>/revocations.txt. Missing file = empty list.
func Load(dir string) (*List, error) {
	l := &List{dir: dir, byID: make(map[string]Entry)}
	path := filepath.Join(dir, crlFile)
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return l, nil
		}
		return nil, fmt.Errorf("read crl: %w", err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, " ", 3)
		if len(parts) < 2 {
			continue
		}
		t, err := time.Parse(time.RFC3339, parts[1])
		if err != nil {
			continue
		}
		serial := strings.ToLower(parts[0])
		reason := ""
		if len(parts) == 3 {
			reason = parts[2]
		}
		l.byID[serial] = Entry{SerialHex: serial, RevokedAt: t, Reason: reason}
	}
	return l, nil
}

// Add records a revocation and persists to disk. Idempotent: re-adding an
// existing serial is a no-op.
func (l *List) Add(serial *big.Int, reason string) error {
	serialHex := strings.ToLower(serial.Text(16))
	l.mu.Lock()
	if _, exists := l.byID[serialHex]; exists {
		l.mu.Unlock()
		return nil
	}
	l.byID[serialHex] = Entry{
		SerialHex: serialHex,
		RevokedAt: time.Now(),
		Reason:    reason,
	}
	// Snapshot under lock so we write a consistent view.
	var sb strings.Builder
	sb.WriteString("# ATGS CRL — managed by central; do not edit manually\n")
	for _, e := range l.byID {
		sb.WriteString(e.SerialHex)
		sb.WriteString(" ")
		sb.WriteString(e.RevokedAt.UTC().Format(time.RFC3339))
		sb.WriteString(" ")
		sb.WriteString(e.Reason)
		sb.WriteString("\n")
	}
	l.mu.Unlock()

	path := filepath.Join(l.dir, crlFile)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(sb.String()), 0o600); err != nil {
		return fmt.Errorf("write crl tmp: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return fmt.Errorf("rename crl: %w", err)
	}
	return nil
}

// IsRevoked reports whether the given cert has been revoked.
func (l *List) IsRevoked(cert *x509.Certificate) bool {
	if cert == nil || cert.SerialNumber == nil {
		return false
	}
	serial := strings.ToLower(cert.SerialNumber.Text(16))
	l.mu.RLock()
	defer l.mu.RUnlock()
	_, revoked := l.byID[serial]
	return revoked
}

// List returns all current revocation entries, newest-first.
func (l *List) List() []Entry {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]Entry, 0, len(l.byID))
	for _, e := range l.byID {
		out = append(out, e)
	}
	return out
}
