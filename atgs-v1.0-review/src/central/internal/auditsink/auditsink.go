// Package auditsink provides the streaming outputs for ATGS audit events.
//
// Phase 7 ships two sink flavors:
//   - FileSink: append-only newline-delimited JSON to a file path.
//   - SyslogSink: writes to the local syslog daemon (Unix only).
//
// Both are safe for concurrent use. Neither is expected to block the caller;
// file I/O errors are logged but not returned so audit writes don't fail
// because the sink is struggling.
package auditsink

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"time"

	"github.com/xkstudios/atgs/central/internal/store"
)

// FileSink appends JSON lines to a file. Opens the file once, reopens on
// rename (so logrotate works) via a size check every 100 writes.
type FileSink struct {
	path string
	log  *slog.Logger

	mu    sync.Mutex
	f     *os.File
	wrote int
}

func NewFileSink(path string, log *slog.Logger) (*FileSink, error) {
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, fmt.Errorf("open audit sink %s: %w", path, err)
	}
	return &FileSink{path: path, log: log, f: f}, nil
}

type line struct {
	At       time.Time      `json:"at"`
	Kind     string         `json:"kind"`
	Actor    string         `json:"actor"`
	KeeperID string         `json:"keeper_id,omitempty"`
	Details  map[string]any `json:"details,omitempty"`
}

func (s *FileSink) Write(e store.AuditEntry) {
	l := line{
		At:      time.Now().UTC(),
		Kind:    e.Kind,
		Actor:   e.Actor,
		Details: e.Details,
	}
	if e.KeeperID != nil {
		l.KeeperID = e.KeeperID.String()
	}
	buf, err := json.Marshal(l)
	if err != nil {
		s.log.Warn("audit sink marshal", "err", err)
		return
	}
	buf = append(buf, '\n')

	s.mu.Lock()
	defer s.mu.Unlock()
	// Rotate check: if the file was moved out from under us, reopen.
	s.wrote++
	if s.wrote%100 == 0 {
		if info, err := os.Stat(s.path); err != nil || info == nil {
			_ = s.f.Close()
			if nf, err := os.OpenFile(s.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
				s.f = nf
			}
		}
	}
	if _, err := s.f.Write(buf); err != nil {
		s.log.Warn("audit sink write", "err", err)
	}
}

// Close releases the file handle.
func (s *FileSink) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.f != nil {
		return s.f.Close()
	}
	return nil
}
