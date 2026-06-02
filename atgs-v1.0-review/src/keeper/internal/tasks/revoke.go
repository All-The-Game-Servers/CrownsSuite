package tasks

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/xkstudios/atgs/shared/protocol"
)

// doKeeperRevoke handles an inbound keeper.revoke task. The Keeper:
//
//  1. Logs the reason with high visibility
//  2. Wipes identity files so a restart cannot reconnect with the same cert
//  3. Returns a success result to Central (so it's recorded in the audit log)
//  4. Triggers a goroutine to exit the process after a short grace period
//
// The grace period lets the TaskResult envelope finish its round trip before
// we disappear. Central's CRL entry persists regardless, so even a corrupted
// exit with identity files intact will fail TLS on next reconnect.
func (h *Handler) doKeeperRevoke(ctx context.Context, rawPayload json.RawMessage) (any, error) {
	var p protocol.KeeperRevokePayload
	if err := json.Unmarshal(rawPayload, &p); err != nil {
		return nil, fmt.Errorf("decode revoke payload: %w", err)
	}

	h.Log.Warn("KEEPER REVOKED — wiping identity and exiting",
		"reason", p.Reason, "actor", p.Actor)

	// Best-effort wipe of identity files. Errors are logged but not returned;
	// Central's CRL is the durable backstop.
	wipeFiles := []string{
		"keeper.id", "client.crt", "client.key", "ca.crt",
		"central.endpoint", "ed25519.key", "ed25519.pub",
		"central_ed25519.pub",
	}
	stateDir := filepath.Dir(h.DataRoot) // DataRoot is <stateDir>/instances; parent is stateDir
	if filepath.Base(h.DataRoot) != "instances" {
		// Fallback for non-default layouts. Scan current directory.
		stateDir = "."
	}
	for _, f := range wipeFiles {
		path := filepath.Join(stateDir, f)
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			h.Log.Warn("failed to wipe identity file", "path", path, "err", err)
		}
	}

	// Delayed exit: let the result envelope flush first.
	go func() {
		time.Sleep(3 * time.Second)
		h.Log.Warn("exiting after revoke grace period")
		os.Exit(0)
	}()

	return map[string]any{
		"revoked":  true,
		"reason":   p.Reason,
		"wiped_at": time.Now().UTC().Format(time.RFC3339),
	}, nil
}
