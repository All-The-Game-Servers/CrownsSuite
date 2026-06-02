// Package tasks implements the Keeper-side handler for inbound task
// dispatches.
//
// Flow for one task:
//  1. Receive a TaskDispatch envelope.
//  2. Send an immediate TaskAck (so Central knows we have it).
//  3. Execute the task against the Docker runtime.
//  4. Send exactly one TaskResult with success or error details.
//
// Every task runs in its own goroutine with a context derived from the
// task's timeout. If execution runs over the timeout, the context is
// cancelled and the result frame carries the timeout error code.
package tasks

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/keeper/internal/client"
	"github.com/xkstudios/atgs/keeper/internal/enroll"
	"github.com/xkstudios/atgs/keeper/internal/localstore"
	"github.com/xkstudios/atgs/keeper/internal/pause"
	"github.com/xkstudios/atgs/keeper/internal/runtimeiface"
	"github.com/xkstudios/atgs/shared/protocol"
)

// Handler implements client.MessageHandler.
type Handler struct {
	Runtime runtimeiface.Runtime
	Store   *localstore.Store
	Log     *slog.Logger

	// --- Phase 4: Backup support ---

	// Identity gives the task handler mTLS credentials for chunk upload/download
	// against Central's /api/v1/chunks endpoints.
	Identity *enroll.Identity

	// InsecureSkipVerify for TLS (dev only).
	InsecureSkipVerify bool

	// DataRoot is where instance volumes live on this keeper's host. Backup
	// walks <DataRoot>/<instance_id>/ to find the bytes to archive. Restore
	// extracts into the same path layout for the target instance.
	DataRoot string

	// --- Phase 6: Pause control ---

	// Pause gates instance.start and instance.create tasks. When Pause is
	// nil, the handler behaves as before (never pauses).
	Pause *pause.Controller
}

// Handle is called by the client read loop for each inbound TaskDispatch.
func (h *Handler) Handle(ctx context.Context, env protocol.Envelope, out client.Sender) error {
	if env.Kind != protocol.KindTaskDispatch {
		return fmt.Errorf("unexpected kind %s", env.Kind)
	}
	var dispatch protocol.TaskDispatch
	if err := decodeData(env.Data, &dispatch); err != nil {
		return fmt.Errorf("decode dispatch: %w", err)
	}

	// ACK immediately so Central moves to Running state.
	ackCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	_ = out.Send(ackCtx, protocol.Envelope{
		Version:       protocol.ProtocolVersion,
		ID:            uuid.NewString(),
		CorrelationID: dispatch.TaskID,
		Kind:          protocol.KindTaskAck,
		Data:          protocol.TaskAck{TaskID: dispatch.TaskID, AcceptedAtUnix: time.Now().Unix()},
	})
	cancel()

	// Execute in a goroutine with the task's own timeout budget, so the
	// read loop is free to handle other messages.
	go h.run(dispatch, out)
	return nil
}

