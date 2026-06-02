// Package tlsutil builds TLS configs for the relay's mTLS listeners and
// outbound connections.
//
// Two shapes of config:
//
//   - ServerConfig: presents the relay's cert, requires+verifies client
//     cert against the CA bundle. Used by the data-channel server and the
//     peer server.
//
//   - ClientConfig: presents the relay's cert when dialing Central (for
//     routing sync) or another relay (for peer mesh).
//
// The relay uses the SAME cert for both directions, signed by Central's CA.
// Subject OU is "ATGS Relay" (enforced by the cert-minting tool and
// verifiable by peers).
package tlsutil

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"

	"github.com/xkstudios/atgs/relay/internal/identity"
)

// ServerConfig returns a TLS config for listeners that require mTLS.
func ServerConfig(id *identity.Identity) (*tls.Config, error) {
	if id == nil {
		return nil, errors.New("nil identity")
	}
	// Belt and suspenders: confirm the cert actually chains to the CA we
	// trust, so a misconfigured identity fails at startup rather than on
	// the first connection.
	if err := verifyChain(id); err != nil {
		return nil, fmt.Errorf("relay cert chain: %w", err)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{id.Certificate},
		ClientCAs:    id.CACertPool,
		ClientAuth:   tls.RequireAndVerifyClientCert,
		MinVersion:   tls.VersionTLS12,
	}, nil
}

// ClientConfig returns a TLS config for dialing Central or peer relays.
// serverName should be the expected hostname (for SNI + verification).
func ClientConfig(id *identity.Identity, serverName string) (*tls.Config, error) {
	if id == nil {
		return nil, errors.New("nil identity")
	}
	return &tls.Config{
		Certificates: []tls.Certificate{id.Certificate},
		RootCAs:      id.CACertPool,
		ServerName:   serverName,
		MinVersion:   tls.VersionTLS12,
	}, nil
}

func verifyChain(id *identity.Identity) error {
	// Parse the leaf (first cert in the chain).
	if len(id.Certificate.Certificate) == 0 {
		return errors.New("identity has no certificate data")
	}
	leaf, err := x509.ParseCertificate(id.Certificate.Certificate[0])
	if err != nil {
		return fmt.Errorf("parse leaf: %w", err)
	}
	_, err = leaf.Verify(x509.VerifyOptions{
		Roots: id.CACertPool,
	})
	return err
}
