package api

import (
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
)

// progenitorAuthMiddleware requires a client cert with OU=ATGS Progenitor.
// Wrapping the admin handler mounted on the keeper listener (already mTLS)
// means we just enforce the OU check and delegate to the existing handlers.
func progenitorAuthMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.VerifiedChains) == 0 || len(r.TLS.PeerCertificates) == 0 {
			writeError(w, http.StatusUnauthorized, "no_client_cert", "client certificate required")
			return
		}
		leaf := r.TLS.PeerCertificates[0]
		if !hasOU(leaf.Subject.OrganizationalUnit, "ATGS Progenitor") {
			writeError(w, http.StatusForbidden, "wrong_ou",
				"this endpoint requires a Progenitor cert (OU=ATGS Progenitor)")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ProgenitorHandler builds the routing tree Progenitor hits. These are the
// same endpoints as the admin listener's, served under /api/v1/admin/ on the
// keeper listener with mTLS+OU gating.
//
// Plus a small set of Progenitor-specific endpoints (whoami, list-all-instances,
// list-all-schedules) that the CLI-era admin listener didn't need.
func (s *Server) ProgenitorHandler() http.Handler {
	mux := http.NewServeMux()

	// --- Read-only surface ---
	mux.HandleFunc("GET /api/v1/admin/whoami", s.handleProgenitorWhoami)
	mux.HandleFunc("GET /api/v1/admin/keepers", s.handleListKeepers)
	mux.HandleFunc("GET /api/v1/admin/instances", s.handleListAllInstances)
	mux.HandleFunc("GET /api/v1/admin/keepers/{id}/instances", s.handleListInstances)
	mux.HandleFunc("GET /api/v1/admin/workspaces", s.handleListWorkspaces)
	mux.HandleFunc("POST /api/v1/admin/workspaces", s.handleCreateWorkspace)
	mux.HandleFunc("GET /api/v1/admin/workspaces/{id}/members", s.handleListWorkspaceMembers)
	mux.HandleFunc("POST /api/v1/admin/workspaces/{id}/members", s.handleUpsertWorkspaceMember)
	mux.HandleFunc("GET /api/v1/admin/tasks", s.handleListTasks)
	mux.HandleFunc("GET /api/v1/admin/tasks/{id}", s.handleGetTask)
	mux.HandleFunc("GET /api/v1/admin/audit", s.handleListAudit)

	// --- Keeper admin ---
	mux.HandleFunc("POST /api/v1/admin/enrollment-tokens", s.handleMintEnrollmentToken)
	mux.HandleFunc("POST /api/v1/admin/keepers/{id}/revoke", s.handleRevokeKeeper)
	mux.HandleFunc("POST /api/v1/admin/keepers/{id}/workspace", s.handleAssignKeeperWorkspace)

	// --- Instance lifecycle ---
	mux.HandleFunc("POST /api/v1/admin/keepers/{id}/instances", s.handleCreateInstance)
	mux.HandleFunc("POST /api/v1/admin/instances/{id}/start", s.handleStartInstance)
	mux.HandleFunc("POST /api/v1/admin/instances/{id}/stop", s.handleStopInstance)
	mux.HandleFunc("DELETE /api/v1/admin/instances/{id}", s.handleDeleteInstance)
	mux.HandleFunc("GET /api/v1/admin/instances/{id}/logs", s.handleInstanceLogs)
	mux.HandleFunc("POST /api/v1/admin/instances/{id}/console", s.handleInstanceConsoleWrite)
	mux.HandleFunc("GET /api/v1/admin/instances/{id}/files", s.handleInstanceFileList)
	mux.HandleFunc("GET /api/v1/admin/instances/{id}/file", s.handleInstanceFileRead)
	mux.HandleFunc("PUT /api/v1/admin/instances/{id}/file", s.handleInstanceFileWrite)
	mux.HandleFunc("DELETE /api/v1/admin/instances/{id}/file", s.handleInstanceFileDelete)
	mux.HandleFunc("POST /api/v1/admin/instances/{id}/file/rename", s.handleInstanceFileRename)

	// --- Phase 4 Backups ---
	if s.BackupHandlers != nil {
		mux.HandleFunc("POST /api/v1/admin/instances/{id}/backups", s.BackupHandlers.createBackup)
		mux.HandleFunc("GET /api/v1/admin/instances/{id}/backups", s.BackupHandlers.listBackups)
		mux.HandleFunc("GET /api/v1/admin/backups/{backup_id}", s.BackupHandlers.getBackup)
		mux.HandleFunc("DELETE /api/v1/admin/backups/{backup_id}", s.BackupHandlers.deleteBackup)
		mux.HandleFunc("POST /api/v1/admin/backups/{backup_id}/restore", s.BackupHandlers.restoreBackup)
		mux.HandleFunc("POST /api/v1/admin/instances/{id}/backup-schedule", s.BackupHandlers.createSchedule)
		mux.HandleFunc("GET /api/v1/admin/schedules", s.handleListAllSchedules)
	}

	return progenitorAuthMiddleware(loggingMiddleware(s.Log, mux))
}

// handleProgenitorWhoami returns identifying info about the authenticated
// Progenitor. Used by the UI to show "connected as ..." in the chrome.
func (s *Server) handleProgenitorWhoami(w http.ResponseWriter, r *http.Request) {
	leaf := r.TLS.PeerCertificates[0]
	writeJSON(w, http.StatusOK, map[string]any{
		"progenitor_id":  leaf.Subject.CommonName,
		"ou":             leaf.Subject.OrganizationalUnit,
		"cert_not_after": leaf.NotAfter,
		"server_version": s.Cfg.ServerVersion,
	})
}

// handleListAudit returns the most recent audit log entries. Optional
// ?keeper_id filter, optional ?limit (1-500, default 100).
func (s *Server) handleListAudit(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var keeperID *uuid.UUID
	if v := q.Get("keeper_id"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_keeper_id", err.Error())
			return
		}
		keeperID = &id
	}
	limit := 100
	if v := q.Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 500 {
			limit = n
		}
	}
	entries, err := s.Store.ListAudit(r.Context(), keeperID, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"entries": entries})
}

