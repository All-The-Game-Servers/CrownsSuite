// Package authn provides human-user authentication primitives for ATGS.
//
// Scope:
//   - Password hashing via argon2id (RFC 9106 low-memory profile)
//   - PHC-string serialization so hashes are self-describing
//   - Session token generation (32 random bytes, returned to client as a
//     cookie; server stores sha256 so a leaked table doesn't leak tokens)
//   - Constant-time password verification
//
// Out of scope for this package:
//   - Storage (handled by store.Users / store.UserSessions)
//   - HTTP handling (handled by api.handleLogin / handleLogout / authMiddleware)
package authn

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

// Argon2id parameters. RFC9106 low-memory profile: good security for
// server-side use, acceptable CPU/memory. 64 MiB memory, 2 iterations,
// 4 parallelism threads, 16-byte salt, 32-byte output.
//
// These are deliberate constants, not config-tunable, to keep the hash
// format stable across deployments. If we ever need to rotate, we add a
// new algorithm variant ("$argon2id-v2$") and support both at verify time.
const (
	timeCost    uint32 = 2
	memoryCost  uint32 = 64 * 1024 // 64 MiB
	parallelism uint8  = 4
	saltLen     uint32 = 16
	keyLen      uint32 = 32
)

// HashPassword returns a PHC-format argon2id hash:
//   $argon2id$v=19$m=65536,t=2,p=4$<b64-salt>$<b64-hash>
func HashPassword(password string) (string, error) {
	if password == "" {
		return "", errors.New("password cannot be empty")
	}
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("salt: %w", err)
	}
	hash := argon2.IDKey([]byte(password), salt, timeCost, memoryCost, parallelism, keyLen)
	return fmt.Sprintf(
		"$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version,
		memoryCost, timeCost, parallelism,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	), nil
}

// VerifyPassword runs the PHC-stored hash against the candidate password
// in constant time. Returns nil on match, an error otherwise.
//
// A zero-value or malformed phcHash always errors (treated as auth fail by
// the caller). The caller should not distinguish between "user not found"
// and "password wrong" in its response, to prevent user-enumeration.
func VerifyPassword(phcHash, candidate string) error {
	parts := strings.Split(phcHash, "$")
	// Expected: ["", "argon2id", "v=19", "m=...,t=...,p=...", "salt", "hash"]
	if len(parts) != 6 || parts[1] != "argon2id" {
		return errors.New("unsupported hash format")
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil {
		return fmt.Errorf("parse version: %w", err)
	}
	if version != argon2.Version {
		return fmt.Errorf("argon2 version mismatch: got %d want %d", version, argon2.Version)
	}
	var m, t uint32
	var p uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &m, &t, &p); err != nil {
		return fmt.Errorf("parse params: %w", err)
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return fmt.Errorf("decode salt: %w", err)
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return fmt.Errorf("decode hash: %w", err)
	}
	got := argon2.IDKey([]byte(candidate), salt, t, m, p, uint32(len(want)))
	if subtle.ConstantTimeCompare(got, want) != 1 {
		return ErrPasswordMismatch
	}
	return nil
}

// NewSessionToken returns (token, tokenHash). The caller sends `token` to
// the client as a cookie and stores `tokenHash` in the DB.
func NewSessionToken() (token string, tokenHash string, err error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", "", fmt.Errorf("rand: %w", err)
	}
	token = hex.EncodeToString(raw)
	sum := sha256.Sum256([]byte(token))
	tokenHash = hex.EncodeToString(sum[:])
	return token, tokenHash, nil
}

// HashSessionToken is the deterministic equivalent for lookup paths:
// when the client sends `token`, we compute HashSessionToken(token) and
// query on that.
func HashSessionToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// ErrPasswordMismatch is returned by VerifyPassword when the candidate does
// not match. Callers treat this same as "user not found" to prevent
// enumeration.
var ErrPasswordMismatch = errors.New("password does not match")
