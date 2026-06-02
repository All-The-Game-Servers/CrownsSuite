// Package dispatcher routes tasks from Central's storage layer out to the
// connected Keeper over the control channel, and feeds replies back into
// storage.
//
// There is one Dispatcher per Central process. It is a small state machine
// that holds the map of in-flight tasks (task_id -> pending) so replies
// arriving on the control channel can be correlated.
//
// Concurrency: the dispatcher is goroutine-safe. SendTask can be called
// from any request handler; HandleReply is invoked from the wsmux read loop.
package dispatcher

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/ratelimit"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/central/internal/wsmux"
	"github.com/xkstudios/atgs/shared/protocol"
)

// Dispatcher is the Central-side task router.
type Dispatcher struct {
	hub       *wsmux.Hub
	store     *store.Store
	publisher RoutingPublisher
	log       *slog.Logger

	// Phase 7: per-keeper rate limiter. Optional; nil means no limiting.
	limiter *ratelimit.Limiter

	mu       sync.Mutex
	inflight map[uuid.UUID]*pending
}

// RoutingPublisher is implemented by *routing.Publisher. Kept as an interface
// here to avoid an import cycle between dispatcher and routing.
type RoutingPublisher interface {
	Publish(event store.RoutingEvent)
}

// pending tracks one task that has been dispatched and is awaiting a reply.
type pending struct {
	taskID   uuid.UUID
	keeperID uuid.UUID
	sessionID uuid.UUID
	deadline time.Time
	// resultCh is closed when a TaskResult arrives. Nil means no waiter.
	resultCh chan protocol.TaskResult
}

func New(hub *wsmux.Hub, st *store.Store, publisher RoutingPublisher, log *slog.Logger) *Dispatcher {
	return &Dispatcher{
		hub:       hub,
		store:     st,
		publisher: publisher,
		log:       log,
		inflight:  make(map[uuid.UUID]*pending),
	}
}

// SetLimiter installs a per-keeper rate limiter. Call once at startup.
// Nil disables limiting (default). Phase 7.
func (d *Dispatcher) SetLimiter(l *ratelimit.Limiter) {
	d.limiter = l
}

// ErrRateLimited is returned by SendTask when the keeper's token bucket
// is empty. Callers (API handlers) translate this to HTTP 429.
var ErrRateLimited = errors.New("keeper rate limit exceeded")

// SendTask persists the task and attempts immediate dispatch. If the Keeper
// is offline, the task remains queued and will be dispatched when the Keeper
// reconnects (via FlushQueued).
//
// Returns the task ID. If waitForResult is true, blocks until the Keeper
// replies with a TaskResult or ctx expires. If false, returns immediately
// after persistence; callers must poll /api/v1/tasks/{id} for status.
func (d *Dispatcher) SendTask(ctx context.Context, p SendTaskParams, waitForResult bool) (uuid.UUID, *protocol.TaskResult, error) {
	if p.TaskID == uuid.Nil {
		p.TaskID = uuid.New()
	}
	if p.TimeoutSecs == 0 {
		p.TimeoutSecs = int(protocol.DefaultTaskTimeout / time.Second)
	}

	// Phase 7: token-bucket rate limit per keeper. Consumes a token on each
	// call; if empty, reject immediately (callers retry later).
	if d.limiter != nil && !d.limiter.Allow(p.KeeperID) {
		d.log.Warn("task dispatch rate limit hit",
			"keeper_id", p.KeeperID, "kind", p.Kind)
		return uuid.Nil, nil, ErrRateLimited
	}

	payloadBytes, err := json.Marshal(p.Payload)
	if err != nil {
		return uuid.Nil, nil, fmt.Errorf("marshal payload: %w", err)
	}

	// Persist the task first. If dispatch fails we have a durable record.
	if err := d.store.CreateTask(ctx, store.CreateTaskParams{
		TaskID:      p.TaskID,
		KeeperID:    p.KeeperID,
		InstanceID:  p.InstanceID,
		Kind:        string(p.Kind),
		Payload:     payloadBytes,
		TimeoutSecs: p.TimeoutSecs,
	}); err != nil {
		return uuid.Nil, nil, fmt.Errorf("create task: %w", err)
	}

	// Register pending entry BEFORE sending, so a very fast reply doesn't
	// arrive before we're ready to correlate it.
	var resultCh chan protocol.TaskResult
	if waitForResult {
		resultCh = make(chan protocol.TaskResult, 1)
	}
	d.mu.Lock()
	d.inflight[p.TaskID] = &pending{
		taskID:   p.TaskID,
		keeperID: p.KeeperID,
		deadline: time.Now().Add(time.Duration(p.TimeoutSecs) * time.Second),
		resultCh: resultCh,
	}
	d.mu.Unlock()

	// Try to send now. If Keeper is offline, leave queued.
	dispatched, sendErr := d.trySend(ctx, p.TaskID, p.KeeperID, p.Kind, payloadBytes, p.TimeoutSecs)
	if !dispatched {
		d.log.Info("task queued for offline keeper", "task_id", p.TaskID, "keeper_id", p.KeeperID, "kind", p.Kind)
		if !waitForResult {
			return p.TaskID, nil, nil
		}
		// Waiting for a result on an offline keeper would just be a wait
		// until timeout. Return a stub error so callers don't hang.
		d.removeInflight(p.TaskID)
		return p.TaskID, nil, ErrKeeperOffline
	}
	if sendErr != nil {
		d.removeInflight(p.TaskID)
		return p.TaskID, nil, fmt.Errorf("dispatch: %w", sendErr)
	}

	if !waitForResult {
		return p.TaskID, nil, nil
	}

	// Wait for the result or timeout.
	select {
	case res := <-resultCh:
		return p.TaskID, &res, nil
	case <-time.After(time.Duration(p.TimeoutSecs) * time.Second):
		d.removeInflight(p.TaskID)
		_ = d.store.CompleteTask(context.Background(), store.CompleteTaskParams{
			TaskID:       p.TaskID,
			Success:      false,
			ErrorCode:    "timeout",
			ErrorMessage: fmt.Sprintf("no result within %ds", p.TimeoutSecs),
		})
		return p.TaskID, nil, ErrTaskTimeout
	case <-ctx.Done():
		d.removeInflight(p.TaskID)
		return p.TaskID, nil, ctx.Err()
	}
}

