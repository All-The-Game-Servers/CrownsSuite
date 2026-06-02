// Package config loads Central's runtime configuration from the environment.
// All keys are prefixed ATGS_CENTRAL_*. Defaults are tuned for local dev;
// production deployments are expected to override explicitly.
package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

type Config struct {
	// HTTP listener for the admin API (Progenitor-facing).
	AdminListenAddr string

	// TLS listener for the Keeper WebSocket endpoint. In dev this is the same
	// process; in production these would likely be separated onto different
	// nodes behind different load balancers.
	KeeperListenAddr string

	// Postgres DSN, e.g. postgres://atgs:atgs@localhost:5432/atgs?sslmode=disable
	DatabaseURL string

	// Where to persist the internal CA cert + key. In production this should
	// be a secrets manager; in dev it's a file on disk the operator protects.
	CADir string

	// Liveness on the control channel.
	PingInterval time.Duration
	PingTimeout  time.Duration

	// Enrollment token lifetime. Short by design.
	EnrollmentTokenTTL time.Duration

	// DevMode relaxes TLS requirements on the admin endpoint and enables
	// pretty-printed logs. Must never be true in production.
	DevMode bool

	// ServerVersion is shown in hellos and /api/v1/version.
	ServerVersion string

	// --- Phase 4: Backups ---

	// BackupStorageDefault picks the default storage backend for new backups
	// when the caller doesn't specify. One of: "central_fs", "object_storage".
	BackupStorageDefault string

	// BackupRoot is the directory where Central stores chunks when using the
	// central_fs backend. Must be writable by the Central process. Chunks live
	// under <BackupRoot>/chunks/.
	BackupRoot string

	// BackupChunkSize is the chunk size in bytes the keeper uses when creating
	// backups. 4 MiB default; 8 MiB recommended for object storage (above S3's
	// 5 MiB multipart minimum). Power of two preferred but not required.
	BackupChunkSize int

	// BackupMasterKeyPath is the path to Central's master encryption key file.
	// The file contains a 32-byte raw key (base64url-encoded, no padding, or
	// raw hex). Used to wrap per-backup data keys. If empty and any encrypted
	// backup is attempted, Central returns an error at backup-create time.
	BackupMasterKeyPath string

	// BackupObjectStorage holds S3-compatible config, used when
	// BackupStorageDefault == "object_storage" or an individual backup requests
	// that mode. Read from ATGS_CENTRAL_BACKUP_OBJECT_* env vars.
	BackupObjectEndpoint  string
	BackupObjectRegion    string
	BackupObjectBucket    string
	BackupObjectPrefix    string
	BackupObjectAccessKey string
	BackupObjectSecretKey string

	// Phase 7: hardening.

	// RequireSignedTasks flips strict signature mode. When true, inbound
	// envelopes lacking a valid signature are dropped on Central side.
	// During transition: false. After every keeper has re-enrolled: true.
	RequireSignedTasks bool

	// TaskRateLimitPerMin / TaskRateLimitBurst control the per-keeper token
	// bucket on task dispatch. 100/500 are the locked Phase 7 defaults.
	TaskRateLimitPerMin int
	TaskRateLimitBurst  int

	// AuditSyslogPath: when non-empty, audit events are also streamed to this
	// file (or "syslog" keyword wires the system syslog). Empty = database only.
	AuditSyslogPath string

	// Phase 8: human auth + admin bootstrap.
	AdminBootstrapEmail    string
	AdminBootstrapPassword string
	SessionCleanupTick     time.Duration

	// Bedrock relay public UDP port allocation range.
	BedrockPublicPortMin int
	BedrockPublicPortMax int
}

