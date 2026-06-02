package store

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// --- Instances ---

type Instance struct {
	InstanceID   uuid.UUID
	WorkspaceID  uuid.UUID
	KeeperID     uuid.UUID
	EggID        string
	DisplayName  string
	State        string
	ContainerID  *string
	Hostname     *string
	HostPort     *int
	PublicPort   *int
	MemoryBytes  int64
	CPUShares    int64
	Env          map[string]string
	PortMappings json.RawMessage
	CreatedAt    time.Time
	UpdatedAt    time.Time
	DeletedAt    *time.Time
}

type CreateInstanceParams struct {
	InstanceID   uuid.UUID
	WorkspaceID  uuid.UUID
	KeeperID     uuid.UUID
	EggID        string
	DisplayName  string
	MemoryBytes  int64
	CPUShares    int64
	Env          map[string]string
	PortMappings json.RawMessage // JSON array
}

func (s *Store) CreateInstance(ctx context.Context, p CreateInstanceParams) error {
	envJSON, err := json.Marshal(p.Env)
	if err != nil {
		return err
	}
	if len(p.PortMappings) == 0 {
		p.PortMappings = []byte("[]")
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO instances (
			instance_id, workspace_id, keeper_id, egg_id, display_name,
			memory_bytes, cpu_shares, env, port_mappings
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
	`, p.InstanceID, p.WorkspaceID, p.KeeperID, p.EggID, p.DisplayName,
		p.MemoryBytes, p.CPUShares, envJSON, p.PortMappings)
	return err
}

func (s *Store) SetInstanceContainerID(ctx context.Context, instanceID uuid.UUID, containerID string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE instances SET container_id = $2, updated_at = NOW() WHERE instance_id = $1
	`, instanceID, containerID)
	return err
}

func (s *Store) SetInstanceState(ctx context.Context, instanceID uuid.UUID, state string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE instances SET state = $2, updated_at = NOW() WHERE instance_id = $1
	`, instanceID, state)
	return err
}

func (s *Store) MarkInstanceDeleted(ctx context.Context, instanceID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE instances SET state = 'deleted', deleted_at = NOW(), updated_at = NOW()
		WHERE instance_id = $1 AND deleted_at IS NULL
	`, instanceID)
	return err
}