// handleListAllSchedules returns every backup schedule system-wide.
// Progenitor-only endpoint.
func (s *Server) handleListAllSchedules(w http.ResponseWriter, r *http.Request) {
	scheds, err := s.Store.ListAllSchedules(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	type schedView struct {
		ScheduleID   string     `json:"schedule_id"`
		WorkspaceID  string     `json:"workspace_id"`
		InstanceID   string     `json:"instance_id"`
		CronExpr     string     `json:"cron_expr"`
		Enabled      bool       `json:"enabled"`
		Retention    int        `json:"retention"`
		Encrypt      bool       `json:"encrypt"`
		NextRunAt    time.Time  `json:"next_run_at"`
		LastRunAt    *time.Time `json:"last_run_at,omitempty"`
		LastBackupID *string    `json:"last_backup_id,omitempty"`
	}
	out := make([]schedView, 0, len(scheds))
	for _, sc := range scheds {
		v := schedView{
			ScheduleID: sc.ScheduleID.String(),
			WorkspaceID: sc.WorkspaceID.String(),
			InstanceID: sc.InstanceID.String(),
			CronExpr:   sc.CronExpr,
			Enabled:    sc.Enabled,
			Retention:  sc.Retention,
			Encrypt:    sc.Encrypt,
			NextRunAt:  sc.NextRunAt,
			LastRunAt:  sc.LastRunAt,
		}
		if sc.LastBackupID != nil {
			idStr := sc.LastBackupID.String()
			v.LastBackupID = &idStr
		}
		out = append(out, v)
	}
	writeJSON(w, http.StatusOK, map[string]any{"schedules": out})
}
