// Package config loads Relay runtime configuration from the environment.
//
// A Relay has its own identity (relay_id UUID, issued at provisioning) and
// its own mTLS client certificate signed by Central's CA with OU="ATGS Relay".
// Certs are minted by an operator using `central mint-relay-cert`; the relay
// does NOT enroll itself at runtime (unlike Keepers, which can self-enroll
// with a one-time token). Relays are infrastructure; they come and go under
// operator control.
//
// All keys use the ATGS_RELAY_* prefix.
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type Config struct {
	// RelayID is the stable UUID identity of this relay. Read from
	// <StateDir>/relay.id at startup; written there by the cert-minting
	// tool when provisioning a new relay.
	RelayID string

	// StateDir is where this relay keeps its identity and local caches.
	// Contents: relay.id, client.crt, client.key, ca.crt, central.endpoint,
	// peers.cache, routing.db.
	StateDir string

	// CentralSyncURL is the wss:// URL of Central's relay-sync endpoint.
	// e.g. wss://central.example.com:8443/api/v1/relay-sync
	CentralSyncURL string

	// IngressAddr is where player TCP connections are accepted (the public
	// Minecraft port). Default 0.0.0.0:25565. In production this is typically
	// bound to a public interface; in dev it's loopback.
	IngressAddr string

	// DataChannelAddr is where Keeper /ws/data connections land. mTLS.
	// Default 0.0.0.0:7443. Different port from the control channel so
	// network policy can distinguish them.
	DataChannelAddr string

	// PeerAddr is the mTLS listener for inter-relay coordination RPC.
	// Default 0.0.0.0:7444. Every relay listens on its PeerAddr and dials
	// every other relay's PeerAddr as a peer.
	PeerAddr string

	// Peers is a comma-separated list of other relay endpoints this relay
	// should maintain persistent mTLS connections with, e.g.
	// "relay-b.example.com:7444,relay-c.example.com:7444". Parsed into
	// PeerEndpoints at load time. Phase 3: static config. Phase 8:
	// service discovery.
	Peers         string
	PeerEndpoints []string

	// PingInterval and PingTimeout tune data-channel liveness to Keepers.
	// Separate from the control channel's timers so one can fail without
	// taking down the other.
	PingInterval time.Duration
	PingTimeout  time.Duration

	// StreamStallTimeout is how long a single stream can have its backpressure
	// channel fully blocked before the stream is killed with ErrCodeTimeout.
	// Guards against a single slow/dead keeper wedging memory.
	StreamStallTimeout time.Duration

	// BedrockBindHost is the interface used for public Bedrock UDP listeners.
	// The listener port is allocated per route from Central.
	BedrockBindHost string

	// BedrockIdleTimeout is how long a Bedrock UDP session can stay silent
	// before the relay closes it and frees local state.
	BedrockIdleTimeout time.Duration

	// Version is the relay binary version for log correlation.
	Version string

	// DevMode: relaxed TLS requirements, pretty logs, more verbose output.
	// Never true in production.
	DevMode bool
}

func Load() (*Config, error) {
	state := getEnv("ATGS_RELAY_STATE_DIR", defaultStateDir())
	if err := os.MkdirAll(state, 0o700); err != nil {
		return nil, fmt.Errorf("mkdir state dir: %w", err)
	}

	// RelayID is read from disk if present. The cert-minting tool writes it.
	var relayID string
	if data, err := os.ReadFile(filepath.Join(state, "relay.id")); err == nil {
		relayID = strings.TrimSpace(string(data))
	}

	cfg := &Config{
		RelayID:            relayID,
		StateDir:           state,
		CentralSyncURL:     getEnv("ATGS_RELAY_CENTRAL_SYNC_URL", "wss://127.0.0.1:8443/api/v1/relay-sync"),
		IngressAddr:        getEnv("ATGS_RELAY_INGRESS_ADDR", "0.0.0.0:25565"),
		DataChannelAddr:    getEnv("ATGS_RELAY_DATA_ADDR", "0.0.0.0:7443"),
		PeerAddr:           getEnv("ATGS_RELAY_PEER_ADDR", "0.0.0.0:7444"),
		Peers:              getEnv("ATGS_RELAY_PEERS", ""),
		PingInterval:       getEnvDuration("ATGS_RELAY_PING_INTERVAL", 15*time.Second),
		PingTimeout:        getEnvDuration("ATGS_RELAY_PING_TIMEOUT", 45*time.Second),
		StreamStallTimeout: getEnvDuration("ATGS_RELAY_STREAM_STALL", 5*time.Second),
		BedrockBindHost:    getEnv("ATGS_RELAY_BEDROCK_BIND_HOST", "0.0.0.0"),
		BedrockIdleTimeout: getEnvDuration("ATGS_RELAY_BEDROCK_IDLE", 90*time.Second),
		Version:            "0.3.0-phase3",
		DevMode:            getEnvBool("ATGS_RELAY_DEV", true),
	}

	if cfg.Peers != "" {
		for _, p := range strings.Split(cfg.Peers, ",") {
			if trimmed := strings.TrimSpace(p); trimmed != "" {
				cfg.PeerEndpoints = append(cfg.PeerEndpoints, trimmed)
			}
		}
	}

	if cfg.PingTimeout <= cfg.PingInterval {
		return nil, fmt.Errorf("ping_timeout (%s) must exceed ping_interval (%s)", cfg.PingTimeout, cfg.PingInterval)
	}

	return cfg, nil
}

// HasIdentity reports whether this relay has been provisioned with a cert.
// A relay without identity can only run the `version` subcommand; it cannot
// serve.
func (c *Config) HasIdentity() bool {
	if c.RelayID == "" {
		return false
	}
	for _, f := range []string{"client.crt", "client.key", "ca.crt"} {
		if _, err := os.Stat(filepath.Join(c.StateDir, f)); err != nil {
			return false
		}
	}
	return true
}

func defaultStateDir() string {
	if v := os.Getenv("ATGS_RELAY_HOME"); v != "" {
		return v
	}
	wd, err := os.Getwd()
	if err != nil {
		return ".atgs-relay"
	}
	return filepath.Join(wd, ".atgs-relay")
}

func getEnv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func getEnvDuration(k string, def time.Duration) time.Duration {
	v := os.Getenv(k)
	if v == "" {
		return def
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		return def
	}
	return d
}

func getEnvBool(k string, def bool) bool {
	v := os.Getenv(k)
	if v == "" {
		return def
	}
	return v == "true" || v == "1"
}
