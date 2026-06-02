package api

import (
	"encoding/json"
	"net/http"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/dispatcher"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/shared/protocol"
)

type fileWriteReq struct {
	Path          string `json:"path"`
	Content       []byte `json:"content"`
	CreateParents bool   `json:"create_parents,omitempty"`
}

type fileRenameReq struct {
	Path    string `json:"path"`
	NewPath string `json:"new_path"`
}

type fileDeleteReq struct {
	Path      string `json:"path"`
	Recursive bool   `json:"recursive,omitempty"`
}

func (s *Server) handleInstanceFileList(w http.ResponseWriter, r *http.Request) {
	instanceID, inst, ok := s.loadInstanceForFileOp(w, r)
	if !ok {
		return
	}
	result, ok := s.runFileTask(w, r, inst, &instanceID, protocol.TaskInstanceFileList, protocol.InstanceFileListPayload{
		InstanceID: instanceID.String(),
		Path:       r.URL.Query().Get("path"),
	})
	if !ok {
		return
	}
	var out protocol.InstanceFileListResult
	_ = json.Unmarshal(result.Result, &out)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleInstanceFileRead(w http.ResponseWriter, r *http.Request) {
	instanceID, inst, ok := s.loadInstanceForFileOp(w, r)
	if !ok {
		return
	}
	result, ok := s.runFileTask(w, r, inst, &instanceID, protocol.TaskInstanceFileRead, protocol.InstanceFileReadPayload{
		InstanceID: instanceID.String(),
		Path:       r.URL.Query().Get("path"),
	})
	if !ok {
		return
	}
	var out protocol.InstanceFileReadResult
	_ = json.Unmarshal(result.Result, &out)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleInstanceFileWrite(w http.ResponseWriter, r *http.Request) {
	instanceID, inst, ok := s.loadInstanceForFileOp(w, r)
	if !ok {
		return
	}
	var req fileWriteReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	result, ok := s.runFileTask(w, r, inst, &instanceID, protocol.TaskInstanceFileWrite, protocol.InstanceFileWritePayload{
		InstanceID:    instanceID.String(),
		Path:          req.Path,
		Content:       req.Content,
		CreateParents: req.CreateParents,
	})
	if !ok {
		return
	}
	var out protocol.InstanceFileWriteResult
	_ = json.Unmarshal(result.Result, &out)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleInstanceFileDelete(w http.ResponseWriter, r *http.Request) {
	instanceID, inst, ok := s.loadInstanceForFileOp(w, r)
	if !ok {
		return
	}
	var req fileDeleteReq
	if r.ContentLength > 0 {
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "decode", err.Error())
			return
		}
	} else {
		req.Path = r.URL.Query().Get("path")
	}
	result, ok := s.runFileTask(w, r, inst, &instanceID, protocol.TaskInstanceFileDelete, protocol.InstanceFileDeletePayload{
		InstanceID: instanceID.String(),
		Path:       req.Path,
		Recursive:  req.Recursive,
	})
	if !ok {
		return
	}
	var out protocol.InstanceFileDeleteResult
	_ = json.Unmarshal(result.Result, &out)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleInstanceFileRename(w http.ResponseWriter, r *http.Request) {
	instanceID, inst, ok := s.loadInstanceForFileOp(w, r)
	if !ok {
		return
	}
	var req fileRenameReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	result, ok := s.runFileTask(w, r, inst, &instanceID, protocol.TaskInstanceFileRename, protocol.InstanceFileRenamePayload{
		InstanceID: instanceID.String(),
		Path:       req.Path,
		NewPath:    req.NewPath,
	})
	if !ok {
		return
	}
	var out protocol.InstanceFileRenameResult
	_ = json.Unmarshal(result.Result, &out)
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) loadInstanceForFileOp(w http.ResponseWriter, r *http.Request) (uuid.UUID, *store.Instance, bool) {
	instanceID, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_instance_id", err.Error())
		return uuid.Nil, nil, false
	}
	inst, err := s.Store.GetInstance(r.Context(), instanceID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", err.Error())
		return uuid.Nil, nil, false
	}
	return instanceID, inst, true
}

func (s *Server) runFileTask(w http.ResponseWriter, r *http.Request, inst *store.Instance, instanceID *uuid.UUID, kind protocol.TaskKind, payload any) (*protocol.TaskResult, bool) {
	_, result, err := s.Dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
		KeeperID:    inst.KeeperID,
		InstanceID:  instanceID,
		Kind:        kind,
		Payload:     payload,
		TimeoutSecs: 30,
	}, true)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "dispatch", err.Error())
		return nil, false
	}
	if result == nil || !result.Success {
		code := "no_result"
		msg := "no result returned"
		if result != nil {
			code = result.ErrorCode
			msg = result.ErrorMessage
		}
		writeError(w, http.StatusBadGateway, code, msg)
		return nil, false
	}
	return result, true
}
