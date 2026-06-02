// Package signingkey owns Central's Ed25519 keypair for control-channel
// envelope signing. Distinct from the CA because:
//
//   - The CA signs X.509 certs; this signs JSON envelopes
//   - Different cryptographic primitives (ECDSA/RSA cert chain vs Ed25519
//     raw signatures)
//   - Rotation cadence differs: the CA is meant to be very long-lived; the
//     signing key is easier to rotate because the only consumers are Keepers
//     who get the current public key at enrollment
//
// On-disk layout (same dir as CA):
//   <dir>/central_ed25519.key       raw 64-byte private key, 0400
//   <dir>/central_ed25519.pub       raw 32-byte public key,  0644
package signingkey

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

const (
	privFile = "central_ed25519.key"
	pubFile  = "central_ed25519.pub"
)

// Key holds a loaded or freshly-generated keypair.
type Key struct {
	Public  ed25519.PublicKey
	Private ed25519.PrivateKey
}

// Bootstrap generates and persists a fresh key. Refuses to overwrite.
// Returns the key so the bootstrap-ca command can print the public key.
func Bootstrap(dir string) (*Key, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("mkdir signing key dir: %w", err)
	}
	if _, err := os.Stat(filepath.Join(dir, privFile)); err == nil {
		return nil, errors.New("central_ed25519.key already exists; refusing to overwrite")
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate ed25519: %w", err)
	}
	if err := os.WriteFile(filepath.Join(dir, privFile), priv, 0o400); err != nil {
		return nil, fmt.Errorf("write private key: %w", err)
	}
	if err := os.WriteFile(filepath.Join(dir, pubFile), pub, 0o644); err != nil {
		return nil, fmt.Errorf("write public key: %w", err)
	}
	return &Key{Public: pub, Private: priv}, nil
}

// Load reads the keypair from disk. Returns (nil, nil) if no key is
// present — callers that want a hard failure can check for nil.
func Load(dir string) (*Key, error) {
	priv, err := os.ReadFile(filepath.Join(dir, privFile))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("read private key: %w", err)
	}
	if len(priv) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("private key size %d, want %d", len(priv), ed25519.PrivateKeySize)
	}
	pub, err := os.ReadFile(filepath.Join(dir, pubFile))
	if err != nil {
		return nil, fmt.Errorf("read public key: %w", err)
	}
	if len(pub) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("public key size %d, want %d", len(pub), ed25519.PublicKeySize)
	}
	return &Key{Public: pub, Private: priv}, nil
}

// PublicHex returns the public key as hex for inclusion in enrollment
// responses and other JSON-serialised places.
func (k *Key) PublicHex() string {
	return hex.EncodeToString(k.Public)
}
