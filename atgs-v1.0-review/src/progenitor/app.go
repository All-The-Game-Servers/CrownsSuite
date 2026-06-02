// Package main is the Progenitor Wails application.
//
// Every exported method on *App is automatically exposed to the JavaScript
// frontend by Wails' runtime binding. The frontend calls them via
// `window.go.main.App.<MethodName>(args)`, which returns a Promise.
//
// State management: App holds the currently-connected Client (or nil if not
// connected). Methods that require a live connection check for nil and
// return a typed error the frontend can display.
package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"github.com/xkstudios/atgs/progenitor/internal/client"
)

type App struct {
	ctx context.Context

	mu  sync.Mutex
	c   *client.Client
	cfg *ConnectionConfig
}

// ConnectionConfig is what the UI stores to reconnect on next launch. Each
// saved connection lives in ~/.atgs-progenitor/connections/<name>.json.
// Phase 8+: multiple named connections supported so one operator can manage
// multiple Centrals (own server + customers' servers).
type ConnectionConfig struct {
	Name               string `json:"name"` // e.g. "prod", "customer-acme"
	CentralURL         string `json:"central_url"`
	BundleDir          string `json:"bundle_dir"`
	InsecureSkipVerify bool   `json:"insecure_skip_verify"`
}

func NewApp() *App {
	return &App{}
}

// startup is called by Wails when the window is ready.
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	// Attempt to auto-connect with the saved config. If it fails (expired
	// cert, missing bundle, unreachable Central) the UI falls back to the
	// connection setup screen.
	if cfg, err := loadSavedConnection(); err == nil && cfg != nil {
		_ = a.Connect(*cfg)
	}
}

// ---- Exposed to frontend ----

// Connect builds a client from the given config and tests the connection
// by calling whoami. On success, the config is persisted to disk.
func (a *App) Connect(cfg ConnectionConfig) error {
	bundle, err := client.LoadBundle(cfg.BundleDir)
	if err != nil {
		return err
	}
	c, err := client.New(client.Config{
		CentralURL:         cfg.CentralURL,
		Bundle:             bundle,
		InsecureSkipVerify: cfg.InsecureSkipVerify,
	})
	if err != nil {
		return err
	}
	if _, err := c.Whoami(a.ctx); err != nil {
		return err
	}
	a.mu.Lock()
	a.c = c
	a.cfg = &cfg
	a.mu.Unlock()
	_ = saveConnection(cfg)
	return nil
}

// Disconnect clears the active connection and removes the saved config.
func (a *App) Disconnect() error {
	a.mu.Lock()
	a.c = nil
	a.cfg = nil
	a.mu.Unlock()
	return clearSavedConnection()
}

// IsConnected reports whether a live client is configured.
func (a *App) IsConnected() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.c != nil
}

// SavedConnection returns the persisted connection config, if any.
// Used by the frontend to prefill the connection setup screen.
func (a *App) SavedConnection() *ConnectionConfig {
	cfg, _ := loadSavedConnection()
	return cfg
}

// Whoami returns identity info about the current connection.
func (a *App) Whoami() (*client.WhoamiResponse, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.Whoami(a.ctx)
}

// ---- Keeper views ----

func (a *App) ListKeepers() ([]client.Keeper, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListKeepers(a.ctx)
}

func (a *App) MintEnrollmentToken(note string) (*client.MintTokenResponse, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.MintEnrollmentToken(a.ctx, client.MintTokenRequest{Note: note})
}

func (a *App) ListWorkspaces() ([]client.Workspace, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListWorkspaces(a.ctx)
}

func (a *App) CreateWorkspace(req client.CreateWorkspaceRequest) (*client.Workspace, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.CreateWorkspace(a.ctx, req)
}

func (a *App) ListWorkspaceMembers(workspaceID string) ([]client.WorkspaceMembership, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListWorkspaceMembers(a.ctx, workspaceID)
}

func (a *App) UpsertWorkspaceMember(workspaceID string, req client.UpsertWorkspaceMemberRequest) (*client.WorkspaceMembership, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.UpsertWorkspaceMember(a.ctx, workspaceID, req)
}

func (a *App) AssignKeeperWorkspace(keeperID, workspaceID string) error {
	c, err := a.client()
	if err != nil {
		return err
	}
	return c.AssignKeeperWorkspace(a.ctx, keeperID, workspaceID)
}

// ---- Instance views ----

func (a *App) ListAllInstances() ([]client.Instance, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListAllInstances(a.ctx)
}