// SendTaskParams bundles the arguments to SendTask.
type SendTaskParams struct {
	TaskID      uuid.UUID        // optional; generated if zero
	KeeperID    uuid.UUID
	InstanceID  *uuid.UUID       // optional, for instance-level tasks
	Kind        protocol.TaskKind
	Payload     any              // will be json.Marshal'd
	TimeoutSecs int              // defaults to DefaultTaskTimeout if zero
}

// trySend dispatches a task to a connected Keeper. Returns (false, nil) if
// the Keeper is offline (the normal "queue for later" path). Returns
// (true, nil) on successful send. Returns (false, err) on a real failure.
func (d *Dispatcher) trySend(ctx context.Context, taskID, keeperID uuid.UUID, kind protocol.TaskKind, payload json.RawMessage, timeoutSecs int) (bool, error) {
	dispatch := protocol.TaskDispatch{
		TaskID:           taskID.String(),
		Kind:             kind,
		Payload:          payload,
		DispatchedAtUnix: time.Now().Unix(),
		TimeoutSecs:      timeoutSecs,
		// Signature intentionally empty in Phase 2.
	}
	env := protocol.Envelope{
		Version: protocol.ProtocolVersion,
		ID:      taskID.String(), // task_id == envelope id for correlation
		Kind:    protocol.KindTaskDispatch,
		Data:    dispatch,
	}
	sent, err := d.hub.SendTo(ctx, keeperID, env)
	if !sent {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if err := d.store.MarkTaskDispatched(ctx, taskID); err != nil {
		d.log.Warn("mark task dispatched failed", "task_id", taskID, "err", err)
	}
	return true, nil
}

// HandleReply routes an inbound task-related envelope from a Keeper.
// Called by the wsmux handler for task.ack, task.progress, task.result kinds.
func (d *Dispatcher) HandleReply(ctx context.Context, keeperID uuid.UUID, sessionID uuid.UUID, env protocol.Envelope) {
	switch env.Kind {
	case protocol.KindTaskAck:
		var ack protocol.TaskAck
		if err := decodeData(env.Data, &ack); err != nil {
			d.log.Warn("decode task ack", "err", err)
			return
		}
		taskID, err := uuid.Parse(ack.TaskID)
		if err != nil {
			return
		}
		if !d.replyMatches(taskID, keeperID, sessionID) {
			d.log.Warn("reject task ack from unexpected keeper/session", "task_id", taskID, "keeper_id", keeperID, "session_id", sessionID)
			return
		}
		if err := d.store.MarkTaskAcked(ctx, taskID); err != nil {
			d.log.Warn("mark task acked", "task_id", taskID, "err", err)
		}
	case protocol.KindTaskProgress:
		// Phase 2: log only. Could be exposed via SSE later.
		var prog protocol.TaskProgress
		_ = decodeData(env.Data, &prog)
		taskID, err := uuid.Parse(prog.TaskID)
		if err != nil || !d.replyMatches(taskID, keeperID, sessionID) {
			return
		}
		d.log.Debug("task progress", "task_id", prog.TaskID, "percent", prog.Percent, "message", prog.Message)
	case protocol.KindTaskResult:
		var res protocol.TaskResult
		if err := decodeData(env.Data, &res); err != nil {
			d.log.Warn("decode task result", "err", err)
			return
		}
		taskID, err := uuid.Parse(res.TaskID)
		if err != nil {
			return
		}
		if !d.replyMatches(taskID, keeperID, sessionID) {
			d.log.Warn("reject task result from unexpected keeper/session", "task_id", taskID, "keeper_id", keeperID, "session_id", sessionID)
			return
		}
		if err := d.store.CompleteTask(ctx, store.CompleteTaskParams{
			TaskID:       taskID,
			Success:      res.Success,
			Result:       res.Result,
			ErrorCode:    res.ErrorCode,
			ErrorMessage: res.ErrorMessage,
		}); err != nil {
			d.log.Warn("complete task", "task_id", taskID, "err", err)
		}
		// Apply side effects based on what kind of task finished.
		if res.Success {
			d.applyInstanceStateFromResult(ctx, taskID, res.Result)
		}
		// Wake any waiter.
		d.mu.Lock()
		p, ok := d.inflight[taskID]
		if ok {
			delete(d.inflight, taskID)
		}
		d.mu.Unlock()
		if ok && p.resultCh != nil {
			p.resultCh <- res
		}
	}
}

// applyInstanceStateFromResult looks up the task and, depending on kind,
// updates the instance row. This is how Central learns container IDs and
// keeps instance.state in sync with lifecycle events.
func (d *Dispatcher) applyInstanceStateFromResult(ctx context.Context, taskID uuid.UUID, resultRaw json.RawMessage) {
	task, err := d.store.GetTask(ctx, taskID)
	if err != nil || task.InstanceID == nil {
		return
	}
	switch protocol.TaskKind(task.Kind) {
	case protocol.TaskInstanceCreate:
		var out protocol.InstanceCreateResult
		if err := json.Unmarshal(resultRaw, &out); err == nil && out.ContainerID != "" {
			_ = d.store.SetInstanceContainerID(ctx, *task.InstanceID, out.ContainerID)
			_ = d.store.SetInstanceState(ctx, *task.InstanceID, "created")
			if out.HostPort > 0 {
				_ = d.store.SetInstanceHostPort(ctx, *task.InstanceID, out.HostPort)
			}
			d.publishRouteUpsert(ctx, task, out.HostPort)
		}
	case protocol.TaskInstanceStart:
		_ = d.store.SetInstanceState(ctx, *task.InstanceID, "running")
		// Against real Docker, the host port isn't populated until after
		// ContainerStart. The keeper detects it post-start and reports here.
		// Publish a routing upsert if we now have both hostname and port.
		var startOut protocol.InstanceStartResult
		if err := json.Unmarshal(resultRaw, &startOut); err == nil && startOut.HostPort > 0 {
			_ = d.store.SetInstanceHostPort(ctx, *task.InstanceID, startOut.HostPort)
			d.publishRouteUpsert(ctx, task, startOut.HostPort)
		}
	case protocol.TaskInstanceStop:
		_ = d.store.SetInstanceState(ctx, *task.InstanceID, "stopped")
	case protocol.TaskInstanceDelete:
		// Grab the hostname BEFORE we mark the row deleted; once it's flagged
		// deleted, future instances can reuse the hostname.
		hostname, publicPort, _ := d.store.GetInstanceRoute(ctx, *task.InstanceID)
		_ = d.store.MarkInstanceDeleted(ctx, *task.InstanceID)
		if hostname != "" || publicPort > 0 {
			routeKind, protocolName := "java_hostname", "tcp"
			if publicPort > 0 {
				routeKind, protocolName = "bedrock_udp", "udp"
			}
			version, err := d.store.AppendRoutingDelete(ctx, routeKind, hostname, publicPort, protocolName)
			if err != nil {
				d.log.Warn("routing delete failed", "hostname", hostname, "public_port", publicPort, "err", err)
			} else if d.publisher != nil {
				d.publisher.Publish(store.RoutingEvent{
					Version:    version,
					At:         time.Now().UTC(),
					EventType:  "delete",
					RouteKind:  routeKind,
					Protocol:   protocolName,
					Hostname:   hostname,
					PublicPort: publicPort,
				})
				d.log.Info("routing delete published", "route_kind", routeKind, "hostname", hostname, "public_port", publicPort, "version", version)
			}
		}
	case protocol.TaskBackupCreate:
		// Parse the keeper's manifest and finalize the backup row.
		var out protocol.BackupCreateResult
		if err := json.Unmarshal(resultRaw, &out); err != nil {
			d.log.Warn("backup.create result parse failed", "task_id", task.TaskID, "err", err)
			break
		}
		backupUUID, err := uuid.Parse(out.BackupID)
		if err != nil {
			d.log.Warn("backup.create bad backup_id", "task_id", task.TaskID, "err", err)
			break
		}
		manifestJSON, err := json.Marshal(out.Manifest)
		if err != nil {
			d.log.Warn("backup.create marshal manifest", "err", err)
			break
		}
		if err := d.store.CompleteBackup(ctx, store.CompleteBackupParams{
			BackupID:   backupUUID,
			Manifest:   manifestJSON,
			TotalBytes: out.TotalBytes,
			ChunkCount: out.ChunkCount,
		}); err != nil {
			d.log.Warn("backup.create CompleteBackup failed", "backup_id", backupUUID, "err", err)
			_ = d.store.FailBackup(ctx, backupUUID, err.Error())
		} else {
			d.log.Info("backup completed",
				"backup_id", backupUUID,
				"chunks", out.ChunkCount,
				"bytes", out.TotalBytes,
				"duration_ms", out.DurationMS)
		}
	case protocol.TaskBackupRestore:
		var out protocol.BackupRestoreResult
		if err := json.Unmarshal(resultRaw, &out); err != nil {
			d.log.Warn("backup.restore result parse failed", "task_id", task.TaskID, "err", err)
			break
		}
		d.log.Info("restore completed",
			"backup_id", out.BackupID,
			"target_instance_id", out.TargetInstanceID,
			"bytes", out.BytesRestored,
			"duration_ms", out.DurationMS)
	}
}

func (d *Dispatcher) publishRouteUpsert(ctx context.Context, task *store.Task, hostPort int) {
	if task == nil || task.InstanceID == nil || hostPort <= 0 {
		return
	}
	hostname, publicPort, err := d.store.GetInstanceRoute(ctx, *task.InstanceID)
	if err != nil {
		return
	}
	routeKind := "java_hostname"
	protocolName := "tcp"
	if publicPort > 0 {
		routeKind = "bedrock_udp"
		protocolName = "udp"
	} else if hostname == "" {
		return
	}
	version, err := d.store.AppendRoutingUpsert(ctx, routeKind, hostname, publicPort, protocolName, *task.InstanceID, task.KeeperID, hostPort)
	if err != nil {
		d.log.Warn("routing upsert failed", "route_kind", routeKind, "hostname", hostname, "public_port", publicPort, "err", err)
		return
	}
	if d.publisher != nil {
		d.publisher.Publish(store.RoutingEvent{
			Version:    version,
			At:         time.Now().UTC(),
			EventType:  "upsert",
			RouteKind:  routeKind,
			Protocol:   protocolName,
			Hostname:   hostname,
			PublicPort: publicPort,
			InstanceID: *task.InstanceID,
			KeeperID:   task.KeeperID,
			HostPort:   hostPort,
		})
	}
	d.log.Info("routing upsert published", "route_kind", routeKind, "hostname", hostname, "public_port", publicPort, "version", version, "host_port", hostPort)
}

// FlushQueued is called when a Keeper reconnects. It dispatches any tasks
// that were queued while the Keeper was offline.
func (d *Dispatcher) FlushQueued(ctx context.Context, keeperID uuid.UUID) {
	tasks, err := d.store.ListQueuedTasksForKeeper(ctx, keeperID)
	if err != nil {
		d.log.Warn("list queued tasks failed", "keeper_id", keeperID, "err", err)
		return
	}
	if len(tasks) == 0 {
		return
	}
	d.log.Info("flushing queued tasks", "keeper_id", keeperID, "count", len(tasks))
	for _, t := range tasks {
		if _, err := d.trySend(ctx, t.TaskID, t.KeeperID, protocol.TaskKind(t.Kind), t.Payload, t.TimeoutSecs); err != nil {
			d.log.Warn("flush dispatch failed", "task_id", t.TaskID, "err", err)
		}
	}
}

func (d *Dispatcher) removeInflight(taskID uuid.UUID) {
	d.mu.Lock()
	delete(d.inflight, taskID)
	d.mu.Unlock()
}

func (d *Dispatcher) replyMatches(taskID uuid.UUID, keeperID uuid.UUID, sessionID uuid.UUID) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	p, ok := d.inflight[taskID]
	if !ok {
		return false
	}
	if p.keeperID != keeperID {
		return false
	}
	if p.sessionID == uuid.Nil {
		p.sessionID = sessionID
		return true
	}
	return p.sessionID == sessionID
}

// --- Errors ---

var (
	ErrKeeperOffline = errors.New("keeper is offline")
	ErrTaskTimeout   = errors.New("task timed out")
)

// decodeData round-trips a JSON map into a typed struct.
func decodeData(data any, out any) error {
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, out)
}
