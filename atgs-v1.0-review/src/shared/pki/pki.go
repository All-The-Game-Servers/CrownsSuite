// Package pki contains shared PKI helpers used by both Central (as a CA) and
// Keeper (as a certificate holder).
//
// Design notes:
//   - Central runs a single internal CA for Keeper identities. It is NOT a
//     public CA; Keepers trust it out of band via the bootstrap response.
//   - Keeper certs are client certs only. They carry the Keeper's UUID in the
//     Common Name so Central can cheaply identify connections at TLS accept.
//   - Cert lifetime for Keepers is 90 days by default; renewal is a future
//     subsystem (Phase 7 hardening). For now, re-enrollment is the escape.
//   - Keys are 2048-bit RSA for broad client compatibility. We could move to
//     Ed25519 later; the protocol doesn't depend on the algorithm.
package pki

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"time"
)

const (
	// RSAKeyBits is the key size for generated keys. 2048 is the floor that
	// modern browsers and Go's TLS accept without complaint.
	RSAKeyBits = 2048

	// CACertLifetime is how long the root CA is valid. Long, because rotating
	// it means re-issuing every Keeper cert.
	CACertLifetime = 10 * 365 * 24 * time.Hour

	// KeeperCertLifetime is how long an issued Keeper cert is valid before
	// renewal or re-enrollment is required.
	KeeperCertLifetime = 90 * 24 * time.Hour
)

// GenerateKey creates a new RSA private key.
func GenerateKey() (*rsa.PrivateKey, error) {
	return rsa.GenerateKey(rand.Reader, RSAKeyBits)
}

// CreateCA generates a self-signed CA certificate and its private key.
// Used once by Central at bootstrap time.
func CreateCA(commonName string) (certPEM, keyPEM []byte, err error) {
	key, err := GenerateKey()
	if err != nil {
		return nil, nil, fmt.Errorf("generate ca key: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, nil, fmt.Errorf("generate ca serial: %w", err)
	}

	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: commonName, Organization: []string{"XKStudios ATGS"}},
		NotBefore:             time.Now().Add(-1 * time.Minute),
		NotAfter:              time.Now().Add(CACertLifetime),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            0, // no intermediates
		MaxPathLenZero:        true,
	}

	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, fmt.Errorf("sign ca cert: %w", err)
	}

	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	return certPEM, keyPEM, nil
}

// CreateCSR builds a PEM-encoded PKCS#10 Certificate Signing Request.
// Used by the Keeper during enrollment.
//
// commonName should be set to the desired identity (we use the keeper UUID
// once known, or a placeholder otherwise; Central overrides this when signing).
func CreateCSR(key *rsa.PrivateKey, commonName string) ([]byte, error) {
	tmpl := &x509.CertificateRequest{
		Subject: pkix.Name{CommonName: commonName, Organization: []string{"XKStudios ATGS Keeper"}},
	}
	der, err := x509.CreateCertificateRequest(rand.Reader, tmpl, key)
	if err != nil {
		return nil, fmt.Errorf("create csr: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: der}), nil
}

// SignCSR validates a PEM-encoded CSR and issues a Keeper leaf certificate
// signed by the given CA. The returned cert's CommonName is forced to
// keeperID, regardless of what the CSR claimed; the Keeper does not get to
// choose its own identity.
func SignCSR(caCertPEM, caKeyPEM, csrPEM []byte, keeperID string) ([]byte, error) {
	caCert, err := parseCert(caCertPEM)
	if err != nil {
		return nil, fmt.Errorf("parse ca cert: %w", err)
	}
	caKey, err := parseKey(caKeyPEM)
	if err != nil {
		return nil, fmt.Errorf("parse ca key: %w", err)
	}

	block, _ := pem.Decode(csrPEM)
	if block == nil || block.Type != "CERTIFICATE REQUEST" {
		return nil, errors.New("csr: no PEM CERTIFICATE REQUEST block found")
	}
	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse csr: %w", err)
	}
	if err := csr.CheckSignature(); err != nil {
		return nil, fmt.Errorf("csr signature invalid: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("generate cert serial: %w", err)
	}

	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   keeperID,
			Organization: []string{"XKStudios ATGS Keeper"},
		},
		NotBefore:   time.Now().Add(-1 * time.Minute),
		NotAfter:    time.Now().Add(KeeperCertLifetime),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}

	der, err := x509.CreateCertificate(rand.Reader, tmpl, caCert, csr.PublicKey, caKey)
	if err != nil {
		return nil, fmt.Errorf("sign leaf cert: %w", err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), nil
}

// PublicKeyFingerprint returns the lowercase hex SHA-256 of a public key's
// DER-encoded SubjectPublicKeyInfo. Used as a stable identifier in audit logs.
func PublicKeyFingerprint(pub *rsa.PublicKey) (string, error) {
	der, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:]), nil
}

func parseCert(p []byte) (*x509.Certificate, error) {
	block, _ := pem.Decode(p)
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, errors.New("no PEM CERTIFICATE block found")
	}
	return x509.ParseCertificate(block.Bytes)
}

func parseKey(p []byte) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode(p)
	if block == nil {
		return nil, errors.New("no PEM block found")
	}
	switch block.Type {
	case "RSA PRIVATE KEY":
		return x509.ParsePKCS1PrivateKey(block.Bytes)
	case "PRIVATE KEY":
		k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return nil, err
		}
		rsaKey, ok := k.(*rsa.PrivateKey)
		if !ok {
			return nil, errors.New("not an RSA key")
		}
		return rsaKey, nil
	default:
		return nil, fmt.Errorf("unsupported key type %q", block.Type)
	}
}
