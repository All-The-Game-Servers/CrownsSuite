package auth

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"time"
)

// ProgenitorCertLifetime - shorter than a relay cert because Progenitor
// certs typically live on operator laptops (portable, riskier). 1 year is
// a reasonable default; operators rotate by re-running `mint-progenitor-cert`.
const ProgenitorCertLifetime = 365 * 24 * time.Hour

// ProgenitorBundle is everything Progenitor needs to run: an identifier,
// its leaf cert + key, and the CA cert it must trust.
type ProgenitorBundle struct {
	ProgenitorID string
	CertPEM      []byte
	KeyPEM       []byte
	CACertPEM    []byte
}

// IssueProgenitorCert generates a fresh keypair + leaf cert for a Progenitor
// install. Subject OU is "ATGS Progenitor" so admin endpoints can distinguish
// a Progenitor request from a Keeper or Relay at TLS time.
//
// Phase 5 treats every Progenitor as having the same privileges (full admin).
// Per-operator roles are a Phase 7 concern.
func (c *CA) IssueProgenitorCert(progenitorID string) (*ProgenitorBundle, error) {
	caCert, caKey, err := parseCABundle(c.certPEM, c.keyPEM)
	if err != nil {
		return nil, fmt.Errorf("parse ca: %w", err)
	}

	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, fmt.Errorf("generate key: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("serial: %w", err)
	}

	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:         progenitorID,
			Organization:       []string{"XKStudios ATGS"},
			OrganizationalUnit: []string{"ATGS Progenitor"},
		},
		NotBefore:   time.Now().Add(-1 * time.Minute),
		NotAfter:    time.Now().Add(ProgenitorCertLifetime),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		// Progenitor is purely a client of Central's admin API.
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}

	der, err := x509.CreateCertificate(rand.Reader, tmpl, caCert, &key.PublicKey, caKey)
	if err != nil {
		return nil, fmt.Errorf("sign cert: %w", err)
	}

	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(key),
	})

	return &ProgenitorBundle{
		ProgenitorID: progenitorID,
		CertPEM:      certPEM,
		KeyPEM:       keyPEM,
		CACertPEM:    c.certPEM,
	}, nil
}
