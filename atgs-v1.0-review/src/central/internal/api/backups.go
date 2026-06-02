package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/backupstore"
	"github.com/xkstudios/atgs/central/internal/cryptoutil"
	"github.com/xkstudios/atgs/central/internal/dispatcher"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/shared/protocol"
)

// NewBackupHandlers constructs the backup subsystem. The returned handler
// can be assigned to Server.BackupHandlers; nil assignment disables backups.
//
// masterKey may be nil; callers who want encryption enabled must provide one.
// defaultMode defaults to central_fs if empty.
// chunkSize defaults to 4 MiB if <= 0.
func NewBackupHandlers(
	st *store.Store,
	disp *dispatcher.Dispatcher,
	fsBackend *backupstore.FSBackend,
	masterKey *cryptoutil.MasterKey,
	defaultMode store.BackupStorageMode,
	chunkSize int,
	keeperURL string,
	log *slog.Logger,
) *backupHandlers {
	if defaultMode == "" {
		defaultMode = store.BackupStorageCentralFS
	}
	if chunkSize <= 0 {
		chunkSize = 4 * 1024 * 1024
	}
	return &backupHandlers{
		store:       st,
		dispatcher:  disp,
		fsBackend:   fsBackend,
		masterKey:   masterKey,
		defaultMode: defaultMode,
		chunkSize:   chunkSize,
		keeperURL:   keeperURL,
		log:         log,
	}
}

// backupHandlers bundles the dependencies for backup-related endpoints.
// Constructed once in api.Server setup.
type backupHandlers struct {
	store      *store.Store
	dispatcher backupDispatcher
	fsBackend  *backupstore.FSBackend
	masterKey  *cryptoutil.MasterKey // may be nil if unconfigured
	defaultMode store.BackupStorageMode
	chunkSize  int
	keeperURL  string // base URL for chunk upload, e.g. https://central:8443/api/v1/chunks
	log        *slog.Logger
}

// backupDispatcher is the subset of the dispatcher the backup flow needs.
// Kept as an interface to avoid pulling the full dispatcher into tests.
type backupDispatcher interface {
	SendTask(ctx context.Context, p dispatcher.SendTaskParams, waitForResult bool) (uuid.UUID, *protocol.TaskResult, error)
}

// ---- POST /api/v1/instances/{id}/backups ----

type createBackupReq struct {
	DisplayName      string `json:"display_name,omitempty"`
	StorageMode      string `json:"storage_mode,omitempty"` // "central_fs" or "object_storage"; default = Central's default
	Encrypted        bool   `json:"encrypted,omitempty"`
	StopDuringBackup bool   `json:"stop_during_backup,omitempty"`
}

type createBackupResp struct {
	BackupID    string `json:"backup_id"`
	TaskID      string `json:"task_id"`
	InstanceID  string `json:"instance_id"`
	StorageMode string `json:"storage_mode"`
	Encrypted   bool   `json:"encrypted"`
}

func (h *backupHandlers) createBackup(w http.ResponseWriter, r *http.Request) {
	instanceIDStr := r.PathValue("id")
	instanceID, err := uuid.Parse(instanceIDStr)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}

	var req createBackupReq
	if r.ContentLength > 0 {
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "bad_json", err.Error())
			return
		}
	}

	// Resolve the target instance + its keeper.
	inst, err := h.store.GetInstance(r.Context(), instanceID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "instance not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}

	storageMode := h.defaultMode
	if req.StorageMode != "" {
		switch req.StorageMode {
		case "central_fs":
			storageMode = store.BackupStorageCentralFS
		case "object_storage":
			storageMode = store.BackupStorageObject
		default:
			writeError(w, http.StatusBadRequest, "invalid_storage_mode", "must be central_fs or object_storage")
			return
		}
	}

	backupID := uuid.New()
	envJSON, _ := json.Marshal(inst.Env)

	var (
		wrappedKey   []byte
		unwrappedKey []byte
	)
	if req.Encrypted {
		if h.masterKey == nil {
			writeError(w, http.StatusBadRequest, "no_master_key",
				"encrypted backups require ATGS_CENTRAL_BACKUP_MASTER_KEY to be set")
			return
		}
		unwrappedKey, err = cryptoutil.GenerateDataKey()
		if err != nil {
			writeError(w, http.StatusInternalServerError, "keygen", err.Error())
			return
		}
		wrappedKey, err = h.masterKey.Wrap(unwrappedKey)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "wrap", err.Error())
			return
		}
	}

	if err := h.store.CreateBackup(r.Context(), store.CreateBackupParams{
		BackupID:       backupID,
		WorkspaceID:    inst.WorkspaceID,
		InstanceID:     instanceID,
		DisplayName:    req.DisplayName,
		StorageMode:    storageMode,
		Encrypted:      req.Encrypted,
		WrappedDataKey: wrappedKey,
		EggID:          inst.EggID,
		EnvJSON:        envJSON,
		MemoryBytes:    inst.MemoryBytes,
		CPUShares:      inst.CPUShares,
	}); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}

	payload := protocol.BackupCreatePayload{
		BackupID:         backupID.String(),
		InstanceID:       instanceID.String(),
		ChunkSizeBytes:   h.chunkSize,
		StopDuringBackup: req.StopDuringBackup,
		Encrypted:        req.Encrypted,
		EncryptionKey:    unwrappedKey, // nil when not encrypted
		ChunkUploadURL:   h.keeperURL,
	}
	taskID, _, err := h.dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
		KeeperID:   inst.KeeperID,
		InstanceID: &instanceID,
		Kind:       protocol.TaskBackupCreate,
		Payload:    payload,
	}, false)
	if err != nil {
		// Clean up the row; no task to run means the backup is dead on arrival.
		_ = h.store.FailBackup(r.Context(), backupID, "dispatch failed: "+err.Error())
		writeError(w, http.StatusServiceUnavailable, "dispatch", err.Error())
		return
	}

	h.log.Info("backup dispatched",
		"backup_id", backupID, "instance_id", instanceID,
		"keeper_id", inst.KeeperID, "task_id", taskID,
		"storage_mode", storageMode, "encrypted", req.Encrypted)

	writeJSON(w, http.StatusCreated, createBackupResp{
		BackupID:    backupID.String(),
		TaskID:      taskID.String(),
		InstanceID:  instanceID.String(),
		StorageMode: string(storageMode),
		Encrypted:   req.Encrypted,
	})
}

