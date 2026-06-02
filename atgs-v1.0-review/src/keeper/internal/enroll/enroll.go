// Package enroll handles the Keeper's one-time bootstrap with Central.
//
// Flow on first run:
//  1. Generate RSA keypair locally.
//  2. Build a CSR (CN is a placeholder; Central overrides it).
//  3. POST token + csr + agent metadata to Central's /api/v1/enroll.
//  4. Persist: client.crt, client.key, ca.crt, keeper.id, central.endpoint.
//
// On subsequent runs the Keeper skips this entirely and loads from disk.
package enroll

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/xkstudios/atgs/shared/pki"
	"github.com/xkstudios/atgs/shared/protocol"
)

const (
	fileKeeperID       = "keeper.id"
	fileCert           = "client.crt"
	fileKey            = "client.key"
	fileCA             = "ca.crt"
	fileEndpoint       = "central.endpoint"
	fileEd25519Priv    = "ed25519.key"         // Keeper's private signing key (0400)
	fileEd25519Pub     = "ed25519.pub"         // Keeper's public key (0644)
	fileCentralEd25519 = "central_ed25519.pub" // Central's public key for verifying inbound envelopes
)

// Identity is what the Keeper loads after enrollment or from disk.
type Identity struct {
	KeeperID     string
	Certificate  tls.Certificate
	CACertPool   *x509.CertPool
	CAPEM        []byte
	WSEndpoint   string
	CertNotAfter time.Time

	// Phase 7: envelope signing keys.
	// Priv is nil on pre-Phase-7 identities loaded from disk (no ed25519.key
	// present); callers that require signing MUST check.
	Ed25519Priv       ed25519.PrivateKey
	Ed25519Pub        ed25519.PublicKey
	CentralEd25519Pub ed25519.PublicKey // verifier key for inbound envelopes
}

// LoadFromDisk returns the persisted identity, or nil if enrollment is
// still required.
func LoadFromDisk(stateDir string) (*Identity, error) {
	idPath := filepath.Join(stateDir, fileKeeperID)
	if _, err := os.Stat(idPath); errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	keeperID, err := os.ReadFile(idPath)
	if err != nil {
		return nil, err
	}
	certPEM, err := os.ReadFile(filepath.Join(stateDir, fileCert))
	if err != nil {
		return nil, err
	}
	keyPEM, err := os.ReadFile(filepath.Join(stateDir, fileKey))
	if err != nil {
		return nil, err
	}
	caPEM, err := os.ReadFile(filepath.Join(stateDir, fileCA))
	if err != nil {
		return nil, err
	}
	endpoint, err := os.ReadFile(filepath.Join(stateDir, fileEndpoint))
	if err != nil {
		return nil, err
	}

	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, fmt.Errorf("load keypair: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("bad ca cert on disk")
	}

	// Parse the leaf so we can surface NotAfter for logging.
	block, _ := pem.Decode(certPEM)
	var notAfter time.Time
	if block != nil {
		if leaf, err := x509.ParseCertificate(block.Bytes); err == nil {
			notAfter = leaf.NotAfter
		}
	}

	// Phase 7: load Ed25519 keys if present. Absent is OK for pre-Phase-7
	// identities; the control channel will run unsigned in that case.
	var (
		edPriv    ed25519.PrivateKey
		edPub     ed25519.PublicKey
		centralEd ed25519.PublicKey
	)
	if raw, err := os.ReadFile(filepath.Join(stateDir, fileEd25519Priv)); err == nil {
		if len(raw) != ed25519.PrivateKeySize {
			return nil, fmt.Errorf("ed25519 private key size %d != %d", len(raw), ed25519.PrivateKeySize)
		}
		edPriv = raw
	}
	if raw, err := os.ReadFile(filepath.Join(stateDir, fileEd25519Pub)); err == nil {
		if len(raw) != ed25519.PublicKeySize {
			return nil, fmt.Errorf("ed25519 public key size %d != %d", len(raw), ed25519.PublicKeySize)
		}
		edPub = raw
	}
	if raw, err := os.ReadFile(filepath.Join(stateDir, fileCentralEd25519)); err == nil {
		if len(raw) != ed25519.PublicKeySize {
			return nil, fmt.Errorf("central ed25519 key size %d != %d", len(raw), ed25519.PublicKeySize)
		}
		centralEd = raw
	}

	return &Identity{
		KeeperID:          string(bytes.TrimSpace(keeperID)),
		Certificate:       cert,
		CACertPool:        pool,
		CAPEM:             caPEM,
		WSEndpoint:        string(bytes.TrimSpace(endpoint)),
		CertNotAfter:      notAfter,
		Ed25519Priv:       edPriv,
		Ed25519Pub:        edPub,
		CentralEd25519Pub: centralEd,
	}, nil
}

