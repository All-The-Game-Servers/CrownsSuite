package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// BackupStorageMode enumerates where a backup's bytes live.
type BackupStorageMode string

const (
	BackupStorageCentralFS BackupStorageMode = "central_fs"
	BackupStorageObject    BackupStorageMode = "object_storage"
)

// BackupStatus tracks lifecycle.
type BackupStatus string

const (
	BackupStatusPending   BackupStatus = "pending"
	BackupStatusUploading BackupStatus = "uploading"
	BackupStatusComplete  BackupStatus = "complete"
	BackupStatusFailed    BackupStatus = "failed"
)

// Backup is the model for a single backup record.
type Backup struct {
	BackupID         uuid.UUID
	WorkspaceID      uuid.UUID
	InstanceID       uuid.UUID
	DisplayName      string
	Status           BackupStatus
	StorageMode      BackupStorageMode
	TotalBytes       int64
	ChunkCount       int
	EggID            string
	EnvJSON          []byte
	MemoryBytes      int64
	CPUShares        int64
	Manifest         []byte // JSONB, may be null until upload starts
	Encrypted        bool
	WrappedDataKey   []byte // encryption key wrapped by Central's master key, or nil if not encrypted
	ObjectStorageURI string
	CentralFSPath    string
	CreatedAt        time.Time
	CompletedAt      *time.Time
	ErrorMessage     string
}

// CreateBackupParams are the inputs to CreateBackup.
type CreateBackupParams struct {
	BackupID       uuid.UUID
	WorkspaceID    uuid.UUID
	InstanceID     uuid.UUID
	DisplayName    string
	StorageMode    BackupStorageMode
	Encrypted      bool
	WrappedDataKey []byte // may be nil
	// Captured from the instance row so the backup is self-contained.
	EggID       string
	EnvJSON     []byte
	MemoryBytes int64
	CPUShares   int64
}

// CreateBackup inserts a pending backup row. Returns the created row.
func (s *Store) CreateBackup(ctx context.Context, p CreateBackupParams) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO backups (
			backup_id, workspace_id, instance_id, display_name, status, storage_mode,
			egg_id, env_json, memory_bytes, cpu_shares,
			encrypted, wrapped_data_key
		) VALUES ($1, $2, $3, $4, 'pending', $5, $6, $7, $8, $9, $10, $11)
	`, p.BackupID, p.WorkspaceID, p.InstanceID, p.DisplayName, p.StorageMode,
		p.EggID, p.EnvJSON, p.MemoryBytes, p.CPUShares,
		p.Encrypted, p.WrappedDataKey)
	return err
}

// GetBackup loads one backup by id.
func (s *Store) GetBackup(ctx context.Context, backupID uuid.UUID) (*Backup, error) {
	b := &Backup{}
	var completedAt *time.Time
	var manifest []byte
	err := s.pool.QueryRow(ctx, `
		SELECT backup_id, instance_id, display_name, status, storage_mode,
		       workspace_id,
		       total_bytes, chunk_count, egg_id, env_json, memory_bytes, cpu_shares,
		       manifest, encrypted, wrapped_data_key,
		       COALESCE(object_storage_uri, ''), COALESCE(central_fs_path, ''),
		       created_at, completed_at, COALESCE(error_message, '')
		FROM backups WHERE backup_id = $1
	`, backupID).Scan(
		&b.BackupID, &b.InstanceID, &b.DisplayName, &b.Status, &b.StorageMode,
		&b.WorkspaceID,
		&b.TotalBytes, &b.ChunkCount, &b.EggID, &b.EnvJSON, &b.MemoryBytes, &b.CPUShares,
		&manifest, &b.Encrypted, &b.WrappedDataKey,
		&b.ObjectStorageURI, &b.CentralFSPath,
		&b.CreatedAt, &completedAt, &b.ErrorMessage,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	b.Manifest = manifest
	b.CompletedAt = completedAt
	return b, nil
}

// ListBackupsForInstance returns backups for an instance, newest first.
func (s *Store) ListBackupsForInstance(ctx context.Context, instanceID uuid.UUID, limit int) ([]Backup, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.pool.Query(ctx, `
		SELECT backup_id, instance_id, display_name, status, storage_mode,
		       workspace_id,
		       total_bytes, chunk_count, egg_id, env_json, memory_bytes, cpu_shares,
		       encrypted, created_at, completed_at, COALESCE(error_message, '')
		FROM backups WHERE instance_id = $1
		ORDER BY created_at DESC LIMIT $2
	`, instanceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Backup
	for rows.Next() {
		var b Backup
		var completedAt *time.Time
		if err := rows.Scan(
			&b.BackupID, &b.InstanceID, &b.DisplayName, &b.Status, &b.StorageMode,
			&b.WorkspaceID,
			&b.TotalBytes, &b.ChunkCount, &b.EggID, &b.EnvJSON, &b.MemoryBytes, &b.CPUShares,
			&b.Encrypted, &b.CreatedAt, &completedAt, &b.ErrorMessage,
		); err != nil {
			return nil, err
		}
		b.CompletedAt = completedAt
		out = append(out, b)
	}
	return out, rows.Err()
}

func (s *Store) ListBackupsForWorkspace(ctx context.Context, workspaceID uuid.UUID, limit int) ([]Backup, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.pool.Query(ctx, `
		SELECT backup_id, instance_id, display_name, status, storage_mode,
		       workspace_id, total_bytes, chunk_count, egg_id, env_json, memory_bytes, cpu_shares,
		       encrypted, created_at, completed_at, COALESCE(error_message, '')
		FROM backups
		WHERE workspace_id = $1
		ORDER BY created_at DESC LIMIT $2
	`, workspaceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Backup
	for rows.Next() {
		var b Backup
		var completedAt *time.Time
		if err := rows.Scan(
			&b.BackupID, &b.InstanceID, &b.DisplayName, &b.Status, &b.StorageMode,
			&b.WorkspaceID, &b.TotalBytes, &b.ChunkCount, &b.EggID, &b.EnvJSON, &b.MemoryBytes, &b.CPUShares,
			&b.Encrypted, &b.CreatedAt, &completedAt, &b.ErrorMessage,
		); err != nil {
			return nil, err
		}
		b.CompletedAt = completedAt
		out = append(out, b)
	}
	return out, rows.Err()
}

// MarkBackupUploading transitions pending -> uploading. Idempotent.
func (s *Store) MarkBackupUploading(ctx context.Context, backupID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE backups SET status = 'uploading'
		WHERE backup_id = $1 AND status = 'pending'
	`, backupID)
	return err
}

