// Package envelope provides Ed25519 signing and verification for the
// control-channel wire protocol. Signing gives Central a cryptographic
// proof that a task came from a specific issuer (typically Central itself,
// or a Progenitor through Central), and gives the Keeper confidence the
// task wasn't forged by something that only controls the TCP endpoint.
//
// Signature scheme:
//   - Ed25519 over a canonical byte representation of the envelope
//   - Canonical form is the envelope with Sig="" serialised as deterministic
//     JSON (sorted keys) then SHA-256 hashed. This avoids canonical-JSON
//     fragility while still being independent of field ordering.
//
// Replay prevention:
//   - Ts is checked against a configurable skew window (default ±60s)
//   - (issuer, nonce) tuples are cached for the skew window + a grace
//     period. A second envelope with the same nonce is rejected.
package envelope

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/xkstudios/atgs/shared/protocol"
)

// DefaultSkew is how far an envelope's Ts can be from the verifier's
// wall clock. Picked to tolerate VM clock drift and brief NTP desync.
const DefaultSkew = 60 * time.Second

// Signer produces signatures for outgoing envelopes.
type Signer struct {
	priv ed25519.PrivateKey
}

// NewSigner wraps an Ed25519 private key.
func NewSigner(priv ed25519.PrivateKey) (*Signer, error) {
	if len(priv) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("invalid ed25519 private key length: %d", len(priv))
	}
	return &Signer{priv: priv}, nil
}

// Sign fills in env.Ts, env.Nonce, env.Sig. It mutates env in place.
// nonce must be 16+ random bytes; callers use crypto/rand to generate it.
func (s *Signer) Sign(env *protocol.Envelope, nonce []byte) error {
	if len(nonce) < 16 {
		return errors.New("nonce must be at least 16 bytes")
	}
	env.Ts = time.Now().Unix()
	env.Nonce = hex.EncodeToString(nonce)
	env.Sig = "" // ensure Sig is not part of canonical bytes

	digest, err := canonicalDigest(env)
	if err != nil {
		return err
	}
	sig := ed25519.Sign(s.priv, digest)
	env.Sig = hex.EncodeToString(sig)
	return nil
}

// Verifier checks incoming envelope signatures and maintains a replay cache.
type Verifier struct {
	pub  ed25519.PublicKey
	skew time.Duration

	mu    sync.Mutex
	seen  map[string]time.Time // nonce -> expiry
	sweep time.Time
}

// NewVerifier wraps a public key.
func NewVerifier(pub ed25519.PublicKey, skew time.Duration) (*Verifier, error) {
	if len(pub) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("invalid ed25519 public key length: %d", len(pub))
	}
	if skew <= 0 {
		skew = DefaultSkew
	}
	return &Verifier{
		pub:   pub,
		skew:  skew,
		seen:  make(map[string]time.Time),
		sweep: time.Now(),
	}, nil
}

// Verify checks the signature, timestamp, and nonce. An envelope with no
// signature fields returns ErrUnsigned. On success, the nonce is recorded
// so a replay within the skew window is rejected.
func (v *Verifier) Verify(env *protocol.Envelope) error {
	if env.Sig == "" && env.Nonce == "" && env.Ts == 0 {
		return ErrUnsigned
	}
	if env.Sig == "" || env.Nonce == "" || env.Ts == 0 {
		return ErrMalformedSignature
	}

	// Timestamp check
	now := time.Now()
	envTime := time.Unix(env.Ts, 0)
	if envTime.Before(now.Add(-v.skew)) {
		return fmt.Errorf("%w: envelope ts %d is %v old (skew=%v)",
			ErrTimestampOutOfRange, env.Ts, now.Sub(envTime), v.skew)
	}
	if envTime.After(now.Add(v.skew)) {
		return fmt.Errorf("%w: envelope ts %d is %v in future (skew=%v)",
			ErrTimestampOutOfRange, env.Ts, envTime.Sub(now), v.skew)
	}

	// Signature check
	sig, err := hex.DecodeString(env.Sig)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrMalformedSignature, err)
	}
	digest, err := canonicalDigestNoSig(env)
	if err != nil {
		return err
	}
	if !ed25519.Verify(v.pub, digest, sig) {
		return ErrBadSignature
	}

	// Nonce check (after signature, to avoid polluting the cache with
	// unsigned junk).
	v.mu.Lock()
	defer v.mu.Unlock()
	v.sweepLocked(now)
	if _, exists := v.seen[env.Nonce]; exists {
		return ErrReplay
	}
	v.seen[env.Nonce] = now.Add(v.skew * 2) // expire after 2x skew
	return nil
}

// sweepLocked purges expired nonces. Called under v.mu. Runs at most once
// per skew/4 to keep the lock window small.
func (v *Verifier) sweepLocked(now time.Time) {
	if now.Sub(v.sweep) < v.skew/4 {
		return
	}
	for nonce, exp := range v.seen {
		if now.After(exp) {
			delete(v.seen, nonce)
		}
	}
	v.sweep = now
}

// --- Canonical form ---
//
// The goal is a stable byte representation that doesn't depend on JSON
// key order. Approach: re-marshal the envelope with a known field order
// (alphabetical by JSON tag), then SHA-256.
//
// We deliberately do NOT use the naked JSON from the wire, because Go's
// encoding/json doesn't guarantee key order across versions.

func canonicalDigest(env *protocol.Envelope) ([]byte, error) {
	return canonicalDigestNoSig(env)
}

func canonicalDigestNoSig(env *protocol.Envelope) ([]byte, error) {
	// Marshal data first so we embed the already-serialised payload bytes
	// (not the Go value's address).
	dataBytes, err := json.Marshal(env.Data)
	if err != nil {
		return nil, fmt.Errorf("marshal data: %w", err)
	}

	// Build a map with ONLY signed fields, then sort keys for determinism.
	// Sig is excluded (circular).
	fields := map[string]any{
		"v":     env.Version,
		"id":    env.ID,
		"kind":  string(env.Kind),
		"data":  json.RawMessage(dataBytes),
		"ts":    env.Ts,
		"nonce": env.Nonce,
	}
	if env.CorrelationID != "" {
		fields["cid"] = env.CorrelationID
	}

	keys := make([]string, 0, len(fields))
	for k := range fields {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	// Emit as a JSON object with sorted keys. Using json.Encoder + Buffer
	// lets us control the format exactly.
	var out []byte
	out = append(out, '{')
	for i, k := range keys {
		if i > 0 {
			out = append(out, ',')
		}
		kb, _ := json.Marshal(k)
		out = append(out, kb...)
		out = append(out, ':')
		vb, err := json.Marshal(fields[k])
		if err != nil {
			return nil, err
		}
		out = append(out, vb...)
	}
	out = append(out, '}')

	h := sha256.Sum256(out)
	return h[:], nil
}

// Errors
var (
	ErrUnsigned            = errors.New("envelope has no signature")
	ErrMalformedSignature  = errors.New("envelope signature is malformed")
	ErrBadSignature        = errors.New("envelope signature is invalid")
	ErrTimestampOutOfRange = errors.New("envelope timestamp out of range")
	ErrReplay              = errors.New("envelope nonce already seen (replay)")
)
