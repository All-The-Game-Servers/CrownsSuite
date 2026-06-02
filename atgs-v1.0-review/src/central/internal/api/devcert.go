package api

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"time"

	"github.com/xkstudios/atgs/central/internal/auth"
)

// devServerCert issues a leaf server certificate for Central, signed by its
// own CA. SANs cover localhost and 127.0.0.1 so Keepers running on the same
// box for development can verify the TLS handshake.
//
// In production, Central's serving cert should come from a real CA (or at
// minimum a separate, non-Keeper-signing internal CA) and be loaded from
// disk. This helper exists for Phase 1 dev ergonomics.
func devServerCert(ca *auth.CA) (certPEM, keyPEM []byte, err error) {
	// Parse the CA so we can sign.
	caCert, caKey, err := loadCABundle(ca)
	if err != nil {
		return nil, nil, err
	}

	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   "central.atgs.local",
			Organization: []string{"XKStudios ATGS Central"},
		},
		NotBefore:   time.Now().Add(-1 * time.Minute),
		NotAfter:    time.Now().Add(365 * 24 * time.Hour),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:    []string{"localhost", "central.atgs.local"},
		IPAddresses: []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, caCert, &key.PublicKey, caKey)
	if err != nil {
		return nil, nil, fmt.Errorf("sign server cert: %w", err)
	}
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	return certPEM, keyPEM, nil
}

func loadCABundle(ca *auth.CA) (*x509.Certificate, *rsa.PrivateKey, error) {
	// Re-parse the CA cert for signing. The CA holds its PEMs; we decode here
	// to keep the auth package's surface small.
	block, _ := pem.Decode(ca.CertPEM())
	if block == nil {
		return nil, nil, errors.New("ca cert: no PEM block")
	}
	caCert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, nil, err
	}
	// We need the CA key too. Read it off the CA instance's directory.
	// To keep the auth package's API tight I'd normally expose a Signer
	// method; for Phase 1 this indirection is fine.
	keyBytes, err := ca.ReadKey()
	if err != nil {
		return nil, nil, err
	}
	block, _ = pem.Decode(keyBytes)
	if block == nil {
		return nil, nil, errors.New("ca key: no PEM block")
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