// CompleteBackupParams finalizes a backup with its manifest.
type CompleteBackupParams struct {
	BackupID   uuid.UUID
	Manifest   []byte // JSONB
	TotalBytes int64
	ChunkCount int
}

// CompleteBackup finalizes a successful backup.
func (s *Store) CompleteBackup(ctx context.Context, p CompleteBackupParams) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	// Increment refcount for every chunk this manifest references.
	var manifest struct {
		Chunks []struct {
			SHA256 string `json:"sha256"`
		} `json:"chunks"`
	}
	if err := json.Unmarshal(p.Manifest, &manifest); err != nil {
		return fmt.Errorf("parse manifest: %w", err)
	}
	for _, c := range manifest.Chunks {
		if _, err := tx.Exec(ctx, `
			UPDATE backup_chunks SET ref_count = ref_count + 1 WHERE sha256 = $1
		`, c.SHA256); err != nil {
			return fmt.Errorf("bump refcount for %s: %w", c.SHA256, err)
		}
	}

	if _, err := tx.Exec(ctx, `
		UPDATE backups
		SET status = 'complete', manifest = $2, total_bytes = $3, chunk_count = $4,
		    completed_at = NOW()
		WHERE backup_id = $1
	`, p.BackupID, p.Manifest, p.TotalBytes, p.ChunkCount); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// FailBackup marks a backup failed with an error message.
