package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/dispatcher"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/shared/protocol"
)

func (s *Server) handleClientCreateInstance(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	keeperID, err := uuid.Parse(r.PathValue("keeper_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_keeper_id", err.Error())
		return
	}
	if _, ok := s.ensureKeeperWorkspaceCapability(w, r, workspaceID, keeperID, store.CapabilityLifecycle); !ok {
		return
	}
	r.SetPathValue("id", keeperID.String())
	s.handleCreateInstance(w, r)
}

func (s *Server) handleClientStartInstance(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceAction(w, r, protocol.TaskInstanceStart, 60)
}

func (s *Server) handleClientStopInstance(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceAction(w, r, protocol.TaskInstanceStop, 90)
}

func (s *Server) handleClientDeleteInstance(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceAction(w, r, protocol.TaskInstanceDelete, 60)
}

func (s *Server) clientInstanceAction(w http.ResponseWriter, r *http.Request, kind protocol.TaskKind, timeoutSecs int) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	instanceID, err := uuid.Parse(r.PathValue("instance_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	inst, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, instanceID, store.CapabilityLifecycle)
	if !ok {
		return
	}
	taskID, _, err := s.Dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
		KeeperID:    inst.KeeperID,
		InstanceID:  &instanceID,
		Kind:        kind,
		Payload:     protocol.InstanceIDPayload{InstanceID: instanceID.String()},
		TimeoutSecs: timeoutSecs,
	}, false)
	if err != nil && !errors.Is(err, dispatcher.ErrKeeperOffline) {
		writeError(w, http.StatusInternalServerError, "dispatch", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"task_id": taskID.String()})
}

func (s *Server) handleClientInstanceLogs(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	instanceID, err := uuid.Parse(r.PathValue("instance_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	inst, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, instanceID, store.CapabilityLogsRead)
	if !ok {
		return
	}
	lines := 100
	if r.URL.Query().Get("lines") != "" {
		_ = json.Unmarshal([]byte(r.URL.Query().Get("lines")), &lines)
	}
	_, result, err := s.Dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
		KeeperID:    inst.KeeperID,
		InstanceID:  &instanceID,
		Kind:        protocol.TaskInstanceLogsTail,
		Payload:     protocol.InstanceLogsTailPayload{InstanceID: instanceID.String(), Lines: lines},
		TimeoutSecs: 15,
	}, true)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "dispatch", err.Error())
		return
	}
	if result == nil || !result.Success {
		code := "no_result"
		msg := "no result returned"
		if result != nil {
			code = result.ErrorCode
			msg = result.ErrorMessage
		}
		writeError(w, http.StatusBadGateway, code, msg)
		return
	}
	var out protocol.InstanceLogsTailResult
	_ = json.Unmarshal(result.Result, &out)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleClientInstanceConsoleWrite(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	instanceID, err := uuid.Parse(r.PathValue("instance_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	inst, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, instanceID, store.CapabilityConsole)
	if !ok {
		return
	}
	var req consoleWriteReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	if len(req.Input) == 0 || len(req.Input) > 512 {
		writeError(w, http.StatusBadRequest, "invalid", "console input must be 1-512 bytes")
		return
	}
	taskID, result, err := s.Dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
		KeeperID:    inst.KeeperID,
		InstanceID:  &instanceID,
		Kind:        protocol.TaskInstanceConsoleWrite,
		Payload:     protocol.InstanceConsoleWritePayload{InstanceID: instanceID.String(), Input: req.Input},
		TimeoutSecs: 15,
	}, true)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "dispatch", err.Error())
		return
	}
	if result == nil || !result.Success {
		code := "no_result"
		msg := "no result returned"
		if result != nil {
			code = result.ErrorCode
			msg = result.ErrorMessage
		}
		writeError(w, http.StatusBadGateway, code, msg)
		return
	}
	writeJSON(w, http.StatusOK, consoleWriteResp{TaskID: taskID.String()})
}

func (s *Server) handleClientInstanceFileList(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceFileOp(w, r, store.CapabilityFilesRead, s.handleInstanceFileList)
}

func (s *Server) handleClientInstanceFileRead(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceFileOp(w, r, store.CapabilityFilesRead, s.handleInstanceFileRead)
}

func (s *Server) handleClientInstanceFileWrite(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceFileOp(w, r, store.CapabilityFilesWrite, s.handleInstanceFileWrite)
}