func (s *Store) GetInstance(ctx context.Context, id uuid.UUID) (*Instance, error) {
	var inst Instance
	var envJSON []byte
	err := s.pool.QueryRow(ctx, `
		SELECT instance_id, workspace_id, keeper_id, egg_id, display_name, state, container_id,
		       hostname, host_port, public_port,
		       memory_bytes, cpu_shares, env, port_mappings,
		       created_at, updated_at, deleted_at
		FROM instances WHERE instance_id = $1
	`, id).Scan(
		&inst.InstanceID, &inst.WorkspaceID, &inst.KeeperID, &inst.EggID, &inst.DisplayName,
		&inst.State, &inst.ContainerID, &inst.Hostname, &inst.HostPort, &inst.PublicPort, &inst.MemoryBytes, &inst.CPUShares,
		&envJSON, &inst.PortMappings,
		&inst.CreatedAt, &inst.UpdatedAt, &inst.DeletedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if len(envJSON) > 0 {
		_ = json.Unmarshal(envJSON, &inst.Env)
	}
	return &inst, nil
}

func (s *Store) ListInstancesForKeeper(ctx context.Context, keeperID uuid.UUID) ([]Instance, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT instance_id, workspace_id, keeper_id, egg_id, display_name, state, container_id,
		       hostname, host_port, public_port,
		       memory_bytes, cpu_shares, env, port_mappings,
		       created_at, updated_at, deleted_at
		FROM instances
		WHERE keeper_id = $1 AND deleted_at IS NULL
		ORDER BY created_at DESC
	`, keeperID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanInstances(rows)
}

func (s *Store) ListInstancesForWorkspace(ctx context.Context, workspaceID uuid.UUID) ([]Instance, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT instance_id, workspace_id, keeper_id, egg_id, display_name, state, container_id,
		       hostname, host_port, public_port,
		       memory_bytes, cpu_shares, env, port_mappings,
		       created_at, updated_at, deleted_at
		FROM instances
		WHERE workspace_id = $1 AND deleted_at IS NULL
		ORDER BY created_at DESC
	`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanInstances(rows)
}

// ListAllInstances returns every non-deleted instance across all keepers.
// Progenitor uses this for the global instance view. Capped at 500 to keep
// the payload bounded; Phase 6 would add pagination if anyone ever has more.
func (s *Store) ListAllInstances(ctx context.Context) ([]Instance, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT instance_id, workspace_id, keeper_id, egg_id, display_name, state, container_id,
		       hostname, host_port, public_port,
		       memory_bytes, cpu_shares, env, port_mappings,
		       created_at, updated_at, deleted_at
		FROM instances
		WHERE deleted_at IS NULL
		ORDER BY created_at DESC
		LIMIT 500
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanInstances(rows)
}

// scanInstances consolidates row scanning used by both listers. A helper
// rather than a method because pgx.Rows is a concrete type the caller owns.
func scanInstances(rows pgx.Rows) ([]Instance, error) {
	var out []Instance
	for rows.Next() {
		var inst Instance
		var envJSON []byte
		if err := rows.Scan(
			&inst.InstanceID, &inst.WorkspaceID, &inst.KeeperID, &inst.EggID, &inst.DisplayName,
			&inst.State, &inst.ContainerID, &inst.Hostname, &inst.HostPort, &inst.PublicPort, &inst.MemoryBytes, &inst.CPUShares,
			&envJSON, &inst.PortMappings,
			&inst.CreatedAt, &inst.UpdatedAt, &inst.DeletedAt,
		); err != nil {
			return nil, err
		}
		if len(envJSON) > 0 {
			_ = json.Unmarshal(envJSON, &inst.Env)
		}
		out = append(out, inst)
	}
	return out, rows.Err()
}

// --- Tasks ---

type Task struct {
	TaskID       uuid.UUID
	KeeperID     uuid.UUID
	InstanceID   *uuid.UUID
	Kind         string
	Status       string
	Payload      json.RawMessage
	Result       json.RawMessage
	ErrorCode    *string
	ErrorMessage *string
	CreatedAt    time.Time
	DispatchedAt *time.Time
	AckedAt      *time.Time
	CompletedAt  *time.Time
	TimeoutSecs  int
}

type CreateTaskParams struct {
	TaskID      uuid.UUID
	KeeperID    uuid.UUID
	InstanceID  *uuid.UUID
	Kind        string
	Payload     json.RawMessage
	TimeoutSecs int
}

func (s *Store) CreateTask(ctx context.Context, p CreateTaskParams) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO tasks (task_id, keeper_id, instance_id, kind, payload, timeout_secs)
		VALUES ($1,$2,$3,$4,$5,$6)
	`, p.TaskID, p.KeeperID, p.InstanceID, p.Kind, p.Payload, p.TimeoutSecs)
	return err
}

func (s *Store) MarkTaskDispatched(ctx context.Context, taskID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE tasks SET status = 'dispatched', dispatched_at = NOW() WHERE task_id = $1 AND status = 'queued'
	`, taskID)
	return err
}