func (s *Store) FailBackup(ctx context.Context, backupID uuid.UUID, reason string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE backups SET status = 'failed', error_message = $2, completed_at = NOW()
		WHERE backup_id = $1
	`, backupID, reason)
	return err
}

// DeleteBackup removes the backup row and decrements chunk refcounts. Chunks
// that drop to refcount 0 are NOT deleted here; a separate GC pass handles
// that so this call stays fast.
func (s *Store) DeleteBackup(ctx context.Context, backupID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var manifestBytes []byte
	err = tx.QueryRow(ctx, `SELECT manifest FROM backups WHERE backup_id = $1`, backupID).Scan(&manifestBytes)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}

	if len(manifestBytes) > 0 {
		var manifest struct {
			Chunks []struct {
				SHA256 string `json:"sha256"`
			} `json:"chunks"`
		}
		if err := json.Unmarshal(manifestBytes, &manifest); err != nil {
			return fmt.Errorf("parse manifest: %w", err)
		}
		for _, c := range manifest.Chunks {
			if _, err := tx.Exec(ctx,
				`UPDATE backup_chunks SET ref_count = GREATEST(ref_count - 1, 0) WHERE sha256 = $1`,
				c.SHA256); err != nil {
				return err
			}
		}
	}

	if _, err := tx.Exec(ctx, `DELETE FROM backups WHERE backup_id = $1`, backupID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ---- Chunk tracking ----

// BackupChunk is the row model for a stored chunk.
type BackupChunk struct {
	SHA256           string
	SizeBytes        int
	StorageMode      BackupStorageMode
	CentralFSPath    string
	ObjectStorageURI string
	FirstSeenAt      time.Time
	RefCount         int
}

// HasChunk returns true if Central already has a chunk with this hash.
// Used by the keeper's resumable uploader to skip bytes already on the server.
func (s *Store) HasChunk(ctx context.Context, sha256 string) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM backup_chunks WHERE sha256 = $1)`,
		sha256).Scan(&exists)
	return exists, err
}

// RecordChunk registers a successfully stored chunk. Called AFTER the chunk's
// bytes land in their storage backend. Idempotent on sha256.
func (s *Store) RecordChunk(ctx context.Context, c BackupChunk) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO backup_chunks (sha256, size_bytes, storage_mode, central_fs_path, object_storage_uri)
		VALUES ($1, $2, $3, NULLIF($4, ''), NULLIF($5, ''))
		ON CONFLICT (sha256) DO NOTHING
	`, c.SHA256, c.SizeBytes, c.StorageMode, c.CentralFSPath, c.ObjectStorageURI)
	return err
}

// GetChunk returns chunk metadata for downloads (restore path).
func (s *Store) GetChunk(ctx context.Context, sha256 string) (*BackupChunk, error) {
	c := &BackupChunk{}
	err := s.pool.QueryRow(ctx, `
		SELECT sha256, size_bytes, storage_mode,
		       COALESCE(central_fs_path, ''), COALESCE(object_storage_uri, ''),
		       first_seen_at, ref_count
		FROM backup_chunks WHERE sha256 = $1
	`, sha256).Scan(
		&c.SHA256, &c.SizeBytes, &c.StorageMode,
		&c.CentralFSPath, &c.ObjectStorageURI,
		&c.FirstSeenAt, &c.RefCount,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return c, err
}

// ListOrphanChunks returns chunks with ref_count = 0. Caller GC's them.
func (s *Store) ListOrphanChunks(ctx context.Context, limit int) ([]BackupChunk, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.pool.Query(ctx, `
		SELECT sha256, size_bytes, storage_mode,
		       COALESCE(central_fs_path, ''), COALESCE(object_storage_uri, '')
		FROM backup_chunks WHERE ref_count = 0 LIMIT $1
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BackupChunk
	for rows.Next() {
		var c BackupChunk
		if err := rows.Scan(&c.SHA256, &c.SizeBytes, &c.StorageMode, &c.CentralFSPath, &c.ObjectStorageURI); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// DeleteChunkRow removes a chunk row (after the underlying bytes have been GC'd).
func (s *Store) DeleteChunkRow(ctx context.Context, sha256 string) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM backup_chunks WHERE sha256 = $1 AND ref_count = 0`, sha256)
	return err
}

// ---- Schedules ----

// BackupSchedule is the model for a cron-scheduled backup.
type BackupSchedule struct {
	ScheduleID    uuid.UUID
	WorkspaceID   uuid.UUID
	InstanceID    uuid.UUID
	CronExpr      string
	Enabled       bool
	Retention     int
	StorageMode   *BackupStorageMode // nil = use Central default
	Encrypt       bool
	NextRunAt     time.Time
	LastRunAt     *time.Time
	LastBackupID  *uuid.UUID
	CreatedAt     time.Time
}

// CreateBackupScheduleParams inputs.
type CreateBackupScheduleParams struct {
	ScheduleID  uuid.UUID
	WorkspaceID uuid.UUID
	InstanceID  uuid.UUID
	CronExpr    string
	Retention   int
	StorageMode *BackupStorageMode
	Encrypt     bool
	NextRunAt   time.Time
}

// CreateBackupSchedule inserts a schedule row.
func (s *Store) CreateBackupSchedule(ctx context.Context, p CreateBackupScheduleParams) error {
	var storageMode *string
	if p.StorageMode != nil {
		v := string(*p.StorageMode)
		storageMode = &v
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO backup_schedules (
			schedule_id, workspace_id, instance_id, cron_expr, retention, storage_mode, encrypt, next_run_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
	`, p.ScheduleID, p.WorkspaceID, p.InstanceID, p.CronExpr, p.Retention, storageMode, p.Encrypt, p.NextRunAt)
	return err
}