func (s *Server) handleClientInstanceFileDelete(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceFileOp(w, r, store.CapabilityFilesWrite, s.handleInstanceFileDelete)
}

func (s *Server) handleClientInstanceFileRename(w http.ResponseWriter, r *http.Request) {
	s.clientInstanceFileOp(w, r, store.CapabilityFilesWrite, s.handleInstanceFileRename)
}

func (s *Server) clientInstanceFileOp(w http.ResponseWriter, r *http.Request, capability string, next http.HandlerFunc) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	instanceID, err := uuid.Parse(r.PathValue("instance_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	if _, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, instanceID, capability); !ok {
		return
	}
	r.SetPathValue("id", instanceID.String())
	next(w, r)
}

func (s *Server) handleClientListBackups(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	instanceID, err := uuid.Parse(r.PathValue("instance_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	if _, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, instanceID, store.CapabilityBackups); !ok {
		return
	}
	r.SetPathValue("id", instanceID.String())
	s.BackupHandlers.listBackups(w, r)
}

func (s *Server) handleClientCreateBackup(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	instanceID, err := uuid.Parse(r.PathValue("instance_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	if _, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, instanceID, store.CapabilityBackups); !ok {
		return
	}
	r.SetPathValue("id", instanceID.String())
	s.BackupHandlers.createBackup(w, r)
}

func (s *Server) handleClientCreateSchedule(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	instanceID, err := uuid.Parse(r.PathValue("instance_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return
	}
	if _, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, instanceID, store.CapabilitySchedules); !ok {
		return
	}
	r.SetPathValue("id", instanceID.String())
	s.BackupHandlers.createSchedule(w, r)
}

func (s *Server) handleClientListSchedules(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilitySchedules) {
		return
	}
	schedules, err := s.Store.ListSchedulesForWorkspace(r.Context(), workspaceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	out := make([]map[string]any, 0, len(schedules))
	for _, sc := range schedules {
		row := map[string]any{
			"schedule_id": sc.ScheduleID.String(),
			"workspace_id": sc.WorkspaceID.String(),
			"instance_id": sc.InstanceID.String(),
			"cron_expr": sc.CronExpr,
			"enabled": sc.Enabled,
			"retention": sc.Retention,
			"encrypt": sc.Encrypt,
			"next_run_at": sc.NextRunAt,
			"last_run_at": sc.LastRunAt,
		}
		if sc.LastBackupID != nil {
			row["last_backup_id"] = sc.LastBackupID.String()
		}
		out = append(out, row)
	}
	writeJSON(w, http.StatusOK, map[string]any{"schedules": out})
}

func (s *Server) handleClientListWorkspaceBackups(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilityBackups) {
		return
	}
	backups, err := s.Store.ListBackupsForWorkspace(r.Context(), workspaceID, 100)
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

func (s *Server) handleClientRestoreBackup(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	backupID, err := uuid.Parse(r.PathValue("backup_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_backup_id", err.Error())
		return
	}
	backup, err := s.Store.GetBackup(r.Context(), backupID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
		return
	}
	if backup.WorkspaceID != workspaceID {
		writeError(w, http.StatusForbidden, "forbidden", "backup does not belong to this workspace")
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilityBackups) {
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
	_, ok := s.ensureInstanceWorkspaceCapability(w, r, workspaceID, targetInstanceID, store.CapabilityBackups)
	if !ok {
		return
	}
	bodyJSON, _ := json.Marshal(req)
	r.Body = io.NopCloser(bytes.NewReader(bodyJSON))
	r.SetPathValue("backup_id", backupID.String())
	s.BackupHandlers.restoreBackup(w, r)
}

func (s *Server) handleClientDeleteBackup(w http.ResponseWriter, r *http.Request) {
	workspaceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
		return
	}
	backupID, err := uuid.Parse(r.PathValue("backup_id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_backup_id", err.Error())
		return
	}
	backup, err := s.Store.GetBackup(r.Context(), backupID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
		return
	}
	if backup.WorkspaceID != workspaceID {
		writeError(w, http.StatusForbidden, "forbidden", "backup does not belong to this workspace")
		return
	}
	if !s.ensureWorkspaceCapability(w, r, workspaceID, store.CapabilityBackups) {
		return
	}
	r.SetPathValue("backup_id", backupID.String())
	s.BackupHandlers.deleteBackup(w, r)
}
