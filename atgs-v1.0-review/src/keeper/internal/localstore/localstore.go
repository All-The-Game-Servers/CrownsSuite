// Package localstore persists Keeper-side instance records to SQLite.
//
// The Keeper needs a durable memory of which containers it owns and why,
// independent of Central. Without this, a Keeper restart (or a Central
// outage) would leave orphan containers or miss recovery opportunities.
//
// This store is the Keeper's side of truth for instances it runs. Central
// is still the global source of truth for the *platform*, but the Keeper
// knows locally which Docker container corresponds to which Central instance
// UUID, its egg, and its last known state.
package localstore

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS instances (
    instance_id    TEXT PRIMARY KEY,
    container_id   TEXT,
    host_port      INTEGER NOT NULL DEFAULT 0,
    egg_id         TEXT NOT NULL,
    display_name   TEXT NOT NULL,
    state          TEXT NOT NULL DEFAULT 'created',
    memory_bytes   INTEGER NOT NULL,
    cpu_shares     INTEGER NOT NULL,
    env_json       TEXT NOT NULL DEFAULT '{}',
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_instances_state ON instances(state);
`

type Store struct {
	db *sql.DB
}

func Open(stateDir string) (*Store, error) {
	path := filepath.Join(stateDir, "keeper.db")
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping sqlite: %w", err)
	}
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("apply schema: %w", err)
	}
	_, _ = db.Exec(`ALTER TABLE instances ADD COLUMN host_port INTEGER NOT NULL DEFAULT 0`)
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

type Instance struct {
	InstanceID   string
	ContainerID  string
	HostPort     int
	EggID        string
	DisplayName  string
	State        string // created, running, stopped, error
	MemoryBytes  int64
	CPUShares    int64
	EnvJSON      string
	CreatedAt    time.Time
	UpdatedAt    time.Time
}

type UpsertInstanceParams struct {
	InstanceID  string
	EggID       string
	DisplayName string
	MemoryBytes int64
	CPUShares   int64
	EnvJSON     string
}

// UpsertInstance creates or updates an instance row. Does not touch
// container_id; that's set separately once Docker creates the container.
func (s *Store) UpsertInstance(ctx context.Context, p UpsertInstanceParams) error {
	now := time.Now().Unix()
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO instances (instance_id, egg_id, display_name, memory_bytes, cpu_shares, env_json, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(instance_id) DO UPDATE SET
			display_name = excluded.display_name,
			memory_bytes = excluded.memory_bytes,
			cpu_shares = excluded.cpu_shares,
			env_json = excluded.env_json,
			updated_at = excluded.updated_at
	`, p.InstanceID, p.EggID, p.DisplayName, p.MemoryBytes, p.CPUShares, p.EnvJSON, now, now)
	return err
}

func (s *Store) SetContainerID(ctx context.Context, instanceID, containerID string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE instances SET container_id = ?, updated_at = ? WHERE instance_id = ?
	`, containerID, time.Now().Unix(), instanceID)
	return err
}

func (s *Store) SetHostPort(ctx context.Context, instanceID string, hostPort int) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE instances SET host_port = ?, updated_at = ? WHERE instance_id = ?
	`, hostPort, time.Now().Unix(), instanceID)
	return err
}

func (s *Store) SetState(ctx context.Context, instanceID, state string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE instances SET state = ?, updated_at = ? WHERE instance_id = ?
	`, state, time.Now().Unix(), instanceID)
	return err
}

func (s *Store) DeleteInstance(ctx context.Context, instanceID string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM instances WHERE instance_id = ?`, instanceID)
	return err
}

func (s *Store) GetInstance(ctx context.Context, instanceID string) (*Instance, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT instance_id, COALESCE(container_id, ''), egg_id, display_name, state,
		       host_port, memory_bytes, cpu_shares, env_json, created_at, updated_at
		FROM instances WHERE instance_id = ?
	`, instanceID)
	var inst Instance
	var createdAt, updatedAt int64
	err := row.Scan(
		&inst.InstanceID, &inst.ContainerID, &inst.EggID, &inst.DisplayName,
		&inst.State, &inst.HostPort, &inst.MemoryBytes, &inst.CPUShares, &inst.EnvJSON,
		&createdAt, &updatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	inst.CreatedAt = time.Unix(createdAt, 0)
	inst.UpdatedAt = time.Unix(updatedAt, 0)
	return &inst, nil
}

func (s *Store) ListInstances(ctx context.Context) ([]Instance, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT instance_id, COALESCE(container_id, ''), egg_id, display_name, state,
		       host_port, memory_bytes, cpu_shares, env_json, created_at, updated_at
		FROM instances ORDER BY created_at ASC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Instance
	for rows.Next() {
		var inst Instance
		var createdAt, updatedAt int64
		if err := rows.Scan(
			&inst.InstanceID, &inst.ContainerID, &inst.EggID, &inst.DisplayName,
			&inst.State, &inst.HostPort, &inst.MemoryBytes, &inst.CPUShares, &inst.EnvJSON,
			&createdAt, &updatedAt,
		); err != nil {
			return nil, err
		}
		inst.CreatedAt = time.Unix(createdAt, 0)
		inst.UpdatedAt = time.Unix(updatedAt, 0)
		out = append(out, inst)
	}
	return out, rows.Err()
}

var ErrNotFound = errors.New("not found")