// ListAllSchedules returns every schedule system-wide, newest first.
// Used by Progenitor's scheduler view; no filter by instance.
func (s *Store) ListAllSchedules(ctx context.Context) ([]BackupSchedule, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT schedule_id, instance_id, cron_expr, enabled, retention,
		       workspace_id,
		       storage_mode, encrypt, next_run_at, last_run_at, last_backup_id, created_at
		FROM backup_schedules
		ORDER BY created_at DESC
		LIMIT 500
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BackupSchedule
	for rows.Next() {
		var s BackupSchedule
		var storageMode *string
		if err := rows.Scan(&s.ScheduleID, &s.InstanceID, &s.CronExpr, &s.Enabled, &s.Retention,
			&s.WorkspaceID, &storageMode, &s.Encrypt, &s.NextRunAt, &s.LastRunAt, &s.LastBackupID, &s.CreatedAt); err != nil {
			return nil, err
		}
		if storageMode != nil {
			mode := BackupStorageMode(*storageMode)
			s.StorageMode = &mode
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// DueSchedules returns enabled schedules with next_run_at <= now. Caller
// runs backups for each and then calls AdvanceSchedule.
func (s *Store) DueSchedules(ctx context.Context, now time.Time) ([]BackupSchedule, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT schedule_id, instance_id, cron_expr, enabled, retention,
		       workspace_id,
		       storage_mode, encrypt, next_run_at, last_run_at, last_backup_id, created_at
		FROM backup_schedules
		WHERE enabled = TRUE AND next_run_at <= $1
	`, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BackupSchedule
	for rows.Next() {
		var s BackupSchedule
		var storageMode *string
		if err := rows.Scan(&s.ScheduleID, &s.InstanceID, &s.CronExpr, &s.Enabled, &s.Retention,
			&s.WorkspaceID, &storageMode, &s.Encrypt, &s.NextRunAt, &s.LastRunAt, &s.LastBackupID, &s.CreatedAt); err != nil {
			return nil, err
		}
		if storageMode != nil {
			mode := BackupStorageMode(*storageMode)
			s.StorageMode = &mode
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (s *Store) ListSchedulesForWorkspace(ctx context.Context, workspaceID uuid.UUID) ([]BackupSchedule, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT schedule_id, instance_id, cron_expr, enabled, retention,
		       workspace_id, storage_mode, encrypt, next_run_at, last_run_at, last_backup_id, created_at
		FROM backup_schedules
		WHERE workspace_id = $1
		ORDER BY created_at DESC
		LIMIT 500
	`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BackupSchedule
	for rows.Next() {
		var s BackupSchedule
		var storageMode *string
		if err := rows.Scan(&s.ScheduleID, &s.InstanceID, &s.CronExpr, &s.Enabled, &s.Retention,
			&s.WorkspaceID, &storageMode, &s.Encrypt, &s.NextRunAt, &s.LastRunAt, &s.LastBackupID, &s.CreatedAt); err != nil {
			return nil, err
		}
		if storageMode != nil {
			mode := BackupStorageMode(*storageMode)
			s.StorageMode = &mode
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// AdvanceSchedule updates last_run_at/next_run_at after a scheduled run fires.
func (s *Store) AdvanceSchedule(ctx context.Context, scheduleID uuid.UUID, lastRun, nextRun time.Time, lastBackupID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE backup_schedules
		SET last_run_at = $2, next_run_at = $3, last_backup_id = $4
		WHERE schedule_id = $1
	`, scheduleID, lastRun, nextRun, lastBackupID)
	return err
}

// ListOldBackupsForRetention returns backups BEYOND the retention window for
// a given instance, oldest first. Caller deletes them. "Beyond" means:
// given N = retention, keep the N most recent complete backups and return
// all others.
func (s *Store) ListOldBackupsForRetention(ctx context.Context, instanceID uuid.UUID, retention int) ([]uuid.UUID, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT backup_id FROM backups
		WHERE instance_id = $1 AND status = 'complete'
		ORDER BY completed_at DESC
		OFFSET $2
	`, instanceID, retention)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}