// Load reads config from env with dev-friendly defaults.
func Load() (*Config, error) {
	c := &Config{
		AdminListenAddr:    getEnv("ATGS_CENTRAL_ADMIN_ADDR", "127.0.0.1:8080"),
		KeeperListenAddr:   getEnv("ATGS_CENTRAL_KEEPER_ADDR", "127.0.0.1:8443"),
		DatabaseURL:        getEnv("ATGS_CENTRAL_DATABASE_URL", "postgres://atgs:atgs@localhost:5432/atgs?sslmode=disable"),
		CADir:              getEnv("ATGS_CENTRAL_CA_DIR", "./.atgs/ca"),
		PingInterval:       getEnvDuration("ATGS_CENTRAL_PING_INTERVAL", 20*time.Second),
		PingTimeout:        getEnvDuration("ATGS_CENTRAL_PING_TIMEOUT", 60*time.Second),
		EnrollmentTokenTTL: getEnvDuration("ATGS_CENTRAL_ENROLL_TTL", 15*time.Minute),
		DevMode:            getEnvBool("ATGS_CENTRAL_DEV", true),
		ServerVersion:      "0.4.0-phase4",

		BackupStorageDefault: getEnv("ATGS_CENTRAL_BACKUP_STORAGE", "central_fs"),
		BackupRoot:           getEnv("ATGS_CENTRAL_BACKUP_ROOT", "./.atgs/backups"),
		BackupChunkSize:      getEnvInt("ATGS_CENTRAL_BACKUP_CHUNK_SIZE", 4*1024*1024),
		BackupMasterKeyPath:  getEnv("ATGS_CENTRAL_BACKUP_MASTER_KEY", ""),

		BackupObjectEndpoint:  getEnv("ATGS_CENTRAL_BACKUP_OBJECT_ENDPOINT", ""),
		BackupObjectRegion:    getEnv("ATGS_CENTRAL_BACKUP_OBJECT_REGION", ""),
		BackupObjectBucket:    getEnv("ATGS_CENTRAL_BACKUP_OBJECT_BUCKET", ""),
		BackupObjectPrefix:    getEnv("ATGS_CENTRAL_BACKUP_OBJECT_PREFIX", ""),
		BackupObjectAccessKey: getEnv("ATGS_CENTRAL_BACKUP_OBJECT_ACCESS_KEY", ""),
		BackupObjectSecretKey: getEnv("ATGS_CENTRAL_BACKUP_OBJECT_SECRET_KEY", ""),

		// Phase 7
		RequireSignedTasks:  getEnvBool("ATGS_CENTRAL_REQUIRE_SIGNED_TASKS", false),
		TaskRateLimitPerMin: getEnvInt("ATGS_CENTRAL_TASK_RATE_PER_MIN", 100),
		TaskRateLimitBurst:  getEnvInt("ATGS_CENTRAL_TASK_RATE_BURST", 500),
		AuditSyslogPath:     getEnv("ATGS_CENTRAL_AUDIT_SYSLOG_PATH", ""),

		// Phase 8
		AdminBootstrapEmail:    getEnv("ATGS_CENTRAL_ADMIN_EMAIL", ""),
		AdminBootstrapPassword: getEnv("ATGS_CENTRAL_ADMIN_PASSWORD", ""),
		SessionCleanupTick:     getEnvDuration("ATGS_CENTRAL_SESSION_CLEANUP_TICK", 15*time.Minute),
		BedrockPublicPortMin:   getEnvInt("ATGS_CENTRAL_BEDROCK_PUBLIC_PORT_MIN", 19132),
		BedrockPublicPortMax:   getEnvInt("ATGS_CENTRAL_BEDROCK_PUBLIC_PORT_MAX", 19231),
	}

	switch c.BackupStorageDefault {
	case "central_fs", "object_storage":
		// ok
	default:
		return nil, fmt.Errorf("ATGS_CENTRAL_BACKUP_STORAGE must be central_fs or object_storage, got %q", c.BackupStorageDefault)
	}
	if c.BackupChunkSize < 64*1024 || c.BackupChunkSize > 64*1024*1024 {
		return nil, fmt.Errorf("ATGS_CENTRAL_BACKUP_CHUNK_SIZE must be between 64KiB and 64MiB, got %d", c.BackupChunkSize)
	}
	if c.PingTimeout <= c.PingInterval {
		return nil, fmt.Errorf("ping timeout (%s) must exceed ping interval (%s)", c.PingTimeout, c.PingInterval)
	}
	if c.BedrockPublicPortMin <= 0 || c.BedrockPublicPortMax < c.BedrockPublicPortMin || c.BedrockPublicPortMax > 65535 {
		return nil, fmt.Errorf("invalid Bedrock public port range %d-%d", c.BedrockPublicPortMin, c.BedrockPublicPortMax)
	}
	return c, nil
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
	b, err := strconv.ParseBool(v)
	if err != nil {
		return def
	}
	return b
}

func getEnvInt(k string, def int) int {
	v := os.Getenv(k)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}
