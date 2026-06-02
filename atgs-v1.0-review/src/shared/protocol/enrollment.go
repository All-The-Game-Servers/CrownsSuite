package protocol

// Enrollment is the one-time bootstrap flow that gives a new Keeper its
// long-lived identity. It happens over HTTPS (not the control channel) because
// the Keeper doesn't have a certificate yet.
//
// Flow:
//   1. Progenitor calls POST /api/v1/enrollment-tokens (authenticated as
//      Progenitor) and gets back a short-lived token.
//   2. Progenitor hands the token to the Keeper operator out-of-band.
//   3. Keeper generates a keypair locally, builds a CSR, and POSTs
//      EnrollmentRequest to /api/v1/enroll with the token and CSR.
//   4. Central validates the token (unused, not expired), signs the CSR
//      with its internal CA, and returns EnrollmentResponse containing the
//      signed cert plus the CA cert the Keeper will need to verify Central.
//   5. Keeper stores cert + key + CA and from then on uses mTLS.
//
// The token is single-use and expires quickly (default 15 minutes). This
// narrows the window for a stolen token and forces the operator to actually
// be ready when they mint one.

// EnrollmentRequest is the Keeper's side of the bootstrap exchange.
type EnrollmentRequest struct {
	Token       string `json:"token"`
	CSRPEM      string `json:"csr_pem"`       // PEM-encoded PKCS#10 CSR
	AgentVersion string `json:"agent_version"`
	Platform    string `json:"platform"`
	Arch        string `json:"arch"`
	Hostname    string `json:"hostname"`
	// PublicKeyFingerprint is the SHA-256 of the DER-encoded SubjectPublicKeyInfo.
	// Central records it for audit; the Keeper uses it later as a tamper check
	// if the issued cert ever needs to be rotated.
	PublicKeyFingerprint string `json:"public_key_fingerprint"`

	// Phase 7: the Keeper generates an Ed25519 keypair for signing its own
	// control-channel envelopes (acks, results, resource reports) and sends
	// the public key here. Central stores it in the keepers row.
	Ed25519PublicKey string `json:"ed25519_public_key"` // hex-encoded 32 bytes
}

// EnrollmentResponse carries the signed cert and metadata back to the Keeper.
type EnrollmentResponse struct {
	KeeperID          string `json:"keeper_id"`           // stable UUID for this Keeper
	CertificatePEM    string `json:"certificate_pem"`     // Keeper's signed leaf cert
	CACertificatePEM  string `json:"ca_certificate_pem"`  // Central's CA cert (trust root)
	CentralWSEndpoint string `json:"central_ws_endpoint"` // e.g. wss://central.example.com/ws
	CertNotAfterUnix  int64  `json:"cert_not_after_unix"` // for renewal scheduling

	// Phase 7: Central's Ed25519 public key, used by the Keeper to verify
	// inbound task dispatches and any other Central-originated envelopes.
	// hex-encoded 32 bytes. Long-lived key rotated only via admin action.
	CentralEd25519PublicKey string `json:"central_ed25519_public_key"`
}
