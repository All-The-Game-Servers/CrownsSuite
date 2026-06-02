// Package protocol defines the wire format shared between Central and Keeper.
//
// Framing: after the WebSocket handshake, each message is a single JSON object
// matching Envelope. This keeps things debuggable for Phase 1. When we add the
// relay in Phase 3, relay data frames will use a separate binary subprotocol
// negotiated at handshake time; we don't want player traffic routed through
// JSON parsing.
//
// Every change to this package is a protocol change. Bump ProtocolVersion and
// update both sides in lockstep.
package protocol

// ProtocolVersion is the wire-format version Keeper and Central negotiate at
// handshake. Mismatches are fatal for the connection. Bump this on any
// breaking change to Envelope, MessageKind, or any Payload type.
const ProtocolVersion = 1

// MessageKind identifies the payload shape of an Envelope.
//
// Naming convention: <subject>.<verb>. Server-initiated messages end in the
// imperative form ("request", "dispatch"); Keeper-initiated messages end in
// reporting form ("report", "ack", "ready").
type MessageKind string

const (
	// Handshake (first message each side sends after TCP/TLS is up).
	KindKeeperHello  MessageKind = "keeper.hello"
	KindCentralHello MessageKind = "central.hello"

	// Liveness. Central sends ping, Keeper replies pong. Central drops the
	// connection if no pong within PingTimeout.
	KindPing MessageKind = "ping"
	KindPong MessageKind = "pong"

	// Resource attestation. Keeper reports CPU/RAM/disk periodically.
	// Central stores the latest and uses it for scheduling decisions.
	// In Phase 1 we only define the shape; the Keeper sends a placeholder.
	KindResourcesReport MessageKind = "resources.report"

	// Task dispatch and reporting. Wired up in Phase 2.
	KindTaskDispatch MessageKind = "task.dispatch"
	KindTaskAck      MessageKind = "task.ack"
	KindTaskProgress MessageKind = "task.progress"
	KindTaskResult   MessageKind = "task.result"

	// Error channel. Either side can send; receiver logs and may disconnect.
	KindError MessageKind = "error"
)

// Envelope is the outer frame for every control-channel message.
//
// ID is a UUID string. Client-generated messages (from Keeper) and
// server-generated messages (from Central) each get their own ID. For
// request/response pairs (like task.dispatch → task.result), the reply's
// CorrelationID matches the request's ID.
//
// Payload is message-kind-specific and lives in Data as a raw JSON object.
// Using json.RawMessage here keeps parsing lazy so a bad payload for one kind
// doesn't block parsing the envelope.
//
// Phase 7 signature fields (Sig, Nonce, Ts) are optional. When present, they
// authenticate the envelope with Ed25519 and prevent replay. The signing
// party's public key is implicit from the transport context (Keeper <->
// Central mTLS establishes identity; the envelope signer is the endpoint
// that owns the private key paired with that mTLS cert). See
// shared/envelope/sign.go for the signing and verification logic.
type Envelope struct {
	Version       int         `json:"v"`             // must equal ProtocolVersion
	ID            string      `json:"id"`            // message UUID
	CorrelationID string      `json:"cid,omitempty"` // reply-to ID, when applicable
	Kind          MessageKind `json:"kind"`
	Data          any         `json:"data,omitempty"` // payload; concrete type per Kind

	// Signature fields (Phase 7). All three are either present together or
	// absent together. Server-side policy decides whether absent is an error.
	Ts    int64  `json:"ts,omitempty"`    // unix seconds when signed
	Nonce string `json:"nonce,omitempty"` // random bytes, hex-encoded, prevents replay
	Sig   string `json:"sig,omitempty"`   // Ed25519 over canonical bytes, hex-encoded
}

// KeeperHello is the first frame a Keeper sends after WebSocket upgrade.
// Even though mTLS has already identified the Keeper to Central, we still
// require an explicit hello so protocol version and agent metadata are
// captured in the message stream for audit.
type KeeperHello struct {
	ProtocolVersion int    `json:"protocol_version"`
	AgentVersion    string `json:"agent_version"` // semver of the Keeper binary
	Platform        string `json:"platform"`      // runtime.GOOS, e.g. "linux", "windows"
	Arch            string `json:"arch"`          // runtime.GOARCH, e.g. "amd64"
	Hostname        string `json:"hostname"`      // self-reported; untrusted
}

// CentralHello is Central's reply to KeeperHello. If ProtocolVersion does not
// match, the Keeper should log and exit; the operator may need to update.
type CentralHello struct {
	ProtocolVersion  int    `json:"protocol_version"`
	ServerVersion    string `json:"server_version"`
	PingIntervalSecs int    `json:"ping_interval_secs"`
	SessionID        string `json:"session_id"` // opaque; Keeper includes in logs for support
}

// Ping carries a monotonic counter so Central can measure RTT trends.
type Ping struct {
	Seq int64 `json:"seq"`
}

// Pong echoes the Seq from the most recent Ping.
type Pong struct {
	Seq int64 `json:"seq"`
}

// ResourcesReport is the Keeper's self-report of machine state.
//
// SECURITY NOTE: this is self-reported and must never be trusted for
// billing, capacity guarantees, or admission decisions without independent
// verification. Central stores it for display and scheduling hints only.
type ResourcesReport struct {
	CPUCores        int     `json:"cpu_cores"`
	CPUPercentUsed  float64 `json:"cpu_percent_used"`
	MemTotalBytes   uint64  `json:"mem_total_bytes"`
	MemUsedBytes    uint64  `json:"mem_used_bytes"`
	DiskTotalBytes  uint64  `json:"disk_total_bytes"`
	DiskUsedBytes   uint64  `json:"disk_used_bytes"`
	ReportedAtUnix  int64   `json:"reported_at_unix"`
}

// ErrorPayload describes an error condition. Code is a stable string so the
// other side can branch on it; Message is human-readable.
type ErrorPayload struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Standard error codes.
const (
	ErrProtocolMismatch  = "protocol_mismatch"
	ErrUnauthorized      = "unauthorized"
	ErrMalformedEnvelope = "malformed_envelope"
	ErrUnknownKind       = "unknown_kind"
	ErrInternal          = "internal"
)
