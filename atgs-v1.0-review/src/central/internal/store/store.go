// Package store wraps Postgres access for Central.
//
// Conventions:
//   - All functions take a context.Context and honor cancellation.
//   - Errors are returned, not logged. Callers log.
//   - Time values are always UTC at the storage boundary.
package store

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// jsonMarshal is a small helper since we use it only for audit details.
func jsonMarshal(v any) ([]byte, error) { return json.Marshal(v) }

type Store struct {
	pool *pgxpool.Pool
}

func New(ctx context.Context, dsn string) (*Store, error) {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, fmt.Errorf("pgx pool: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("postgres ping: %w", err)
	}
	return &Store{pool: pool}, nil
}

func (s *Store) Close() { s.pool.Close() }

// ---- Keepers ----

type Keeper struct {
	ID                   uuid.UUID
	WorkspaceID          uuid.UUID
	DisplayName          string
	PublicKeyFingerprint string
	Platform             string
	Arch                 string
	Hostname             string
	AgentVersion         string
	EnrolledAt           time.Time
	CertNotAfter         time.Time
	RevokedAt            *time.Time
	LastSeenAt           *time.Time
}

type CreateKeeperParams struct {
	ID                   uuid.UUID
	WorkspaceID          uuid.UUID
	DisplayName          string
	PublicKeyFingerprint string
	Platform             string
	Arch                 string
	Hostname             string
	AgentVersion         string
	CertNotAfter         time.Time
	// Ed25519PublicKey is the raw 32-byte public key the Keeper submits at
	// enrollment for envelope signing. Phase 7. Nil during transition.
	Ed25519PublicKey []byte
	// CertSerialHex is the lowercase hex of the cert serial. Phase 7 CRL uses
	// it to revoke at TLS handshake. Empty during transition is allowed.
	CertSerialHex string
}

func (s *Store) CreateKeeper(ctx context.Context, p CreateKeeperParams) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO keepers (
			keeper_id, workspace_id, display_name, public_key_fingerprint,
			platform, arch, hostname, agent_version, cert_not_after,
			ed25519_public_key
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
	`, p.ID, p.WorkspaceID, p.DisplayName, p.PublicKeyFingerprint, p.Platform, p.Arch, p.Hostname, p.AgentVersion, p.CertNotAfter, p.Ed25519PublicKey)
	return err
}

func (s *Store) GetKeeper(ctx context.Context, id uuid.UUID) (*Keeper, error) {
	var k Keeper
	err := s.pool.QueryRow(ctx, `
		SELECT keeper_id, workspace_id, display_name, public_key_fingerprint, platform, arch,
		       hostname, agent_version, enrolled_at, cert_not_after, revoked_at, last_seen_at
		FROM keepers WHERE keeper_id = $1
	`, id).Scan(
		&k.ID, &k.WorkspaceID, &k.DisplayName, &k.PublicKeyFingerprint, &k.Platform, &k.Arch,
		&k.Hostname, &k.AgentVersion, &k.EnrolledAt, &k.CertNotAfter, &k.RevokedAt, &k.LastSeenAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &k, nil
}

func (s *Store) ListKeepers(ctx context.Context) ([]Keeper, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT keeper_id, workspace_id, display_name, public_key_fingerprint, platform, arch,
		       hostname, agent_version, enrolled_at, cert_not_after, revoked_at, last_seen_at
		FROM keepers
		ORDER BY enrolled_at DESC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Keeper
	for rows.Next() {
		var k Keeper
		if err := rows.Scan(
			&k.ID, &k.WorkspaceID, &k.DisplayName, &k.PublicKeyFingerprint, &k.Platform, &k.Arch,
			&k.Hostname, &k.AgentVersion, &k.EnrolledAt, &k.CertNotAfter, &k.RevokedAt, &k.LastSeenAt,
		); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}

func (s *Store) ListKeepersForWorkspace(ctx context.Context, workspaceID uuid.UUID) ([]Keeper, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT keeper_id, workspace_id, display_name, public_key_fingerprint, platform, arch,
		       hostname, agent_version, enrolled_at, cert_not_after, revoked_at, last_seen_at
		FROM keepers
		WHERE workspace_id = $1
		ORDER BY enrolled_at DESC
	`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Keeper
	for rows.Next() {
		var k Keeper
		if err := rows.Scan(
			&k.ID, &k.WorkspaceID, &k.DisplayName, &k.PublicKeyFingerprint, &k.Platform, &k.Arch,
			&k.Hostname, &k.AgentVersion, &k.EnrolledAt, &k.CertNotAfter, &k.RevokedAt, &k.LastSeenAt,
		); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}

func (s *Store) TouchKeeperLastSeen(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `UPDATE keepers SET last_seen_at = NOW() WHERE keeper_id = $1`, id)
	return err
}

