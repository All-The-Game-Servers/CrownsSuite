// Package config loads Keeper runtime configuration.
//
// All keys use the ATGS_KEEPER_* prefix. The Keeper persists its identity
// (certificate, private key, Central's CA cert, keeper id) into StateDir.
// If StateDir contains a complete identity, enrollment is skipped.
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type Config struct {
	// CentralEnrollURL is the HTTPS endpoint used for initial enrollment,
	// e.g. https://127.0.0.1:8443. Falls back to the enrollment token's
	// host if empty (not implemented yet).
	CentralEnrollURL string

	// EnrollToken is the one-time token handed to the operator by a
	// Progenitor. Only needed for the first run. After enrollment, the
	// identity on disk is sufficient.
	EnrollToken string

	// StateDir is where we persist identity: keeper.id, client.crt, client.key,
	// ca.crt, central.endpoint.
	StateDir string

	// EggsDir is where egg manifests live. Default: ./eggs.
	EggsDir string

	// DataRoot is the host path root for per-instance container volumes.
	// If empty, defaults to <StateDir>/instances.
	DataRoot string

	// AgentVersion is reported to Central for audit. Set at build time.
	AgentVersion string

	// RelayDataURLs are candidate relay /ws/data endpoints the Keeper should
	// keep trying in order. Phase 3 uses a static list from env.
	RelayDataURLs []string

	// InsecureSkipVerify disables TLS verification against Central's cert.
	// Dev only. Production should never set this.
	InsecureSkipVerify bool

	// FakeDocker replaces the real Docker runtime with an in-memory stub.
	// Used for end-to-end testing in environments without Docker. Never
	// enable in production.
	FakeDocker bool
}

func Load() (*Config, error) {
	state := getEnv("ATGS_KEEPER_STATE_DIR", defaultStateDir())
	if err := os.MkdirAll(state, 0o700); err != nil {
		return nil, fmt.Errorf("mkdir state dir: %w", err)
	}
	cfg := &Config{
		CentralEnrollURL:   getEnv("ATGS_KEEPER_CENTRAL_URL", "https://127.0.0.1:8443"),
		EnrollToken:        getEnv("ATGS_ENROLL_TOKEN", ""),
		StateDir:           state,
		EggsDir:            getEnv("ATGS_KEEPER_EGGS_DIR", "./eggs"),
		DataRoot:           getEnv("ATGS_KEEPER_DATA_ROOT", ""),
		AgentVersion:       getEnv("ATGS_KEEPER_VERSION", "0.3.0-phase3"),
		InsecureSkipVerify: getEnv("ATGS_KEEPER_INSECURE_TLS", "true") == "true",
		FakeDocker:         getEnv("ATGS_KEEPER_FAKE_DOCKER", "false") == "true",
	}
	for _, item := range strings.Split(getEnv("ATGS_KEEPER_RELAY_DATA_URLS", "wss://127.0.0.1:7443/ws/data"), ",") {
		if trimmed := strings.TrimSpace(item); trimmed != "" {
			cfg.RelayDataURLs = append(cfg.RelayDataURLs, trimmed)
		}
	}
	return cfg, nil
}

func defaultStateDir() string {
	if v := os.Getenv("ATGS_KEEPER_HOME"); v != "" {
		return v
	}
	// Fall back to a local ./atgs-keeper dir so dev doesn't touch $HOME.
	wd, err := os.Getwd()
	if err != nil {
		return ".atgs-keeper"
	}
	return filepath.Join(wd, ".atgs-keeper")
}

func getEnv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