func (h *Handler) run(dispatch protocol.TaskDispatch, out client.Sender) {
	timeout := time.Duration(dispatch.TimeoutSecs) * time.Second
	if timeout <= 0 {
		timeout = protocol.DefaultTaskTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	log := h.Log.With("task_id", dispatch.TaskID, "kind", dispatch.Kind)
	log.Info("executing task")

	resultData, err := h.dispatchByKind(ctx, dispatch)
	result := protocol.TaskResult{
		TaskID:          dispatch.TaskID,
		CompletedAtUnix: time.Now().Unix(),
	}
	if err != nil {
		log.Warn("task failed", "err", err)
		result.Success = false
		result.ErrorCode = classifyError(err)
		result.ErrorMessage = err.Error()
	} else {
		result.Success = true
		if resultData != nil {
			encoded, encErr := json.Marshal(resultData)
			if encErr != nil {
				log.Warn("encode result", "err", encErr)
				result.Success = false
				result.ErrorCode = "encode_result"
				result.ErrorMessage = encErr.Error()
			} else {
				result.Result = encoded
			}
		}
	}

	sendCtx, sendCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer sendCancel()
	if sendErr := out.Send(sendCtx, protocol.Envelope{
		Version:       protocol.ProtocolVersion,
		ID:            uuid.NewString(),
		CorrelationID: dispatch.TaskID,
		Kind:          protocol.KindTaskResult,
		Data:          result,
	}); sendErr != nil {
		log.Warn("send result failed", "err", sendErr)
	}
}

// dispatchByKind runs the actual work for each kind. Returns a typed result
// (to be marshalled to JSON by the caller) and an error.
func (h *Handler) dispatchByKind(ctx context.Context, d protocol.TaskDispatch) (any, error) {
	// Phase 6: if paused, defer tasks that would consume resources. Stop
	// and delete are still allowed — operator may want to free things up
	// during pause. Backups proceed (they're read-only from a running
	// workload's perspective, and the host wanted breathing room from
	// CPU, not storage).
	if h.Pause != nil && h.Pause.IsPaused() {
		switch d.Kind {
		case protocol.TaskInstanceCreate, protocol.TaskInstanceStart:
			return nil, pause.ErrKeeperPaused
		}
	}
	switch d.Kind {
	case protocol.TaskInstanceCreate:
		return h.doInstanceCreate(ctx, d.Payload)
	case protocol.TaskInstanceStart:
		return h.doInstanceLifecycle(ctx, d.Payload, "start")
	case protocol.TaskInstanceStop:
		return h.doInstanceLifecycle(ctx, d.Payload, "stop")
	case protocol.TaskInstanceDelete:
		return h.doInstanceLifecycle(ctx, d.Payload, "delete")
	case protocol.TaskInstanceLogsTail:
		return h.doInstanceLogsTail(ctx, d.Payload)
	case protocol.TaskInstanceConsoleWrite:
		return h.doInstanceConsoleWrite(ctx, d.Payload)
	case protocol.TaskInstanceFileList:
		return h.doInstanceFileList(ctx, d.Payload)
	case protocol.TaskInstanceFileRead:
		return h.doInstanceFileRead(ctx, d.Payload)
	case protocol.TaskInstanceFileWrite:
		return h.doInstanceFileWrite(ctx, d.Payload)
	case protocol.TaskInstanceFileDelete:
		return h.doInstanceFileDelete(ctx, d.Payload)
	case protocol.TaskInstanceFileRename:
		return h.doInstanceFileRename(ctx, d.Payload)
	case protocol.TaskBackupCreate:
		return h.doBackupCreate(ctx, d.Payload)
	case protocol.TaskBackupRestore:
		return h.doBackupRestore(ctx, d.Payload)
	case protocol.TaskKeeperRevoke:
		return h.doKeeperRevoke(ctx, d.Payload)
	default:
		return nil, fmt.Errorf("unknown task kind: %s", d.Kind)
	}
}

// --- instance.create ---

func (h *Handler) doInstanceCreate(ctx context.Context, rawPayload json.RawMessage) (any, error) {
	var p protocol.InstanceCreatePayload
	if err := json.Unmarshal(rawPayload, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	// Persist Keeper-side record BEFORE creating the container, so a crash
	// mid-create doesn't leave an orphan container unknown to us.
	envJSON, _ := json.Marshal(p.Env)
	if err := h.Store.UpsertInstance(ctx, localstore.UpsertInstanceParams{
		InstanceID:  p.InstanceID,
		EggID:       p.EggID,
		DisplayName: p.DisplayName,
		MemoryBytes: int64(p.ResourceLimits.MemoryBytes),
		CPUShares:   p.ResourceLimits.CPUShares,
		EnvJSON:     string(envJSON),
	}); err != nil {
		return nil, fmt.Errorf("localstore upsert: %w", err)
	}

	res, err := h.Runtime.CreateInstance(ctx, runtimeiface.CreateParams{
		InstanceID:  p.InstanceID,
		EggID:       p.EggID,
		DisplayName: p.DisplayName,
		Env:         p.Env,
		MemoryBytes: int64(p.ResourceLimits.MemoryBytes),
		CPUShares:   p.ResourceLimits.CPUShares,
	})
	if err != nil {
		_ = h.Store.SetState(ctx, p.InstanceID, "error")
		return nil, err
	}
	if err := h.Store.SetContainerID(ctx, p.InstanceID, res.ContainerID); err != nil {
		return nil, fmt.Errorf("persist container id: %w", err)
	}
	if res.HostPort > 0 {
		if err := h.Store.SetHostPort(ctx, p.InstanceID, res.HostPort); err != nil {
			return nil, fmt.Errorf("persist host port: %w", err)
		}
	}
	if err := h.Store.SetState(ctx, p.InstanceID, "created"); err != nil {
		return nil, fmt.Errorf("persist state: %w", err)
	}
	return protocol.InstanceCreateResult{
		InstanceID:  p.InstanceID,
		ContainerID: res.ContainerID,
		HostPort:    res.HostPort,
	}, nil
}

// --- instance.start / stop / delete ---

func (h *Handler) doInstanceLifecycle(ctx context.Context, rawPayload json.RawMessage, action string) (any, error) {
	var p protocol.InstanceIDPayload
	if err := json.Unmarshal(rawPayload, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	inst, err := h.Store.GetInstance(ctx, p.InstanceID)
	if err != nil {
		return nil, fmt.Errorf("unknown instance %s: %w", p.InstanceID, err)
	}
	if inst.ContainerID == "" {
		return nil, fmt.Errorf("instance %s has no container id on this keeper", p.InstanceID)
	}

	switch action {
	case "start":
		if err := h.Runtime.StartInstance(ctx, inst.ContainerID); err != nil {
			return nil, err
		}
		_ = h.Store.SetState(ctx, p.InstanceID, "running")
		// Re-detect the host port. Real Docker only populates port bindings
		// after start; our CreateInstance path no longer attempts it.
		hostPort, detectErr := h.Runtime.DetectHostPort(ctx, inst.ContainerID, inst.EggID)
		if detectErr != nil {
			// Don't fail the start just because port detection failed.
			// The instance is running; routing will be degraded until next start.
			return protocol.InstanceStartResult{InstanceID: p.InstanceID, HostPort: 0}, nil
		}
		if hostPort > 0 {
			_ = h.Store.SetHostPort(ctx, p.InstanceID, hostPort)
		}
		return protocol.InstanceStartResult{InstanceID: p.InstanceID, HostPort: hostPort}, nil
	case "stop":
		if err := h.Runtime.StopInstance(ctx, inst.ContainerID, 30*time.Second); err != nil {
			return nil, err
		}
		_ = h.Store.SetState(ctx, p.InstanceID, "stopped")
	case "delete":
		if err := h.Runtime.DeleteInstance(ctx, p.InstanceID, inst.ContainerID); err != nil {
			return nil, err
		}
		_ = h.Store.DeleteInstance(ctx, p.InstanceID)
	default:
		return nil, fmt.Errorf("unknown action %s", action)
	}
	return map[string]string{"instance_id": p.InstanceID, "action": action, "ok": "true"}, nil
}

// --- instance.logs.tail ---

func (h *Handler) doInstanceLogsTail(ctx context.Context, rawPayload json.RawMessage) (any, error) {
	var p protocol.InstanceLogsTailPayload
	if err := json.Unmarshal(rawPayload, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	inst, err := h.Store.GetInstance(ctx, p.InstanceID)
	if err != nil {
		return nil, err
	}
	if inst.ContainerID == "" {
		return protocol.InstanceLogsTailResult{InstanceID: p.InstanceID, Lines: []string{}}, nil
	}
	lines, truncated, err := h.Runtime.TailLogs(ctx, inst.ContainerID, p.Lines)
	if err != nil {
		return nil, err
	}
	return protocol.InstanceLogsTailResult{
		InstanceID: p.InstanceID,
		Lines:      lines,
		Truncated:  truncated,
	}, nil
}

func (h *Handler) doInstanceConsoleWrite(ctx context.Context, rawPayload json.RawMessage) (any, error) {
	var p protocol.InstanceConsoleWritePayload
	if err := json.Unmarshal(rawPayload, &p); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	inst, err := h.Store.GetInstance(ctx, p.InstanceID)
	if err != nil {
		return nil, err
	}
	if inst.ContainerID == "" {
		return nil, fmt.Errorf("instance %s has no container id on this keeper", p.InstanceID)
	}
	if err := h.Runtime.WriteConsole(ctx, inst.ContainerID, p.Input); err != nil {
		return nil, err
	}
	return protocol.InstanceConsoleWriteResult{
		InstanceID: p.InstanceID,
		Accepted:   true,
	}, nil
}

// --- helpers ---

func classifyError(err error) string {
	s := err.Error()
	switch {
	case strings.Contains(s, "context deadline"):
		return "timeout"
	case strings.Contains(s, "no such container"):
		return "not_found"
	case strings.Contains(s, "Cannot connect to the Docker daemon"):
		return "docker_unavailable"
	default:
		return "runtime"
	}
}

// decodeData is the same helper used elsewhere: JSON round-trip into a
// typed struct.
func decodeData(data any, out any) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}

// parseInt is kept for future use.
var _ = strconv.Itoa
