package auth

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"time"
)

// RelayCertLifetime is how long an issued relay cert is valid. Longer than
// a Keeper cert because relays are stable infrastructure under operator
// control, not end-user machines.
const RelayCertLifetime = 2 * 365 * 24 * time.Hour

// RelayBundle is everything a newly-provisioned relay needs to run:
// its UUID identity, its leaf cert + key, and the CA cert it must trust.
type RelayBundle struct {
	RelayID  string // UUID string
	CertPEM  []byte
	KeyPEM   []byte
	CACertPEM []byte
}

// IssueRelayCert generates a new RSA keypair and a leaf certificate for a
// new relay. The relay's UUID becomes the cert's CommonName; the Subject
// OU is "ATGS Relay" so peers and Central can distinguish it from a
// Keeper cert at TLS time.
//
// In production, the CA's private key would ideally not live alongside
// cert issuance. For Phase 3 the CA key is already on Central's disk;
// this command just uses it. Phase 7 hardening would move CA key access
// behind a hardware-backed signer.
func (c *CA) IssueRelayCert(relayID string) (*RelayBundle, error) {
	caCert, caKey, err := parseCABundle(c.certPEM, c.keyPEM)
	if err != nil {
		return nil, fmt.Errorf("parse ca: %w", err)
	}

	// Fresh keypair for this relay.
	relayKey, err := rsa.GenerateKey(rand.Reader, 2048)
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
			CommonName:         relayID,
			Organization:       []string{"XKStudios ATGS"},
			OrganizationalUnit: []string{"ATGS Relay"},
		},
		NotBefore:   time.Now().Add(-1 * time.Minute),
		NotAfter:    time.Now().Add(RelayCertLifetime),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		// Relays act as BOTH server (for keepers and peers dialing in) and
		// client (when dialing Central's sync endpoint and peer relays).
		ExtKeyUsage: []x509.ExtKeyUsage{
			x509.ExtKeyUsageServerAuth,
			x509.ExtKeyUsageClientAuth,
		},
		DNSNames:    []string{"localhost"},
	}

	der, err := x509.CreateCertificate(rand.Reader, tmpl, caCert, &relayKey.PublicKey, caKey)
	if err != nil {
		return nil, fmt.Errorf("sign cert: %w", err)
	}

	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(relayKey),
	})

	return &RelayBundle{
		RelayID:   relayID,
		CertPEM:   certPEM,
		KeyPEM:    keyPEM,
		CACertPEM: c.certPEM,
	}, nil
}

func parseCABundle(certPEM, keyPEM []byte) (*x509.Certificate, *rsa.PrivateKey, error) {
	block, _ := pem.Decode(certPEM)
	if block == nil {
		return nil, nil, errors.New("ca.crt: no PEM block")
	}
	caCert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, nil, err
	}
	block, _ = pem.Decode(keyPEM)
	if block == nil {
		return nil, nil, errors.New("ca.key: no PEM block")
	}
	var caKey *rsa.PrivateKey
	switch block.Type {
	case "RSA PRIVATE KEY":
		caKey, err = x509.ParsePKCS1PrivateKey(block.Bytes)
	case "PRIVATE KEY":
		k, perr := x509.ParsePKCS8PrivateKey(block.Bytes)
		if perr != nil {
			return nil, nil, perr
		}
		var ok bool
		caKey, ok = k.(*rsa.PrivateKey)
		if !ok {
			return nil, nil, errors.New("ca key is not RSA")
		}
	default:
		return nil, nil, fmt.Errorf("unknown key type %q", block.Type)
	}
	if err != nil {
		return nil, nil, err
	}
	return caCert, caKey, nil
}
