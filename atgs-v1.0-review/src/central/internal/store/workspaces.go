package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

const (
	DefaultWorkspaceID   = "00000000-0000-0000-0000-000000001200"
	WorkspaceRoleOwner   = "owner"
	WorkspaceRoleMember  = "member"
	CapabilityLifecycle  = "instance.lifecycle"
	CapabilityLogsRead   = "logs.read"
	CapabilityConsole    = "console.write"
	CapabilityFilesRead  = "files.read"
	CapabilityFilesWrite = "files.write"
	CapabilityBackups    = "backups.manage"
	CapabilitySchedules  = "schedules.manage"
	CapabilityMembers    = "members.manage"
	CapabilityKeepers    = "keepers.view"
)

var DefaultWorkspaceUUID = uuid.MustParse(DefaultWorkspaceID)

var FullWorkspaceCapabilities = []string{
	CapabilityLifecycle,
	CapabilityLogsRead,
	CapabilityConsole,
	CapabilityFilesRead,
	CapabilityFilesWrite,
	CapabilityBackups,
	CapabilitySchedules,
	CapabilityMembers,
	CapabilityKeepers,
}

type Workspace struct {
	ID                      uuid.UUID  `json:"id"`
	Slug                    string     `json:"slug"`
	DisplayName             string     `json:"display_name"`
	OwnerUserID             *uuid.UUID `json:"owner_user_id,omitempty"`
	MockPlanKey             string     `json:"mock_plan_key"`
	MockSubscriptionStatus  string     `json:"mock_subscription_status"`
	MockSubscriptionSeatLimit int      `json:"mock_subscription_seat_limit"`
	CreatedAt               time.Time  `json:"created_at"`
	UpdatedAt               time.Time  `json:"updated_at"`
}

type WorkspaceMembership struct {
	WorkspaceID   uuid.UUID  `json:"workspace_id"`
	UserID        uuid.UUID  `json:"user_id"`
	UserEmail     string     `json:"user_email"`
	Role          string     `json:"role"`
	Capabilities  []string   `json:"capabilities"`
	CreatedAt     time.Time  `json:"created_at"`
	UpdatedAt     time.Time  `json:"updated_at"`
	LastLoginAt   *time.Time `json:"last_login_at,omitempty"`
	DisabledAt    *time.Time `json:"disabled_at,omitempty"`
}

type WorkspaceSubscription struct {
	WorkspaceID uuid.UUID `json:"workspace_id"`
	PlanKey     string    `json:"plan_key"`
	Status      string    `json:"status"`
	SeatLimit   int       `json:"seat_limit"`
	Note        string    `json:"note"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type CreateWorkspaceParams struct {
	ID                     uuid.UUID
	Slug                   string
	DisplayName            string
	OwnerUserID            *uuid.UUID
	MockPlanKey            string
	MockSubscriptionStatus string
	MockSubscriptionSeatLimit int
}

type UpsertWorkspaceMembershipParams struct {
	WorkspaceID  uuid.UUID
	UserID       uuid.UUID
	Role         string
	Capabilities []string
}

func NormalizeCapabilities(in []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, cap := range in {
		cap = strings.TrimSpace(cap)
		if cap == "" || seen[cap] {
			continue
		}
		seen[cap] = true
		out = append(out, cap)
	}
	return out
}

func (s *Store) CreateWorkspace(ctx context.Context, p CreateWorkspaceParams) (uuid.UUID, error) {
	if p.ID == uuid.Nil {
		p.ID = uuid.New()
	}
	if p.Slug == "" {
		return uuid.Nil, fmt.Errorf("workspace slug required")
	}
	if p.DisplayName == "" {
		return uuid.Nil, fmt.Errorf("workspace display name required")
	}
	if p.MockPlanKey == "" {
		p.MockPlanKey = "founder"
	}
	if p.MockSubscriptionStatus == "" {
		p.MockSubscriptionStatus = "active"
	}
	if p.MockSubscriptionSeatLimit <= 0 {
		p.MockSubscriptionSeatLimit = 1
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return uuid.Nil, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `
		INSERT INTO workspaces (
			workspace_id, slug, display_name, owner_user_id,
			mock_plan_key, mock_subscription_status, mock_subscription_seat_limit
		) VALUES ($1,$2,$3,$4,$5,$6,$7)
	`, p.ID, p.Slug, p.DisplayName, p.OwnerUserID, p.MockPlanKey, p.MockSubscriptionStatus, p.MockSubscriptionSeatLimit); err != nil {
		return uuid.Nil, err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO workspace_subscriptions (workspace_id, plan_key, status, seat_limit)
		VALUES ($1,$2,$3,$4)
	`, p.ID, p.MockPlanKey, p.MockSubscriptionStatus, p.MockSubscriptionSeatLimit); err != nil {
		return uuid.Nil, err
	}
	if p.OwnerUserID != nil {
		capsJSON, _ := json.Marshal(FullWorkspaceCapabilities)
		if _, err := tx.Exec(ctx, `
			INSERT INTO workspace_memberships (workspace_id, user_id, role, capabilities)
			VALUES ($1,$2,$3,$4)
			ON CONFLICT (workspace_id, user_id) DO UPDATE SET
				role = EXCLUDED.role,
				capabilities = EXCLUDED.capabilities,
				updated_at = NOW()
		`, p.ID, *p.OwnerUserID, WorkspaceRoleOwner, capsJSON); err != nil {
			return uuid.Nil, err
		}
	}
	return p.ID, tx.Commit(ctx)
}

