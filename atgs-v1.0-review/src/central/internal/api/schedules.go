package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/scheduler"
	"github.com/xkstudios/atgs/central/internal/store"
)

// Schedule handlers are attached to the backupHandlers struct because they
// share most of the same dependencies (store + master key awareness).

// ---- POST /api/v1/instances/{id}/backup-schedule ----

type createScheduleReq struct {
	CronExpr    string `json:"cron_expr"`
	Retention   int    `json:"retention,omitempty"`    // default 7
	StorageMode string `json:"storage_mode,omitempty"` // optional override
	Encrypt     bool   `json:"encrypt,omitempty"`
}

type createScheduleResp struct {
	ScheduleID string    `json:"schedule_id"`
	InstanceID string    `json:"instance_id"`
	CronExpr   string    `json:"cron_expr"`
	NextRunAt  time.Time `json:"next_run_at"`
	Retention  int       `json:"retention"`
	Encrypt    bool      `json:"encrypt"`
}

func (h *backupHandlers) createSchedule(w http.ResponseWriter, r *http.Request) {
	instanceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	var req createScheduleReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", err.Error())
		return
	}
	if req.CronExpr == "" {
		writeError(w, http.StatusBadRequest, "missing_cron_expr", "cron_expr is required")
		return
	}

	// Validate the cron expression by computing the first next-run.
	nextRun, err := scheduler.NextFromExpression(req.CronExpr, time.Now())
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_cron_expr", err.Error())
		return
	}

	inst, err := h.store.GetInstance(r.Context(), instanceID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "instance not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}

	var mode *store.BackupStorageMode
	if req.StorageMode != "" {
		switch req.StorageMode {
		case "central_fs":
			m := store.BackupStorageCentralFS
			mode = &m
		case "object_storage":
			m := store.BackupStorageObject
			mode = &m
		default:
			writeError(w, http.StatusBadRequest, "invalid_storage_mode", "must be central_fs or object_storage")
			return
		}
	}

	retention := req.Retention
	if retention == 0 {
		retention = 7
	}

	if req.Encrypt && h.masterKey == nil {
		writeError(w, http.StatusBadRequest, "no_master_key",
			"encrypted schedules require ATGS_CENTRAL_BACKUP_MASTER_KEY to be set")
		return
	}

	scheduleID := uuid.New()
	if err := h.store.CreateBackupSchedule(r.Context(), store.CreateBackupScheduleParams{
		ScheduleID:  scheduleID,
		WorkspaceID: inst.WorkspaceID,
		InstanceID:  instanceID,
		CronExpr:    req.CronExpr,
		Retention:   retention,
		StorageMode: mode,
		Encrypt:     req.Encrypt,
		NextRunAt:   nextRun,
	}); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}

	writeJSON(w, http.StatusCreated, createScheduleResp{
		ScheduleID: scheduleID.String(),
		InstanceID: instanceID.String(),
		CronExpr:   req.CronExpr,
		NextRunAt:  nextRun,
		Retention:  retention,
		Encrypt:    req.Encrypt,
	})
}