// GetKeeperEd25519 returns the keeper's Ed25519 public key (Phase 7). Returns
// (nil, nil) if the keeper exists but has no key recorded — legitimate during
// the transition window before every keeper re-enrolls.
func (s *Store) GetKeeperEd25519(ctx context.Context, id uuid.UUID) ([]byte, error) {
	var pub []byte
	err := s.pool.QueryRow(ctx,
		`SELECT ed25519_public_key FROM keepers WHERE keeper_id = $1`, id,
	).Scan(&pub)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return pub, nil
}

// SumKeeperCommittedMemory returns the total memory_bytes summed across all
// non-deleted instances for a keeper. Used by the Phase 7 restracker.
func (s *Store) SumKeeperCommittedMemory(ctx context.Context, keeperID uuid.UUID) (uint64, error) {
	var sum *int64
	err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(SUM(memory_bytes), 0)
		FROM instances
		WHERE keeper_id = $1 AND deleted_at IS NULL
	`, keeperID).Scan(&sum)
	if err != nil {
		return 0, err
	}
	if sum == nil {
		return 0, nil
	}
	return uint64(*sum), nil
}

// KeeperResourcesSnapshot is the latest resources.report the keeper sent.
// Nil if none recorded yet.
type KeeperResourcesSnapshot struct {
	At             time.Time
	CPUCores       int
	CPUPercent     float64
	MemUsedBytes   uint64
	MemTotalBytes  uint64
	DiskUsedBytes  uint64
	DiskTotalBytes uint64
}

type UpsertKeeperResourcesSnapshotParams struct {
	KeeperID       uuid.UUID
	ReportedAt     time.Time
	CPUCores       int
	CPUPercentUsed float64
	MemTotalBytes  uint64
	MemUsedBytes   uint64
	DiskTotalBytes uint64
	DiskUsedBytes  uint64
}

func (s *Store) UpsertKeeperResourcesSnapshot(ctx context.Context, p UpsertKeeperResourcesSnapshotParams) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO keeper_resource_snapshots (
			keeper_id, reported_at, cpu_cores, cpu_percent_used,
			mem_total_bytes, mem_used_bytes, disk_total_bytes, disk_used_bytes, updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,NOW())
		ON CONFLICT (keeper_id) DO UPDATE SET
			reported_at = EXCLUDED.reported_at,
			cpu_cores = EXCLUDED.cpu_cores,
			cpu_percent_used = EXCLUDED.cpu_percent_used,
			mem_total_bytes = EXCLUDED.mem_total_bytes,
			mem_used_bytes = EXCLUDED.mem_used_bytes,
			disk_total_bytes = EXCLUDED.disk_total_bytes,
			disk_used_bytes = EXCLUDED.disk_used_bytes,
			updated_at = NOW()
	`, p.KeeperID, p.ReportedAt, p.CPUCores, p.CPUPercentUsed,
		int64(p.MemTotalBytes), int64(p.MemUsedBytes), int64(p.DiskTotalBytes), int64(p.DiskUsedBytes))
	return err
}