// ---- GET /api/v1/instances/{id}/backups ----

func (h *backupHandlers) listBackups(w http.ResponseWriter, r *http.Request) {
	instanceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	backups, err := h.store.ListBackupsForInstance(r.Context(), instanceID, 100)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	out := make([]backupSummary, 0, len(backups))
	for _, b := range backups {
		out = append(out, toBackupSummary(b))
	}
	writeJSON(w, http.StatusOK, map[string]any{"backups": out})
}

type backupSummary struct {
	BackupID    string     `json:"backup_id"`
	InstanceID  string     `json:"instance_id"`
	DisplayName string     `json:"display_name"`
	Status      string     `json:"status"`
	StorageMode string     `json:"storage_mode"`
	TotalBytes  int64      `json:"total_bytes"`
	ChunkCount  int        `json:"chunk_count"`
	Encrypted   bool       `json:"encrypted"`
	CreatedAt   time.Time  `json:"created_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	Error       string     `json:"error,omitempty"`
}

func toBackupSummary(b store.Backup) backupSummary {
	return backupSummary{
		BackupID:    b.BackupID.String(),
		InstanceID:  b.InstanceID.String(),
		DisplayName: b.DisplayName,
		Status:      string(b.Status),
		StorageMode: string(b.StorageMode),
		TotalBytes:  b.TotalBytes,
		ChunkCount:  b.ChunkCount,
		Encrypted:   b.Encrypted,
		CreatedAt:   b.CreatedAt,
		CompletedAt: b.CompletedAt,
		Error:       b.ErrorMessage,
	}
}

// ---- GET /api/v1/backups/{backup_id} ----

func (h *backupHandlers) getBackup(w http.ResponseWriter, r *http.Request) {
	backupID, err := uuid.Parse(r.PathValue("backup_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_backup_id", err.Error())
		return
	}
	b, err := h.store.GetBackup(r.Context(), backupID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "backup not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	// Inline the manifest as raw JSON.
	var manifest json.RawMessage
	if len(b.Manifest) > 0 {
		manifest = b.Manifest
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"backup":   toBackupSummary(*b),
		"manifest": manifest,
	})
}

// ---- DELETE /api/v1/backups/{backup_id} ----

func (h *backupHandlers) deleteBackup(w http.ResponseWriter, r *http.Request) {
	backupID, err := uuid.Parse(r.PathValue("backup_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_backup_id", err.Error())
		return
	}
	if err := h.store.DeleteBackup(r.Context(), backupID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "backup not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- POST /api/v1/backups/{backup_id}/restore ----

type restoreReq struct {
	TargetInstanceID string `json:"target_instance_id"` // may equal source instance
}

type restoreResp struct {
	BackupID         string `json:"backup_id"`
	TargetInstanceID string `json:"target_instance_id"`
	TaskID           string `json:"task_id"`
}

func (h *backupHandlers) restoreBackup(w http.ResponseWriter, r *http.Request) {
	backupID, err := uuid.Parse(r.PathValue("backup_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_backup_id", err.Error())
		return
	}
	var req restoreReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}
	targetInstanceID, err := uuid.Parse(req.TargetInstanceID)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_target_instance_id", err.Error())
		return
	}
	b, err := h.store.GetBackup(r.Context(), backupID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "backup not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	if b.Status != store.BackupStatusComplete {
		writeError(w, http.StatusConflict, "not_complete", fmt.Sprintf("backup is %s, cannot restore", b.Status))
		return
	}

	// Target instance must exist so we know which keeper owns it.
	target, err := h.store.GetInstance(r.Context(), targetInstanceID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "target_not_found", "target instance not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}

	var manifest protocol.BackupManifest
	if err := json.Unmarshal(b.Manifest, &manifest); err != nil {
		writeError(w, http.StatusInternalServerError, "manifest_parse", err.Error())
		return
	}

	var unwrappedKey []byte
	if b.Encrypted {
		if h.masterKey == nil {
			writeError(w, http.StatusInternalServerError, "no_master_key", "cannot decrypt; master key not configured")
			return
		}
		unwrappedKey, err = h.masterKey.Unwrap(b.WrappedDataKey)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "unwrap", err.Error())
			return
		}
	}

	payload := protocol.BackupRestorePayload{
		BackupID:         backupID.String(),
		TargetInstanceID: targetInstanceID.String(),
		ChunkDownloadURL: h.keeperURL,
		Manifest:         manifest,
		Encrypted:        b.Encrypted,
		EncryptionKey:    unwrappedKey,
	}
	taskID, _, err := h.dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
		KeeperID:   target.KeeperID,
		InstanceID: &targetInstanceID,
		Kind:       protocol.TaskBackupRestore,
		Payload:    payload,
	}, false)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "dispatch", err.Error())
		return
	}
	h.log.Info("restore dispatched",
		"backup_id", backupID, "target_instance_id", targetInstanceID,
		"keeper_id", target.KeeperID, "task_id", taskID)
	writeJSON(w, http.StatusCreated, restoreResp{
		BackupID:         backupID.String(),
		TargetInstanceID: targetInstanceID.String(),
		TaskID:           taskID.String(),
	})
}

// ---- Chunk ingest / egress (keeper-facing, mTLS, on the keeper listener) ----
//
// PUT  /api/v1/chunks/{sha256}  -- keeper uploads a chunk during backup.create
// HEAD /api/v1/chunks/{sha256}  -- keeper's resumable uploader: "do you have this already?"
// GET  /api/v1/chunks/{sha256}  -- keeper downloads during backup.restore

// MaxChunkUploadBytes caps a single chunk upload. Set to 2x the default
// chunk size so an operator who bumped chunk size can still use it without
// reconfiguring the server.
const MaxChunkUploadBytes = 64 * 1024 * 1024

func (h *backupHandlers) putChunk(w http.ResponseWriter, r *http.Request) {
	sha := r.PathValue("sha256")
	if err := validateSHAForAPI(sha); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_sha", err.Error())
		return
	}
	if !hasKeeperCert(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "keeper cert required")
		return
	}

	// Enforce chunk size ceiling at the HTTP layer.
	body := http.MaxBytesReader(w, r.Body, MaxChunkUploadBytes)
	defer body.Close()

	exists, err := h.store.HasChunk(r.Context(), sha)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	if exists {
		// Idempotent: drain the body and return 200.
		_, _ = io.Copy(io.Discard, body)
		w.WriteHeader(http.StatusOK)
		return
	}

	// Stream to the backend backend. Size is optional; the fs backend accepts 0.
	locator, err := h.fsBackend.Put(r.Context(), sha, body, r.ContentLength)
	if err != nil {
		// If the bytes exceed MaxBytesReader's cap, we get a *http.MaxBytesError.
		writeError(w, http.StatusInternalServerError, "put", err.Error())
		return
	}

	// Record in DB.
	if err := h.store.RecordChunk(r.Context(), store.BackupChunk{
		SHA256:        sha,
		SizeBytes:     int(r.ContentLength),
		StorageMode:   store.BackupStorageCentralFS,
		CentralFSPath: locator,
	}); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (h *backupHandlers) headChunk(w http.ResponseWriter, r *http.Request) {
	sha := r.PathValue("sha256")
	if err := validateSHAForAPI(sha); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	exists, err := h.store.HasChunk(r.Context(), sha)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	if !exists {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (h *backupHandlers) getChunk(w http.ResponseWriter, r *http.Request) {
	sha := r.PathValue("sha256")
	if err := validateSHAForAPI(sha); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_sha", err.Error())
		return
	}
	if !hasKeeperCert(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "keeper cert required")
		return
	}
	c, err := h.store.GetChunk(r.Context(), sha)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "chunk not found")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	if c.StorageMode != store.BackupStorageCentralFS {
		writeError(w, http.StatusNotImplemented, "backend_not_impl", "only central_fs chunks retrievable in phase 4")
		return
	}
	rc, err := h.fsBackend.Get(r.Context(), c.CentralFSPath)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "get", err.Error())
		return
	}
	defer rc.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", c.SizeBytes))
	_, _ = io.Copy(w, rc)
}

func validateSHAForAPI(sha string) error {
	if len(sha) != 64 {
		return fmt.Errorf("sha must be 64 hex chars, got %d", len(sha))
	}
	for _, c := range sha {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return fmt.Errorf("sha contains invalid char %q", c)
		}
	}
	return nil
}

// hasKeeperCert reports whether the request carries a valid keeper client cert.
// Used to gate /api/v1/chunks/* on mTLS. Relays and keepers both have certs
// chaining to Central's CA; this accepts either.
func hasKeeperCert(r *http.Request) bool {
	return r.TLS != nil && len(r.TLS.VerifiedChains) > 0 && len(r.TLS.PeerCertificates) > 0
}
