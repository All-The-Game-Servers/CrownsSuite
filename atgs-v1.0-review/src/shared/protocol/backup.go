// Package protocol - Phase 4 additions.
//
// Backup and restore task shapes. Shared between Central (dispatcher) and
// Keeper (task handler) so both agree on the wire format.
package protocol

import "time"

// ---- Task kinds ----

const (
	TaskBackupCreate  TaskKind = "backup.create"
	TaskBackupRestore TaskKind = "backup.restore"
)

// ---- backup.create ----

// BackupCreatePayload is Central -> Keeper. The keeper walks the instance's
// data volume, splits it into chunks, uploads each to Central's chunk ingest
// endpoint, and returns a manifest referencing the uploaded chunks.
//
// The keeper does NOT decide whether to stop the container or not; that's
// Central's choice, conveyed via StopDuringBackup. For most gameservers
// stopping briefly is safest (no mid-write inconsistency).
type BackupCreatePayload struct {
	BackupID          string            `json:"backup_id"`
	InstanceID        string            `json:"instance_id"`
	ChunkSizeBytes    int               `json:"chunk_size_bytes"` // e.g. 4194304 for 4 MiB
	StopDuringBackup  bool              `json:"stop_during_backup"`
	Encrypted         bool              `json:"encrypted"`
	// EncryptionKey is sent only when Encrypted=true. Raw 32-byte key for
	// AES-256-GCM. Central wraps + stores the key separately; this field
	// is the unwrapped form delivered just for this task. The keeper
	// does not persist it; it's used in-memory to encrypt chunks and then
	// zeroed.
	EncryptionKey     []byte            `json:"encryption_key,omitempty"`
	// ChunkUploadURL is the base URL the keeper PUTs chunks to. Central
	// builds this as "<central-keeper-url>/api/v1/chunks". The keeper
	// appends /{sha256} and does a PUT with the raw chunk body.
	ChunkUploadURL    string            `json:"chunk_upload_url"`
}

// BackupCreateResult is Keeper -> Central. Reports the manifest and byte totals.
type BackupCreateResult struct {
	BackupID    string         `json:"backup_id"`
	Manifest    BackupManifest `json:"manifest"`
	TotalBytes  int64          `json:"total_bytes"`
	ChunkCount  int            `json:"chunk_count"`
	DurationMS  int64          `json:"duration_ms"`
}

// BackupManifest describes a completed backup. Persisted in the backups.manifest
// JSONB column on Central and used at restore time to reassemble.
type BackupManifest struct {
	Chunks          []BackupChunkRef `json:"chunks"`
	TotalSize       int64            `json:"total_size"`
	ArchiveFormat   string           `json:"archive_format"` // "tar" (no compression in phase 4; content dedupe is good enough)
	Encrypted       bool             `json:"encrypted"`
	KeyFingerprint  string           `json:"key_fingerprint,omitempty"` // sha256 of the key, for audit
	CreatedAt       time.Time        `json:"created_at"`
}

// BackupChunkRef is one chunk in the manifest. Order matters; concatenating
// chunks in this order (after decrypting each, if encrypted) reproduces the
// original tar archive.
type BackupChunkRef struct {
	Seq    int    `json:"seq"`
	SHA256 string `json:"sha256"`
	Size   int    `json:"size"`
}

// ---- backup.restore ----

// BackupRestorePayload is Central -> Keeper. Keeper downloads the chunks by
// hash, reassembles the archive, and extracts into the target instance's
// data dir.
//
// Restore targets may be a brand new instance; in that case the Keeper first
// creates the container (via the standard instance.create flow driven by
// Central) and only after it exists does this task fire to populate its volume.
type BackupRestorePayload struct {
	BackupID         string            `json:"backup_id"`
	TargetInstanceID string            `json:"target_instance_id"`
	ChunkDownloadURL string            `json:"chunk_download_url"` // base URL; keeper appends /{sha256}
	Manifest         BackupManifest    `json:"manifest"`
	Encrypted        bool              `json:"encrypted"`
	EncryptionKey    []byte            `json:"encryption_key,omitempty"`
}

// BackupRestoreResult is Keeper -> Central.
type BackupRestoreResult struct {
	BackupID         string `json:"backup_id"`
	TargetInstanceID string `json:"target_instance_id"`
	BytesRestored    int64  `json:"bytes_restored"`
	DurationMS       int64  `json:"duration_ms"`
}