// LatestKeeperResourcesReport is a best-effort read of the latest resources
// snapshot Central has received from a Keeper. Missing data is not an error.
func (s *Store) LatestKeeperResourcesReport(ctx context.Context, keeperID uuid.UUID) (*KeeperResourcesSnapshot, error) {
	var snap KeeperResourcesSnapshot
	var memTotal, memUsed, diskTotal, diskUsed int64
	err := s.pool.QueryRow(ctx, `
		SELECT reported_at, cpu_cores, cpu_percent_used,
		       mem_total_bytes, mem_used_bytes, disk_total_bytes, disk_used_bytes
		FROM keeper_resource_snapshots
		WHERE keeper_id = $1
	`, keeperID).Scan(
		&snap.At, &snap.CPUCores, &snap.CPUPercent, &memTotal, &memUsed, &diskTotal, &diskUsed,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	snap.MemTotalBytes = uint64(memTotal)
	snap.MemUsedBytes = uint64(memUsed)
	snap.DiskTotalBytes = uint64(diskTotal)
	snap.DiskUsedBytes = uint64(diskUsed)
	return &snap, nil
}

// GetKeeperCertSerial returns the keeper's cert serial number as a big.Int,
// or nil if no serial is on record. Phase 7 CRL consumer.
func (s *Store) GetKeeperCertSerial(ctx context.Context, id uuid.UUID) (*big.Int, error) {
	var hexStr *string
	err := s.pool.QueryRow(ctx,
		`SELECT cert_serial_hex FROM keepers WHERE keeper_id = $1`, id,
	).Scan(&hexStr)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if hexStr == nil || *hexStr == "" {
		return nil, nil
	}
	n := new(big.Int)
	if _, ok := n.SetString(*hexStr, 16); !ok {
		return nil, fmt.Errorf("invalid cert serial hex: %s", *hexStr)
	}
	return n, nil
}

// nullableString returns nil for empty strings so Postgres stores NULL
// rather than an empty string. Used for optional text columns.
func nullableString(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func (s *Store) RevokeKeeper(ctx context.Context, id uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `UPDATE keepers SET revoked_at = NOW() WHERE keeper_id = $1 AND revoked_at IS NULL`, id)
	return err
}

// ---- Enrollment tokens ----
//
// We never store the raw token; we store sha256(token) as the primary key.
// This means a Postgres compromise doesn't hand attackers live tokens.

// HashToken returns the hex SHA-256 of a raw enrollment token.
func HashToken(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

type CreateEnrollmentTokenParams struct {
	TokenHash string
	WorkspaceID uuid.UUID
	CreatedBy string
	Note      string
	ExpiresAt time.Time
}

func (s *Store) CreateEnrollmentToken(ctx context.Context, p CreateEnrollmentTokenParams) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO enrollment_tokens (token_hash, workspace_id, created_by, note, expires_at)
		VALUES ($1,$2,$3,$4,$5)
	`, p.TokenHash, p.WorkspaceID, p.CreatedBy, p.Note, p.ExpiresAt)
	return err
}

// CompleteEnrollment atomically creates the keeper row AND consumes the
// enrollment token in a single transaction. Ordering matters: we insert the
// keeper row FIRST so the used_by_keeper foreign key in enrollment_tokens
// can reference it. If the token check fails, the whole transaction rolls
// back and no keeper row is created.
//
// Returns the same error classes as ConsumeEnrollmentToken.
func (s *Store) CompleteEnrollment(ctx context.Context, tokenHash string, keeper CreateKeeperParams) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	// Validate and lock the token row before we do any writes.
	var expiresAt time.Time
	var usedAt *time.Time
	var workspaceID uuid.UUID
	err = tx.QueryRow(ctx, `
		SELECT expires_at, used_at, workspace_id FROM enrollment_tokens WHERE token_hash = $1 FOR UPDATE
	`, tokenHash).Scan(&expiresAt, &usedAt, &workspaceID)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if usedAt != nil {
		return ErrTokenAlreadyUsed
	}
	if time.Now().After(expiresAt) {
		return ErrTokenExpired
	}

	// Insert keeper first so the FK in enrollment_tokens is satisfiable.
	if _, err := tx.Exec(ctx, `
		INSERT INTO keepers (
			keeper_id, workspace_id, display_name, public_key_fingerprint,
			platform, arch, hostname, agent_version, cert_not_after,
			ed25519_public_key, cert_serial_hex
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
	`, keeper.ID, workspaceID, keeper.DisplayName, keeper.PublicKeyFingerprint,
		keeper.Platform, keeper.Arch, keeper.Hostname, keeper.AgentVersion, keeper.CertNotAfter,
		keeper.Ed25519PublicKey, nullableString(keeper.CertSerialHex)); err != nil {
		return fmt.Errorf("insert keeper: %w", err)
	}

	// Now mark the token consumed with the FK reference.
	if _, err := tx.Exec(ctx, `
		UPDATE enrollment_tokens SET used_at = NOW(), used_by_keeper = $1 WHERE token_hash = $2
	`, keeper.ID, tokenHash); err != nil {
		return fmt.Errorf("consume token: %w", err)
	}

	return tx.Commit(ctx)
}

// ConsumeEnrollmentToken is kept for callers that only need to consume a
// token (not used in the enrollment flow; see CompleteEnrollment).
func (s *Store) ConsumeEnrollmentToken(ctx context.Context, tokenHash string, keeperID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var expiresAt time.Time
	var usedAt *time.Time
	err = tx.QueryRow(ctx, `
		SELECT expires_at, used_at FROM enrollment_tokens WHERE token_hash = $1 FOR UPDATE
	`, tokenHash).Scan(&expiresAt, &usedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if usedAt != nil {
		return ErrTokenAlreadyUsed
	}
	if time.Now().After(expiresAt) {
		return ErrTokenExpired
	}
	if _, err := tx.Exec(ctx, `
		UPDATE enrollment_tokens SET used_at = NOW(), used_by_keeper = $1 WHERE token_hash = $2
	`, keeperID, tokenHash); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ---- Sessions ----

type CreateSessionParams struct {
	SessionID  uuid.UUID
	KeeperID   uuid.UUID
	RemoteAddr string
}

func (s *Store) CreateSession(ctx context.Context, p CreateSessionParams) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO keeper_sessions (session_id, keeper_id, remote_addr)
		VALUES ($1,$2,$3)
	`, p.SessionID, p.KeeperID, p.RemoteAddr)
	return err
}

func (s *Store) EndSession(ctx context.Context, sessionID uuid.UUID, reason string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE keeper_sessions SET disconnected_at = NOW(), disconnect_reason = $2
		WHERE session_id = $1 AND disconnected_at IS NULL
	`, sessionID, reason)
	return err
}

// ---- Audit ----

type AuditEntry struct {
	Kind     string
	Actor    string
	KeeperID *uuid.UUID
	Details  map[string]any
}

// ListAudit returns the most recent N entries optionally filtered by keeper.
// Phase 7. Newest-first.
func (s *Store) ListAudit(ctx context.Context, keeperID *uuid.UUID, limit int) ([]AuditListEntry, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	var rows pgx.Rows
	var err error
	if keeperID != nil {
		rows, err = s.pool.Query(ctx, `
			SELECT id, at, kind, actor, keeper_id, details
			FROM audit_log
			WHERE keeper_id = $1
			ORDER BY at DESC LIMIT $2`, *keeperID, limit)
	} else {
		rows, err = s.pool.Query(ctx, `
			SELECT id, at, kind, actor, keeper_id, details
			FROM audit_log
			ORDER BY at DESC LIMIT $1`, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AuditListEntry
	for rows.Next() {
		var e AuditListEntry
		if err := rows.Scan(&e.ID, &e.At, &e.Kind, &e.Actor, &e.KeeperID, &e.Details); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// AuditListEntry is what List returns — same as AuditEntry plus the
// persisted fields (id, at).
type AuditListEntry struct {
	ID       int64          `json:"id"`
	At       time.Time      `json:"at"`
	Kind     string         `json:"kind"`
	Actor    string         `json:"actor"`
	KeeperID *uuid.UUID     `json:"keeper_id,omitempty"`
	Details  map[string]any `json:"details"`
}

// AuditSink receives each audit entry after it's written to the DB. Used by
// Phase 7's syslog streaming. Nil = no streaming.
type AuditSink interface {
	Write(e AuditEntry)
}

// sink is the installed sink, if any. Package-level because WriteAudit is
// a method on Store and we don't want to thread the sink through every
// call site.
var auditSink AuditSink
var auditSinkMu sync.RWMutex

// SetAuditSink installs a sink. Called once at startup.
func SetAuditSink(s AuditSink) {
	auditSinkMu.Lock()
	defer auditSinkMu.Unlock()
	auditSink = s
}

func (s *Store) WriteAudit(ctx context.Context, e AuditEntry) error {
	// Encode details as JSON for the JSONB column.
	detailsJSON, err := jsonMarshal(e.Details)
	if err != nil {
		return fmt.Errorf("marshal audit details: %w", err)
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO audit_log (kind, actor, keeper_id, details)
		VALUES ($1,$2,$3,$4)
	`, e.Kind, e.Actor, e.KeeperID, detailsJSON)
	if err != nil {
		return err
	}
	// Fire sink best-effort. Sinks are expected to be non-blocking (syslog
	// sink buffers internally).
	auditSinkMu.RLock()
	sk := auditSink
	auditSinkMu.RUnlock()
	if sk != nil {
		sk.Write(e)
	}
	return nil
}

// ---- Errors ----

var (
	ErrNotFound         = errors.New("not found")
	ErrTokenExpired     = errors.New("token expired")
	ErrTokenAlreadyUsed = errors.New("token already used")
)