func (s *Store) ListWorkspaces(ctx context.Context) ([]Workspace, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT workspace_id, slug, display_name, owner_user_id,
		       mock_plan_key, mock_subscription_status, mock_subscription_seat_limit,
		       created_at, updated_at
		FROM workspaces
		ORDER BY created_at ASC
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Workspace
	for rows.Next() {
		var ws Workspace
		if err := rows.Scan(
			&ws.ID, &ws.Slug, &ws.DisplayName, &ws.OwnerUserID,
			&ws.MockPlanKey, &ws.MockSubscriptionStatus, &ws.MockSubscriptionSeatLimit,
			&ws.CreatedAt, &ws.UpdatedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, ws)
	}
	return out, rows.Err()
}

func (s *Store) GetWorkspace(ctx context.Context, id uuid.UUID) (*Workspace, error) {
	var ws Workspace
	err := s.pool.QueryRow(ctx, `
		SELECT workspace_id, slug, display_name, owner_user_id,
		       mock_plan_key, mock_subscription_status, mock_subscription_seat_limit,
		       created_at, updated_at
		FROM workspaces
		WHERE workspace_id = $1
	`, id).Scan(
		&ws.ID, &ws.Slug, &ws.DisplayName, &ws.OwnerUserID,
		&ws.MockPlanKey, &ws.MockSubscriptionStatus, &ws.MockSubscriptionSeatLimit,
		&ws.CreatedAt, &ws.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &ws, nil
}

func (s *Store) ListWorkspacesForUser(ctx context.Context, userID uuid.UUID) ([]Workspace, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT w.workspace_id, w.slug, w.display_name, w.owner_user_id,
		       w.mock_plan_key, w.mock_subscription_status, w.mock_subscription_seat_limit,
		       w.created_at, w.updated_at
		FROM workspaces w
		INNER JOIN workspace_memberships wm ON wm.workspace_id = w.workspace_id
		WHERE wm.user_id = $1
		ORDER BY w.created_at ASC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Workspace
	for rows.Next() {
		var ws Workspace
		if err := rows.Scan(
			&ws.ID, &ws.Slug, &ws.DisplayName, &ws.OwnerUserID,
			&ws.MockPlanKey, &ws.MockSubscriptionStatus, &ws.MockSubscriptionSeatLimit,
			&ws.CreatedAt, &ws.UpdatedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, ws)
	}
	return out, rows.Err()
}

func (s *Store) GetWorkspaceMembership(ctx context.Context, workspaceID, userID uuid.UUID) (*WorkspaceMembership, error) {
	var (
		m        WorkspaceMembership
		capsJSON []byte
	)
	err := s.pool.QueryRow(ctx, `
		SELECT wm.workspace_id, wm.user_id, u.email, wm.role, wm.capabilities,
		       wm.created_at, wm.updated_at, u.last_login_at, u.disabled_at
		FROM workspace_memberships wm
		INNER JOIN users u ON u.user_id = wm.user_id
		WHERE wm.workspace_id = $1 AND wm.user_id = $2
	`, workspaceID, userID).Scan(
		&m.WorkspaceID, &m.UserID, &m.UserEmail, &m.Role, &capsJSON,
		&m.CreatedAt, &m.UpdatedAt, &m.LastLoginAt, &m.DisabledAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	_ = json.Unmarshal(capsJSON, &m.Capabilities)
	m.Capabilities = NormalizeCapabilities(m.Capabilities)
	return &m, nil
}

func (s *Store) ListWorkspaceMemberships(ctx context.Context, workspaceID uuid.UUID) ([]WorkspaceMembership, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT wm.workspace_id, wm.user_id, u.email, wm.role, wm.capabilities,
		       wm.created_at, wm.updated_at, u.last_login_at, u.disabled_at
		FROM workspace_memberships wm
		INNER JOIN users u ON u.user_id = wm.user_id
		WHERE wm.workspace_id = $1
		ORDER BY wm.created_at ASC
	`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []WorkspaceMembership
	for rows.Next() {
		var (
			m        WorkspaceMembership
			capsJSON []byte
		)
		if err := rows.Scan(
			&m.WorkspaceID, &m.UserID, &m.UserEmail, &m.Role, &capsJSON,
			&m.CreatedAt, &m.UpdatedAt, &m.LastLoginAt, &m.DisabledAt,
		); err != nil {
			return nil, err
		}
		_ = json.Unmarshal(capsJSON, &m.Capabilities)
		m.Capabilities = NormalizeCapabilities(m.Capabilities)
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) UpsertWorkspaceMembership(ctx context.Context, p UpsertWorkspaceMembershipParams) error {
	capsJSON, err := json.Marshal(NormalizeCapabilities(p.Capabilities))
	if err != nil {
		return err
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO workspace_memberships (workspace_id, user_id, role, capabilities)
		VALUES ($1,$2,$3,$4)
		ON CONFLICT (workspace_id, user_id) DO UPDATE SET
			role = EXCLUDED.role,
			capabilities = EXCLUDED.capabilities,
			updated_at = NOW()
	`, p.WorkspaceID, p.UserID, p.Role, capsJSON)
	return err
}

func (s *Store) DeleteWorkspaceMembership(ctx context.Context, workspaceID, userID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM workspace_memberships WHERE workspace_id = $1 AND user_id = $2`, workspaceID, userID)
	return err
}

func (s *Store) GetWorkspaceSubscription(ctx context.Context, workspaceID uuid.UUID) (*WorkspaceSubscription, error) {
	var sub WorkspaceSubscription
	err := s.pool.QueryRow(ctx, `
		SELECT workspace_id, plan_key, status, seat_limit, note, updated_at
		FROM workspace_subscriptions
		WHERE workspace_id = $1
	`, workspaceID).Scan(&sub.WorkspaceID, &sub.PlanKey, &sub.Status, &sub.SeatLimit, &sub.Note, &sub.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &sub, nil
}

func (s *Store) AssignKeeperToWorkspace(ctx context.Context, keeperID, workspaceID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE keepers SET workspace_id = $2 WHERE keeper_id = $1`, keeperID, workspaceID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE instances SET workspace_id = $2 WHERE keeper_id = $1`, keeperID, workspaceID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE backups b
		SET workspace_id = $2
		FROM instances i
		WHERE b.instance_id = i.instance_id AND i.keeper_id = $1
	`, keeperID, workspaceID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE backup_schedules bs
		SET workspace_id = $2
		FROM instances i
		WHERE bs.instance_id = i.instance_id AND i.keeper_id = $1
	`, keeperID, workspaceID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