func (a *App) CreateInstance(keeperID string, req client.CreateInstanceRequest) (*client.CreateInstanceResponse, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.CreateInstance(a.ctx, keeperID, req)
}

func (a *App) StartInstance(instanceID string) error {
	c, err := a.client()
	if err != nil {
		return err
	}
	return c.StartInstance(a.ctx, instanceID)
}

func (a *App) StopInstance(instanceID string) error {
	c, err := a.client()
	if err != nil {
		return err
	}
	return c.StopInstance(a.ctx, instanceID)
}

func (a *App) DeleteInstance(instanceID string) error {
	c, err := a.client()
	if err != nil {
		return err
	}
	return c.DeleteInstance(a.ctx, instanceID)
}

func (a *App) GetInstanceLogs(instanceID string, lines int) (*client.InstanceLogsTailResult, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.GetInstanceLogs(a.ctx, instanceID, lines)
}

func (a *App) WriteInstanceConsole(instanceID string, input string) (*client.ConsoleWriteResponse, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.WriteInstanceConsole(a.ctx, instanceID, client.ConsoleWriteRequest{Input: input})
}

func (a *App) ListInstanceFiles(instanceID string, path string) (*client.InstanceFileListResult, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListInstanceFiles(a.ctx, instanceID, path)
}

func (a *App) ReadInstanceFile(instanceID string, path string) (*client.InstanceFileReadResult, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ReadInstanceFile(a.ctx, instanceID, path)
}

func (a *App) WriteInstanceFile(instanceID string, req client.InstanceFileWriteRequest) (*client.InstanceFileWriteResult, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.WriteInstanceFile(a.ctx, instanceID, req)
}

func (a *App) DeleteInstanceFile(instanceID string, req client.InstanceFileDeleteRequest) (*client.InstanceFileDeleteResult, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.DeleteInstanceFile(a.ctx, instanceID, req)
}

func (a *App) RenameInstanceFile(instanceID string, req client.InstanceFileRenameRequest) (*client.InstanceFileRenameResult, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.RenameInstanceFile(a.ctx, instanceID, req)
}

// ---- Backup views ----

func (a *App) ListBackupsForInstance(instanceID string) ([]client.Backup, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListBackupsForInstance(a.ctx, instanceID)
}

func (a *App) CreateBackup(instanceID string, req client.CreateBackupRequest) (*client.CreateBackupResponse, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.CreateBackup(a.ctx, instanceID, req)
}

func (a *App) RestoreBackup(backupID string, targetInstanceID string) (*client.RestoreBackupResponse, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.RestoreBackup(a.ctx, backupID, client.RestoreBackupRequest{TargetInstanceID: targetInstanceID})
}

func (a *App) DeleteBackup(backupID string) error {
	c, err := a.client()
	if err != nil {
		return err
	}
	return c.DeleteBackup(a.ctx, backupID)
}

// ---- Schedule views ----

func (a *App) ListAllSchedules() ([]client.Schedule, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListAllSchedules(a.ctx)
}

func (a *App) CreateSchedule(instanceID string, req client.CreateScheduleRequest) (*client.Schedule, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.CreateSchedule(a.ctx, instanceID, req)
}

func (a *App) ListTasks(instanceID string, limit int) ([]client.Task, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListTasks(a.ctx, instanceID, limit)
}

func (a *App) ListAudit(limit int) ([]client.AuditEntry, error) {
	c, err := a.client()
	if err != nil {
		return nil, err
	}
	return c.ListAudit(a.ctx, limit)
}

// ---- internals ----

// ErrNotConnected is returned when the UI tries to call a data method
// before Connect succeeded. The frontend catches this and routes back to
// the connection setup screen.
var ErrNotConnected = errors.New("not connected to Central")

func (a *App) client() (*client.Client, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.c == nil {
		return nil, ErrNotConnected
	}
	return a.c, nil
}

// ---- Persistent config ----

func configDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(home, ".atgs-progenitor")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, nil
}

func loadSavedConnection() (*ConnectionConfig, error) {
	dir, err := configDir()
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(filepath.Join(dir, "connection.json"))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var cfg ConnectionConfig
	if err := jsonUnmarshal(data, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func saveConnection(cfg ConnectionConfig) error {
	dir, err := configDir()
	if err != nil {
		return err
	}
	data, err := jsonMarshal(cfg)
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, "connection.json"), data, 0o600)
}

func clearSavedConnection() error {
	dir, err := configDir()
	if err != nil {
		return err
	}
	err = os.Remove(filepath.Join(dir, "connection.json"))
	if os.IsNotExist(err) {
		return nil
	}
	return err
}
