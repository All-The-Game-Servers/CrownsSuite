package envelope

import (
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"testing"
	"time"

	"github.com/xkstudios/atgs/shared/protocol"
)

func makePair(t *testing.T) (*Signer, *Verifier) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	s, err := NewSigner(priv)
	if err != nil {
		t.Fatal(err)
	}
	v, err := NewVerifier(pub, 60*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	return s, v
}

func randNonce(t *testing.T) []byte {
	t.Helper()
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return b
}

// Round trip: sign then verify succeeds.
func TestSignVerifyRoundtrip(t *testing.T) {
	s, v := makePair(t)
	env := &protocol.Envelope{
		Version: 1,
		ID:      "test-id",
		Kind:    protocol.KindTaskDispatch,
		Data:    map[string]any{"foo": "bar"},
	}
	if err := s.Sign(env, randNonce(t)); err != nil {
		t.Fatalf("sign: %v", err)
	}
	if err := v.Verify(env); err != nil {
		t.Fatalf("verify: %v", err)
	}
}

// Replay: the same envelope verified twice in a row should fail the second time.
func TestReplayRejected(t *testing.T) {
	s, v := makePair(t)
	env := &protocol.Envelope{Version: 1, ID: "x", Kind: "k", Data: "payload"}
	if err := s.Sign(env, randNonce(t)); err != nil {
		t.Fatal(err)
	}
	if err := v.Verify(env); err != nil {
		t.Fatalf("first verify: %v", err)
	}
	if err := v.Verify(env); !errors.Is(err, ErrReplay) {
		t.Fatalf("expected ErrReplay, got %v", err)
	}
}

// Wrong key: verifier with a different public key rejects.
func TestBadSignatureRejected(t *testing.T) {
	s, _ := makePair(t)
	_, otherV := makePair(t)
	env := &protocol.Envelope{Version: 1, ID: "x", Kind: "k"}
	if err := s.Sign(env, randNonce(t)); err != nil {
		t.Fatal(err)
	}
	if err := otherV.Verify(env); !errors.Is(err, ErrBadSignature) {
		t.Fatalf("expected ErrBadSignature, got %v", err)
	}
}

// Tampering: changing the payload after signing breaks the digest.
func TestTamperRejected(t *testing.T) {
	s, v := makePair(t)
	env := &protocol.Envelope{Version: 1, ID: "x", Kind: "k", Data: "original"}
	if err := s.Sign(env, randNonce(t)); err != nil {
		t.Fatal(err)
	}
	env.Data = "tampered"
	if err := v.Verify(env); !errors.Is(err, ErrBadSignature) {
		t.Fatalf("expected ErrBadSignature after tamper, got %v", err)
	}
}

// Timestamp outside skew window is rejected.
func TestOldTimestampRejected(t *testing.T) {
	s, v := makePair(t)
	env := &protocol.Envelope{Version: 1, ID: "x", Kind: "k"}
	if err := s.Sign(env, randNonce(t)); err != nil {
		t.Fatal(err)
	}
	// Backdate
	env.Ts = time.Now().Add(-10 * time.Minute).Unix()
	// Re-sign with new ts to make the signature valid
	env.Sig = ""
	digest, err := canonicalDigestNoSig(env)
	if err != nil {
		t.Fatal(err)
	}
	env.Sig = hexEncode(ed25519.Sign(s.priv, digest))
	if err := v.Verify(env); !errors.Is(err, ErrTimestampOutOfRange) {
		t.Fatalf("expected ErrTimestampOutOfRange, got %v", err)
	}
}

// Unsigned envelope returns ErrUnsigned so caller can decide policy.
func TestUnsignedReturnsErrUnsigned(t *testing.T) {
	_, v := makePair(t)
	env := &protocol.Envelope{Version: 1, ID: "x", Kind: "k"}
	if err := v.Verify(env); !errors.Is(err, ErrUnsigned) {
		t.Fatalf("expected ErrUnsigned, got %v", err)
	}
}

// Different nonces are fine even with same payload.
func TestDifferentNoncesFine(t *testing.T) {
	s, v := makePair(t)
	for i := 0; i < 5; i++ {
		env := &protocol.Envelope{Version: 1, ID: "x", Kind: "k", Data: "same"}
		if err := s.Sign(env, randNonce(t)); err != nil {
			t.Fatal(err)
		}
		if err := v.Verify(env); err != nil {
			t.Fatalf("iteration %d: %v", i, err)
		}
	}
}

// helper to keep test file clean; mirrors what Sign does
func hexEncode(b []byte) string {
	const hex = "0123456789abcdef"
	out := make([]byte, len(b)*2)
	for i, c := range b {
		out[i*2] = hex[c>>4]
		out[i*2+1] = hex[c&0x0f]
	}
	return string(out)
}
