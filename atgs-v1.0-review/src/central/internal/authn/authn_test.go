package authn

import (
	"errors"
	"strings"
	"testing"
)

func TestHashVerifyRoundtrip(t *testing.T) {
	h, err := HashPassword("correct horse battery staple")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$") {
		t.Fatalf("unexpected hash prefix: %s", h)
	}
	if err := VerifyPassword(h, "correct horse battery staple"); err != nil {
		t.Fatalf("verify: %v", err)
	}
}

func TestVerifyWrongPassword(t *testing.T) {
	h, _ := HashPassword("s3cret")
	if err := VerifyPassword(h, "wrong"); !errors.Is(err, ErrPasswordMismatch) {
		t.Fatalf("expected ErrPasswordMismatch, got %v", err)
	}
}

func TestHashPasswordsAreUnique(t *testing.T) {
	// Same password, different hashes (salts must differ).
	h1, _ := HashPassword("pw")
	h2, _ := HashPassword("pw")
	if h1 == h2 {
		t.Fatal("two hashes of same password must not collide (salt must differ)")
	}
	// But both must verify.
	if err := VerifyPassword(h1, "pw"); err != nil {
		t.Fatal(err)
	}
	if err := VerifyPassword(h2, "pw"); err != nil {
		t.Fatal(err)
	}
}

func TestEmptyPasswordRejected(t *testing.T) {
	if _, err := HashPassword(""); err == nil {
		t.Fatal("empty password must error")
	}
}

func TestMalformedHashRejected(t *testing.T) {
	cases := []string{
		"",
		"plaintext",
		"$argon2i$v=19$m=65536,t=2,p=4$abc$def", // wrong variant
		"$argon2id$v=99$m=65536,t=2,p=4$abc$def", // wrong version
		"$argon2id$v=19$xyz$abc$def",              // bad params
	}
	for _, c := range cases {
		if err := VerifyPassword(c, "pw"); err == nil {
			t.Errorf("malformed hash should error: %q", c)
		}
	}
}

func TestSessionTokenUnique(t *testing.T) {
	a, ah, err := NewSessionToken()
	if err != nil {
		t.Fatal(err)
	}
	b, bh, err := NewSessionToken()
	if err != nil {
		t.Fatal(err)
	}
	if a == b || ah == bh {
		t.Fatal("consecutive session tokens must differ")
	}
	// HashSessionToken must be deterministic
	if HashSessionToken(a) != ah {
		t.Fatal("HashSessionToken is not deterministic against NewSessionToken output")
	}
}
