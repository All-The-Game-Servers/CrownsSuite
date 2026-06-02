// Package identity loads the relay's mTLS client certificate and its trust
// anchor (Central's CA cert).
//
// Unlike the Keeper, the relay does NOT self-enroll. Identity files are
// provisioned out of band by an operator using `central mint-relay-cert`.
// This package only loads; it never writes.
package identity

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"
)

const (
	fileRelayID = "relay.id"
	fileCert    = "client.crt"
	fileKey     = "client.key"
	fileCA      = "ca.crt"
)

// Identity is the full set of credentials a relay needs to act on the wire.
type Identity struct {
	RelayID      uuid.UUID
	Certificate  tls.Certificate
	CACertPool   *x509.CertPool
	CAPEM        []byte
	CertNotAfter time.Time
}

// Load reads the identity from stateDir. Returns ErrNoIdentity if the
// expected files are missing (clearer than a wrapped os.ErrNotExist).
func Load(stateDir string) (*Identity, error) {
	idPath := filepath.Join(stateDir, fileRelayID)
	idBytes, err := os.ReadFile(idPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrNoIdentity
		}
		return nil, fmt.Errorf("read %s: %w", fileRelayID, err)
	}
	relayID, err := uuid.Parse(stringTrim(idBytes))
	if err != nil {
		return nil, fmt.Errorf("parse relay_id: %w", err)
	}

	certPEM, err := os.ReadFile(filepath.Join(stateDir, fileCert))
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", fileCert, err)
	}
	keyPEM, err := os.ReadFile(filepath.Join(stateDir, fileKey))
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", fileKey, err)
	}
	caPEM, err := os.ReadFile(filepath.Join(stateDir, fileCA))
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", fileCA, err)
	}

	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, fmt.Errorf("load keypair: %w", err)
	}

	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("ca.crt contains no certificates")
	}

	var notAfter time.Time
	if block, _ := pem.Decode(certPEM); block != nil {
		if leaf, err := x509.ParseCertificate(block.Bytes); err == nil {
			notAfter = leaf.NotAfter
		}
	}

	return &Identity{
		RelayID:      relayID,
		Certificate:  cert,
		CACertPool:   pool,
		CAPEM:        caPEM,
		CertNotAfter: notAfter,
	}, nil
}

// ErrNoIdentity indicates the relay has not been provisioned yet.
var ErrNoIdentity = errors.New("no relay identity on disk (run `central mint-relay-cert` and drop the bundle in the relay state dir)")

func stringTrim(b []byte) string {
	// Strip trailing newline/whitespace without pulling in strings for such
	// a tiny task. Keeps this package dependency-free except for uuid.
	for len(b) > 0 {
		c := b[len(b)-1]
		if c == ' ' || c == '\t' || c == '\n' || c == '\r' {
			b = b[:len(b)-1]
			continue
		}
		break
	}
	return string(b)
}
