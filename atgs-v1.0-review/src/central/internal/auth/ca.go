// Package auth handles Central's signing CA and mTLS verification.
//
// The CA is a pair of PEM files on disk: ca.crt and ca.key. In production,
// the private key belongs in a secrets manager (KMS, HashiCorp Vault, SOPS,
// etc). For Phase 1 we read it from a file with 0600 perms. The bootstrap-ca
// command creates this pair; the operator must protect it thereafter.
package auth

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/xkstudios/atgs/shared/pki"
)

const (
	caCertFile = "ca.crt"
	caKeyFile  = "ca.key"
)

type CA struct {
	dir     string
	certPEM []byte
	keyPEM  []byte
}

// LoadCA reads an existing CA from disk.
func LoadCA(dir string) (*CA, error) {
	certPEM, err := os.ReadFile(filepath.Join(dir, caCertFile))
	if err != nil {
		return nil, fmt.Errorf("read ca cert: %w", err)
	}
	keyPEM, err := os.ReadFile(filepath.Join(dir, caKeyFile))
	if err != nil {
		return nil, fmt.Errorf("read ca key: %w", err)
	}
	return &CA{dir: dir, certPEM: certPEM, keyPEM: keyPEM}, nil
}

// Bootstrap creates a new CA in the given directory. Refuses to overwrite.
func Bootstrap(dir string) (*CA, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("mkdir ca dir: %w", err)
	}
	if _, err := os.Stat(filepath.Join(dir, caCertFile)); err == nil {
		return nil, errors.New("ca already exists; refusing to overwrite")
	}

	certPEM, keyPEM, err := pki.CreateCA("ATGS Central Internal CA")
	if err != nil {
		return nil, err
	}

	if err := os.WriteFile(filepath.Join(dir, caCertFile), certPEM, 0o644); err != nil {
		return nil, fmt.Errorf("write ca cert: %w", err)
	}
	if err := os.WriteFile(filepath.Join(dir, caKeyFile), keyPEM, 0o600); err != nil {
		return nil, fmt.Errorf("write ca key: %w", err)
	}

	return &CA{dir: dir, certPEM: certPEM, keyPEM: keyPEM}, nil
}

func (c *CA) CertPEM() []byte { return c.certPEM }

// ReadKey returns the CA's private key PEM. Exposed for the api package's
// dev server cert issuance. Never log or export this value.
func (c *CA) ReadKey() ([]byte, error) {
	if len(c.keyPEM) == 0 {
		return nil, errors.New("ca key not loaded")
	}
	return c.keyPEM, nil
}

// SignKeeper signs a Keeper CSR with this CA. keeperID becomes the cert's
// Common Name; the Keeper's CSR-claimed CN is ignored.
func (c *CA) SignKeeper(csrPEM []byte, keeperID string) ([]byte, error) {
	return pki.SignCSR(c.certPEM, c.keyPEM, csrPEM, keeperID)
}
