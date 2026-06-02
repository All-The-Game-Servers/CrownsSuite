// Package cryptoutil centralizes Central's backup encryption helpers.
//
// Threat model for Phase 4:
//   - Master key lives on Central's filesystem at a configured path. Operator
//     is responsible for protecting that file (mode 0400, root-owned).
//   - Per-backup data keys are randomly generated at backup-create time,
//     wrapped with the master key using AES-256-GCM, and stored in the DB.
//   - Unwrapped data key is sent to the Keeper in the backup.create task
//     payload over the control channel (which is already mTLS).
//   - The Keeper uses the unwrapped key to encrypt each chunk with
//     AES-256-GCM (fresh random 12-byte nonce per chunk, prepended to ciphertext).
//   - At restore time, Central unwraps the data key and sends it to the Keeper
//     again in the backup.restore task.
//
// What this does NOT do (Phase 7 hardening items):
//   - Key rotation
//   - Hardware-backed key storage (KMS, HSM)
//   - Per-tenant keys for multi-tenant deployments
package cryptoutil

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"
)

const (
	// DataKeyBytes is the length of an AES-256 key.
	DataKeyBytes = 32
	// NonceBytes is the GCM nonce length.
	NonceBytes = 12
)

// MasterKey is Central's root-of-trust for wrapping data keys.
type MasterKey struct {
	key []byte // 32 bytes
}

// LoadMasterKey reads the master key from path. The file may be base64url
// (with optional padding stripped), hex, or raw binary. The loader picks the
// right decoder based on content length.
func LoadMasterKey(path string) (*MasterKey, error) {
	if path == "" {
		return nil, errors.New("master key path is empty")
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("stat master key: %w", err)
	}
	// Warn (via returned error) if the file is group/world readable.
	// 0o077 = any permission bits below owner. Skipping on Windows where
	// mode bits are always 0777; this is a Unix-first tool.
	if info.Mode().Perm()&0o077 != 0 {
		return nil, fmt.Errorf("master key file %s is group/world readable (mode %o); chmod 0400", path, info.Mode().Perm())
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	key, err := parseKeyBytes(raw)
	if err != nil {
		return nil, fmt.Errorf("parse master key: %w", err)
	}
	return &MasterKey{key: key}, nil
}

// parseKeyBytes accepts 32 raw bytes, 64 hex chars, or base64url variants.
func parseKeyBytes(raw []byte) ([]byte, error) {
	// Trim trailing whitespace.
	s := strings.TrimRight(string(raw), " \t\n\r")

	// Raw binary: exactly 32 bytes.
	if len(raw) == DataKeyBytes {
		k := make([]byte, DataKeyBytes)
		copy(k, raw)
		return k, nil
	}
	// Hex: 64 chars.
	if len(s) == 2*DataKeyBytes {
		k, err := hex.DecodeString(s)
		if err == nil {
			return k, nil
		}
	}
	// base64url (with or without padding).
	for _, enc := range []*base64.Encoding{
		base64.RawURLEncoding,
		base64.URLEncoding,
		base64.RawStdEncoding,
		base64.StdEncoding,
	} {
		k, err := enc.DecodeString(s)
		if err == nil && len(k) == DataKeyBytes {
			return k, nil
		}
	}
	return nil, fmt.Errorf("key material must be 32 raw bytes, 64 hex chars, or 43-44 chars base64 (got %d bytes / %d chars)", len(raw), len(s))
}

// GenerateDataKey returns a new random 32-byte AES-256 key.
func GenerateDataKey() ([]byte, error) {
	k := make([]byte, DataKeyBytes)
	if _, err := rand.Read(k); err != nil {
		return nil, err
	}
	return k, nil
}

// Wrap encrypts dataKey with the master key. Returns nonce||ciphertext||tag.
func (m *MasterKey) Wrap(dataKey []byte) ([]byte, error) {
	if len(dataKey) != DataKeyBytes {
		return nil, fmt.Errorf("data key must be %d bytes, got %d", DataKeyBytes, len(dataKey))
	}
	block, err := aes.NewCipher(m.key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	out := append([]byte{}, nonce...)
	out = gcm.Seal(out, nonce, dataKey, nil)
	return out, nil
}

// Unwrap decrypts a wrapped data key. Input is nonce||ciphertext||tag.
func (m *MasterKey) Unwrap(wrapped []byte) ([]byte, error) {
	block, err := aes.NewCipher(m.key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(wrapped) < gcm.NonceSize() {
		return nil, errors.New("wrapped key too short")
	}
	nonce := wrapped[:gcm.NonceSize()]
	ct := wrapped[gcm.NonceSize():]
	return gcm.Open(nil, nonce, ct, nil)
}

// KeyFingerprint returns sha256 of the key, truncated to 16 hex chars, as
// "sha256:xxxxxxxx...". Stored in manifests for audit — answers "which key
// is this backup encrypted with?" without leaking the key itself.
func KeyFingerprint(key []byte) string {
	h := sha256.Sum256(key)
	return "sha256:" + hex.EncodeToString(h[:8])
}
