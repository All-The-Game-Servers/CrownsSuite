package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/dispatcher"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/shared/protocol"
)

// --- Create instance ---

type createInstanceReq struct {
	EggID       string            `json:"egg_id"`
	DisplayName string            `json:"display_name"`
	Hostname    string            `json:"hostname,omitempty"`
	Env         map[string]string `json:"env,omitempty"`
	MemoryBytes int64             `json:"memory_bytes"`
	CPUShares   int64             `json:"cpu_shares"`
}

type createInstanceResp struct {
	InstanceID string `json:"instance_id"`
	TaskID     string `json:"task_id"`
	Hostname   string `json:"hostname,omitempty"`
	PublicPort *int   `json:"public_port,omitempty"`
}

func (s *Server) handleCreateInstance(w http.ResponseWriter, r *http.Request) {
	keeperID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}
	var req createInstanceReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	if req.EggID == "" || req.DisplayName == "" {
		writeError(w, http.StatusBadRequest, "invalid", "egg_id and display_name are required")
		return
	}
	// Default resource caps if caller didn't set them.
	if req.MemoryBytes == 0 {
		req.MemoryBytes = 2 * 1024 * 1024 * 1024 // 2 GiB
	}
	if req.CPUShares == 0 {
		req.CPUShares = 1024 // one full core
	}

	// Verify the Keeper exists.
	keeper, err := s.Store.GetKeeper(r.Context(), keeperID)
	if err != nil {
		writeError(w, http.StatusNotFound, "keeper_not_found", err.Error())
		return
	}

	instanceID := uuid.New()

	// Persist the instance BEFORE dispatching. If dispatch fails we still
	// have a record and can retry.
	if err := s.Store.CreateInstance(r.Context(), store.CreateInstanceParams{
		InstanceID:  instanceID,
		WorkspaceID: keeper.WorkspaceID,
		KeeperID:    keeperID,
		EggID:       req.EggID,
		DisplayName: req.DisplayName,
		Env:         req.Env,
		MemoryBytes: req.MemoryBytes,
		CPUShares:   req.CPUShares,
	}); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}

	if req.Hostname != "" {
		if err := s.Store.SetInstanceHostname(r.Context(), instanceID, req.Hostname); err != nil {
			// Most common failure: hostname already in use.
			writeError(w, http.StatusConflict, "hostname_conflict", err.Error())
			// Best-effort cleanup: remove the instance row we just inserted.
			_ = s.Store.MarkInstanceDeleted(r.Context(), instanceID)
			return
		}
	}
	var publicPort *int
	if req.EggID == "minecraft-bedrock" {
		for attempt := 0; attempt < 5; attempt++ {
			port, allocErr := s.Store.AllocatePublicPort(r.Context(), s.Cfg.BedrockPublicPortMin, s.Cfg.BedrockPublicPortMax)
			if allocErr != nil {
				writeError(w, http.StatusConflict, "public_port_unavailable", allocErr.Error())
				_ = s.Store.MarkInstanceDeleted(r.Context(), instanceID)
				return
			}
			if err := s.Store.SetInstancePublicPort(r.Context(), instanceID, port); err == nil {
				publicPort = &port
				break
			}
		}
		if publicPort == nil {
			writeError(w, http.StatusConflict, "public_port_conflict", "unable to reserve bedrock public port")
			_ = s.Store.MarkInstanceDeleted(r.Context(), instanceID)
			return
		}
	}

	payload := protocol.InstanceCreatePayload{
		InstanceID:  instanceID.String(),
		EggID:       req.EggID,
		DisplayName: req.DisplayName,
		Env:         req.Env,
		ResourceLimits: protocol.ResourceLimits{
			MemoryBytes: uint64(req.MemoryBytes),
			CPUShares:   req.CPUShares,
		},
	}

	taskID, _, err := s.Dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
		KeeperID:    keeperID,
		InstanceID:  &instanceID,
		Kind:        protocol.TaskInstanceCreate,
		Payload:     payload,
		TimeoutSecs: 300, // container pull may take a while
	}, false)
	if err != nil && !errors.Is(err, dispatcher.ErrKeeperOffline) {
		writeError(w, http.StatusInternalServerError, "dispatch", err.Error())
		return
	}

	writeJSON(w, http.StatusCreated, createInstanceResp{
		InstanceID: instanceID.String(),
		TaskID:     taskID.String(),
		Hostname:   req.Hostname,
		PublicPort: publicPort,
	})
}