// Enroll runs the bootstrap flow and persists the resulting identity.
// insecureSkipVerify is for dev only; it disables TLS verification against
// Central's serving cert, which is required when Central is running with
// a self-signed dev cert and the Keeper has not yet received the CA bundle.
func Enroll(ctx context.Context, centralURL, token, stateDir, agentVersion string, insecureSkipVerify bool) (*Identity, error) {
	key, err := pki.GenerateKey()
	if err != nil {
		return nil, fmt.Errorf("generate key: %w", err)
	}
	csrPEM, err := pki.CreateCSR(key, "unenrolled-keeper")
	if err != nil {
		return nil, fmt.Errorf("create csr: %w", err)
	}
	fingerprint, err := pki.PublicKeyFingerprint(&key.PublicKey)
	if err != nil {
		return nil, fmt.Errorf("fingerprint: %w", err)
	}

	hostname, _ := os.Hostname()

	// Phase 7: generate Ed25519 keypair for envelope signing.
	edPub, edPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate ed25519: %w", err)
	}

	reqBody := protocol.EnrollmentRequest{
		Token:                token,
		CSRPEM:               string(csrPEM),
		AgentVersion:         agentVersion,
		Platform:             runtime.GOOS,
		Arch:                 runtime.GOARCH,
		Hostname:             hostname,
		PublicKeyFingerprint: fingerprint,
		Ed25519PublicKey:     hex.EncodeToString(edPub),
	}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return nil, err
	}

	client := &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: insecureSkipVerify, //nolint:gosec // dev only
				MinVersion:         tls.VersionTLS12,
			},
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, enrollmentURL(centralURL), bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("POST enroll: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		msg, _ := readSmall(resp.Body)
		return nil, fmt.Errorf("enroll rejected: %s (body: %s)", resp.Status, msg)
	}

	var enrollResp protocol.EnrollmentResponse
	if err := json.NewDecoder(resp.Body).Decode(&enrollResp); err != nil {
		return nil, fmt.Errorf("decode enroll response: %w", err)
	}

	// Persist everything under stateDir with 0600 for the key, 0644 for
	// everything else.
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return nil, err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(key),
	})
	writes := []struct {
		name string
		data []byte
		perm os.FileMode
	}{
		{fileKeeperID, []byte(enrollResp.KeeperID), 0o644},
		{fileCert, []byte(enrollResp.CertificatePEM), 0o644},
		{fileKey, keyPEM, 0o600},
		{fileCA, []byte(enrollResp.CACertificatePEM), 0o644},
		{fileEndpoint, []byte(enrollResp.CentralWSEndpoint), 0o644},
		{fileEd25519Priv, edPriv, 0o400},
		{fileEd25519Pub, edPub, 0o644},
	}
	// Central's public key - only persist if the server sent one. Keepers
	// enrolling against a pre-Phase-7 Central stay unsigned.
	var centralEd ed25519.PublicKey
	if enrollResp.CentralEd25519PublicKey != "" {
		centralEd, err = hex.DecodeString(enrollResp.CentralEd25519PublicKey)
		if err != nil || len(centralEd) != ed25519.PublicKeySize {
			return nil, fmt.Errorf("central returned bad ed25519 public key")
		}
		writes = append(writes, struct {
			name string
			data []byte
			perm os.FileMode
		}{fileCentralEd25519, centralEd, 0o644})
	}
	for _, w := range writes {
		if err := writeAtomic(filepath.Join(stateDir, w.name), w.data, w.perm); err != nil {
			return nil, fmt.Errorf("write %s: %w", w.name, err)
		}
	}

	// Build the returned Identity without reloading from disk.
	cert, err := tls.X509KeyPair([]byte(enrollResp.CertificatePEM), keyPEM)
	if err != nil {
		return nil, fmt.Errorf("build keypair: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM([]byte(enrollResp.CACertificatePEM)) {
		return nil, errors.New("central returned bad ca cert")
	}
	_ = (*rsa.PrivateKey)(nil) // keep import if later removed

	return &Identity{
		KeeperID:          enrollResp.KeeperID,
		Certificate:       cert,
		CACertPool:        pool,
		CAPEM:             []byte(enrollResp.CACertificatePEM),
		WSEndpoint:        enrollResp.CentralWSEndpoint,
		CertNotAfter:      time.Unix(enrollResp.CertNotAfterUnix, 0),
		Ed25519Priv:       edPriv,
		Ed25519Pub:        edPub,
		CentralEd25519Pub: centralEd,
	}, nil
}

func enrollmentURL(centralURL string) string {
	return strings.TrimRight(centralURL, "/") + "/api/v1/enroll"
}

func readSmall(r interface{ Read([]byte) (int, error) }) (string, error) {
	buf := make([]byte, 4096)
	n, err := r.Read(buf)
	return string(buf[:n]), err
}

// writeAtomic writes data to path via a temp file + rename. This keeps
// partial writes from corrupting identity files if the process is killed.
func writeAtomic(path string, data []byte, perm os.FileMode) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
