// Package syncproto is the JSON message schema for the relay-sync WebSocket
// at /api/v1/relay-sync.
//
// This is NOT the data-channel binary subprotocol (see shared/relayproto).
// It's a low-volume JSON control channel carrying routing updates only.
// Using JSON here keeps it debuggable and the message rate is bounded by
// instance-create frequency, not player traffic.
package syncproto

import "time"

// ProtocolVersion is the relay-sync wire version. Independent of the data
// channel's ProtocolVersion.
const ProtocolVersion = 1

// MsgKind identifies a message's shape.
type MsgKind string

const (
	// Relay → Central (opening frame).
	KindRelayHello MsgKind = "relay.hello"
	// Central → Relay (reply). Declares whether snapshot or delta follows.
	KindCentralHello MsgKind = "central.hello"

	// Central → Relay (one-shot after hello, when relay has no cache).
	KindRoutingSnapshot MsgKind = "routing.snapshot"
	// Central → Relay (streaming, one per event).
	KindRoutingDelta MsgKind = "routing.delta"

	// Keepalive.
	KindPing MsgKind = "ping"
	KindPong MsgKind = "pong"
)

// Envelope is the outer JSON wrapper for every relay-sync message.
type Envelope struct {
	Version int     `json:"v"`
	Kind    MsgKind `json:"kind"`
	Data    any     `json:"data,omitempty"`
}

// RelayHello is the first message the relay sends on the /relay-sync WS.
// KnownVersion is the highest routing_events.version the relay has already
// applied to its cache. Central uses it to decide snapshot vs delta replay.
// New relays send 0 to request a full snapshot.
type RelayHello struct {
	ProtocolVersion int    `json:"protocol_version"`
	RelayID         string `json:"relay_id"`
	KnownVersion    int64  `json:"known_version"`
	RelayVersion    string `json:"relay_version"` // binary version for audit
}

// CentralHello is Central's reply. Mode is either "snapshot" or "delta"
// depending on whether Central decided to send a full snapshot (because the
// relay's known_version is too far behind, or zero) or a delta replay.
type CentralHello struct {
	ProtocolVersion int    `json:"protocol_version"`
	Mode            string `json:"mode"` // "snapshot" or "delta"
	ServerVersion   string `json:"server_version"`
}

// RoutingSnapshot is a full current routing table. Sent once, right after a
// "snapshot"-mode CentralHello. Following the snapshot, Central then streams
// any deltas that happen.
type RoutingSnapshot struct {
	CurrentVersion int64           `json:"current_version"`
	Entries        []RoutingEntry  `json:"entries"`
}

// RoutingEntry mirrors store.RoutingEntry but with string-typed UUIDs and
// no pgx dependency, suitable for JSON.
type RoutingEntry struct {
	RouteKind  string `json:"route_kind"`
	Protocol   string `json:"protocol"`
	Hostname   string `json:"hostname,omitempty"`
	PublicPort int    `json:"public_port,omitempty"`
	InstanceID string `json:"instance_id"`
	KeeperID   string `json:"keeper_id"`
	HostPort   int    `json:"host_port"`
	Version    int64  `json:"version"`
}

// RoutingDelta is a single change event.
type RoutingDelta struct {
	Version    int64     `json:"version"`
	At         time.Time `json:"at"`
	EventType  string    `json:"event_type"` // "upsert" | "delete"
	RouteKind  string    `json:"route_kind"`
	Protocol   string    `json:"protocol"`
	Hostname   string    `json:"hostname,omitempty"`
	PublicPort int       `json:"public_port,omitempty"`
	// Populated on upsert, zero on delete.
	InstanceID string `json:"instance_id,omitempty"`
	KeeperID   string `json:"keeper_id,omitempty"`
	HostPort   int    `json:"host_port,omitempty"`
}