// --- Instance lifecycle actions ---

func (s *Server) instanceIDAction(w http.ResponseWriter, r *http.Request, kind protocol.TaskKind, timeoutSecs int) {
	instanceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}
	inst, err := s.Store.GetInstance(r.Context(), instanceID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
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

func (s *Server) handleStartInstance(w http.ResponseWriter, r *http.Request) {
	s.instanceIDAction(w, r, protocol.TaskInstanceStart, 60)
}

func (s *Server) handleStopInstance(w http.ResponseWriter, r *http.Request) {
	s.instanceIDAction(w, r, protocol.TaskInstanceStop, 90)
}

func (s *Server) handleDeleteInstance(w http.ResponseWriter, r *http.Request) {
	s.instanceIDAction(w, r, protocol.TaskInstanceDelete, 60)
}

// --- Instance logs ---

type logsReq struct {
	Lines int `json:"lines"`
}

func (s *Server) handleInstanceLogs(w http.ResponseWriter, r *http.Request) {
	instanceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}
	inst, err := s.Store.GetInstance(r.Context(), instanceID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
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

type consoleWriteReq struct {
	Input string `json:"input"`
}

type consoleWriteResp struct {
	TaskID string `json:"task_id"`
}

func (s *Server) handleInstanceConsoleWrite(w http.ResponseWriter, r *http.Request) {
	instanceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}
	inst, err := s.Store.GetInstance(r.Context(), instanceID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
		return
	}
	var req consoleWriteReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	req.Input = strings.TrimSpace(req.Input)
	if req.Input == "" {
		writeError(w, http.StatusBadRequest, "invalid", "input is required")
		return
	}
	if len(req.Input) > 512 {
		writeError(w, http.StatusBadRequest, "too_long", "console input must be 512 bytes or less")
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

// --- List instances on a Keeper ---

type instanceView struct {
	InstanceID  string    `json:"instance_id"`
	WorkspaceID string    `json:"workspace_id"`
	KeeperID    string    `json:"keeper_id"`
	EggID       string    `json:"egg_id"`
	DisplayName string    `json:"display_name"`
	State       string    `json:"state"`
	ContainerID *string   `json:"container_id,omitempty"`
	Hostname    *string   `json:"hostname,omitempty"`
	HostPort    *int      `json:"host_port,omitempty"`
	PublicPort  *int      `json:"public_port,omitempty"`
	MemoryBytes int64     `json:"memory_bytes"`
	CPUShares   int64     `json:"cpu_shares"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

func (s *Server) handleListInstances(w http.ResponseWriter, r *http.Request) {
	keeperID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}
	insts, err := s.Store.ListInstancesForKeeper(r.Context(), keeperID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"instances": toInstanceViews(insts)})
}

// handleListAllInstances returns every non-deleted instance system-wide.
// Progenitor-only endpoint; the CLI doesn't need it (uses per-keeper lists).
func (s *Server) handleListAllInstances(w http.ResponseWriter, r *http.Request) {
	insts, err := s.Store.ListAllInstances(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"instances": toInstanceViews(insts)})
}

func toInstanceViews(insts []store.Instance) []instanceView {
	out := make([]instanceView, 0, len(insts))
	for _, i := range insts {
		out = append(out, instanceView{
			InstanceID:  i.InstanceID.String(),
			WorkspaceID: i.WorkspaceID.String(),
			KeeperID:    i.KeeperID.String(),
			EggID:       i.EggID,
			DisplayName: i.DisplayName,
			State:       i.State,
			ContainerID: i.ContainerID,
			Hostname:    i.Hostname,
			HostPort:    i.HostPort,
			PublicPort:  i.PublicPort,
			MemoryBytes: i.MemoryBytes,
			CPUShares:   i.CPUShares,
			CreatedAt:   i.CreatedAt,
			UpdatedAt:   i.UpdatedAt,
		})
	}
	return out
}

// --- Task status ---

type taskView struct {
	TaskID       string     `json:"task_id"`
	KeeperID     string     `json:"keeper_id"`
	InstanceID   *string    `json:"instance_id,omitempty"`
	Kind         string     `json:"kind"`
	Status       string     `json:"status"`
	ErrorCode    *string    `json:"error_code,omitempty"`
	ErrorMessage *string    `json:"error_message,omitempty"`
	CreatedAt    time.Time  `json:"created_at"`
	DispatchedAt *time.Time `json:"dispatched_at,omitempty"`
	AckedAt      *time.Time `json:"acked_at,omitempty"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	TimeoutSecs  int        `json:"timeout_secs"`
	Result       any        `json:"result,omitempty"`
}

func (s *Server) handleGetTask(w http.ResponseWriter, r *http.Request) {
	taskID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}
	t, err := s.Store.GetTask(r.Context(), taskID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
		return
	}
	var instStr *string
	if t.InstanceID != nil {
		id := t.InstanceID.String()
		instStr = &id
	}
	view := taskView{
		TaskID:       t.TaskID.String(),
		KeeperID:     t.KeeperID.String(),
		InstanceID:   instStr,
		Kind:         t.Kind,
		Status:       t.Status,
		ErrorCode:    t.ErrorCode,
		ErrorMessage: t.ErrorMessage,
		CreatedAt:    t.CreatedAt,
		DispatchedAt: t.DispatchedAt,
		AckedAt:      t.AckedAt,
		CompletedAt:  t.CompletedAt,
		TimeoutSecs:  t.TimeoutSecs,
	}
	if len(t.Result) > 0 {
		var anyResult any
		_ = json.Unmarshal(t.Result, &anyResult)
		view.Result = anyResult
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleListTasks(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var (
		keeperID   *uuid.UUID
		instanceID *uuid.UUID
	)
	if v := q.Get("keeper_id"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_keeper_id", err.Error())
			return
		}
		keeperID = &id
	}
	if v := q.Get("instance_id"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
			return
		}
		instanceID = &id
	}
	limit := 50
	if v := q.Get("limit"); v != "" {
		var parsed int
		if _, err := fmt.Sscanf(v, "%d", &parsed); err == nil && parsed > 0 && parsed <= 200 {
			limit = parsed
		}
	}
	tasks, err := s.Store.ListRecentTasks(r.Context(), keeperID, instanceID, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	out := make([]taskView, 0, len(tasks))
	for _, t := range tasks {
		var instStr *string
		if t.InstanceID != nil {
			id := t.InstanceID.String()
			instStr = &id
		}
		view := taskView{
			TaskID:       t.TaskID.String(),
			KeeperID:     t.KeeperID.String(),
			InstanceID:   instStr,
			Kind:         t.Kind,
			Status:       t.Status,
			ErrorCode:    t.ErrorCode,
			ErrorMessage: t.ErrorMessage,
			CreatedAt:    t.CreatedAt,
			DispatchedAt: t.DispatchedAt,
			AckedAt:      t.AckedAt,
			CompletedAt:  t.CompletedAt,
			TimeoutSecs:  t.TimeoutSecs,
		}
		if len(t.Result) > 0 {
			var anyResult any
			_ = json.Unmarshal(t.Result, &anyResult)
			view.Result = anyResult
		}
		out = append(out, view)
	}
	writeJSON(w, http.StatusOK, map[string]any{"tasks": out})
}