func (s *Store) MarkTaskAcked(ctx context.Context, taskID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE tasks SET status = 'running', acked_at = NOW()
		WHERE task_id = $1 AND status IN ('dispatched', 'queued')
	`, taskID)
	return err
}

type CompleteTaskParams struct {
	TaskID       uuid.UUID
	Success      bool
	Result       json.RawMessage
	ErrorCode    string
	ErrorMessage string
}

func (s *Store) CompleteTask(ctx context.Context, p CompleteTaskParams) error {
	status := "succeeded"
	if !p.Success {
		status = "failed"
	}
	var errCode, errMsg *string
	if p.ErrorCode != "" {
		errCode = &p.ErrorCode
	}
	if p.ErrorMessage != "" {
		errMsg = &p.ErrorMessage
	}
	_, err := s.pool.Exec(ctx, `
		UPDATE tasks SET status = $2, result = $3, error_code = $4, error_message = $5, completed_at = NOW()
		WHERE task_id = $1 AND status IN ('queued', 'dispatched', 'running')
	`, p.TaskID, status, p.Result, errCode, errMsg)
	return err
}

func (s *Store) GetTask(ctx context.Context, id uuid.UUID) (*Task, error) {
	var t Task
	err := s.pool.QueryRow(ctx, `
		SELECT task_id, keeper_id, instance_id, kind, status, payload, result,
		       error_code, error_message, created_at, dispatched_at, acked_at, completed_at, timeout_secs
		FROM tasks WHERE task_id = $1
	`, id).Scan(
		&t.TaskID, &t.KeeperID, &t.InstanceID, &t.Kind, &t.Status, &t.Payload, &t.Result,
		&t.ErrorCode, &t.ErrorMessage, &t.CreatedAt, &t.DispatchedAt, &t.AckedAt, &t.CompletedAt, &t.TimeoutSecs,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &t, nil
}

// ListRecentTasks returns recent tasks newest-first, optionally filtered by
// keeper and/or instance. Used by Progenitor's operator views.
func (s *Store) ListRecentTasks(ctx context.Context, keeperID *uuid.UUID, instanceID *uuid.UUID, limit int) ([]Task, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	base := `
		SELECT task_id, keeper_id, instance_id, kind, status, payload, result,
		       error_code, error_message, created_at, dispatched_at, acked_at, completed_at, timeout_secs
		FROM tasks
	`
	var (
		rows pgx.Rows
		err  error
	)
	switch {
	case keeperID != nil && instanceID != nil:
		rows, err = s.pool.Query(ctx, base+`WHERE keeper_id = $1 AND instance_id = $2 ORDER BY created_at DESC LIMIT $3`, *keeperID, *instanceID, limit)
	case keeperID != nil:
		rows, err = s.pool.Query(ctx, base+`WHERE keeper_id = $1 ORDER BY created_at DESC LIMIT $2`, *keeperID, limit)
	case instanceID != nil:
		rows, err = s.pool.Query(ctx, base+`WHERE instance_id = $1 ORDER BY created_at DESC LIMIT $2`, *instanceID, limit)
	default:
		rows, err = s.pool.Query(ctx, base+`ORDER BY created_at DESC LIMIT $1`, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Task
	for rows.Next() {
		var t Task
		if err := rows.Scan(
			&t.TaskID, &t.KeeperID, &t.InstanceID, &t.Kind, &t.Status, &t.Payload, &t.Result,
			&t.ErrorCode, &t.ErrorMessage, &t.CreatedAt, &t.DispatchedAt, &t.AckedAt, &t.CompletedAt, &t.TimeoutSecs,
		); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// ListQueuedTasksForKeeper returns tasks that were queued while a Keeper was
// offline and should be dispatched on next connect. Ordered by creation time
// so older tasks go first.
func (s *Store) ListQueuedTasksForKeeper(ctx context.Context, keeperID uuid.UUID) ([]Task, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT task_id, keeper_id, instance_id, kind, status, payload, result,
		       error_code, error_message, created_at, dispatched_at, acked_at, completed_at, timeout_secs
		FROM tasks
		WHERE keeper_id = $1 AND status = 'queued'
		ORDER BY created_at ASC
	`, keeperID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Task
	for rows.Next() {
		var t Task
		if err := rows.Scan(
			&t.TaskID, &t.KeeperID, &t.InstanceID, &t.Kind, &t.Status, &t.Payload, &t.Result,
			&t.ErrorCode, &t.ErrorMessage, &t.CreatedAt, &t.DispatchedAt, &t.AckedAt, &t.CompletedAt, &t.TimeoutSecs,
		); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}
