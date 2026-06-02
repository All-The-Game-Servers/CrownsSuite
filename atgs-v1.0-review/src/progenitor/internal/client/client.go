// Package client is Progenitor's Go-side ATGS API client.
//
// It loads a progenitor bundle (cert/key/ca) from disk, builds an mTLS
// http.Client, and offers typed methods for each admin endpoint. Called from
// the Wails bindings in app.go.
package client

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Bundle is the on-disk bundle issued by `central mint-progenitor-cert`.
type Bundle struct {
	ProgenitorID string
	Certificate  tls.Certificate
	CACertPool   *x509.CertPool
	CertNotAfter time.Time
}

// LoadBundle reads the four-file bundle from dir.
func LoadBundle(dir string) (*Bundle, error) {
	idBytes, err := os.ReadFile(filepath.Join(dir, "progenitor.id"))
	if err != nil {
		return nil, fmt.Errorf("read progenitor.id: %w", err)
	}
	certPEM, err := os.ReadFile(filepath.Join(dir, "client.crt"))
	if err != nil {
		return nil, fmt.Errorf("read client.crt: %w", err)
	}
	keyPEM, err := os.ReadFile(filepath.Join(dir, "client.key"))
	if err != nil {
		return nil, fmt.Errorf("read client.key: %w", err)
	}
	caPEM, err := os.ReadFile(filepath.Join(dir, "ca.crt"))
	if err != nil {
		return nil, fmt.Errorf("read ca.crt: %w", err)
	}
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, fmt.Errorf("load keypair: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("ca.crt contains no certificates")
	}
	// Parse leaf for expiry info shown in the UI.
	if len(cert.Certificate) == 0 {
		return nil, errors.New("no leaf cert in bundle")
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return nil, fmt.Errorf("parse leaf: %w", err)
	}
	return &Bundle{
		ProgenitorID: strings.TrimSpace(string(idBytes)),
		Certificate:  cert,
		CACertPool:   pool,
		CertNotAfter: leaf.NotAfter,
	}, nil
}

// Client is an authenticated admin client.
type Client struct {
	baseURL    string
	httpClient *http.Client
	bundle     *Bundle
}

// Config is what New needs.
type Config struct {
	// CentralURL is the https://host:port root. Progenitor appends /api/v1/admin
	// to every request. Example: https://central.atgslowlightsmp.mine.bz:8443
	CentralURL string

	Bundle *Bundle

	// InsecureSkipVerify disables server cert verification. Useful only for
	// local dev where Central uses its self-issued cert. The UI surfaces this
	// as a checkbox on the connection setup screen.
	InsecureSkipVerify bool

	// Timeout is the per-request timeout. 30s default; bump for long-running
	// things like a restore trigger.
	Timeout time.Duration
}

// New builds the client. Returns an error if the config is obviously wrong
// (missing URL, missing bundle) but does NOT hit the network.
func New(cfg Config) (*Client, error) {
	if cfg.CentralURL == "" {
		return nil, errors.New("central URL required")
	}
	if cfg.Bundle == nil {
		return nil, errors.New("bundle required")
	}
	timeout := cfg.Timeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	tlsCfg := &tls.Config{
		Certificates:       []tls.Certificate{cfg.Bundle.Certificate},
		RootCAs:            cfg.Bundle.CACertPool,
		InsecureSkipVerify: cfg.InsecureSkipVerify,
		MinVersion:         tls.VersionTLS12,
	}
	return &Client{
		baseURL: strings.TrimRight(cfg.CentralURL, "/"),
		bundle:  cfg.Bundle,
		httpClient: &http.Client{
			Timeout:   timeout,
			Transport: &http.Transport{TLSClientConfig: tlsCfg},
		},
	}, nil
}

// Bundle returns the bundle the client was built from. Useful for the UI to
// display identity info.
func (c *Client) Bundle() *Bundle { return c.bundle }

// do is the shared request flow. method is "GET"/"POST"/etc; path is the
// sub-path under /api/v1/admin (e.g. "whoami", "keepers").
// body is optional; if non-nil it's marshalled to JSON.
// into is optional; if non-nil the response body is unmarshalled into it.
func (c *Client) do(ctx context.Context, method, path string, body, into any) error {
	url := c.baseURL + "/api/v1/admin/" + strings.TrimLeft(path, "/")

	var bodyReader io.Reader
	if body != nil {
		bodyJSON, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("marshal request: %w", err)
		}
		bodyReader = bytes.NewReader(bodyJSON)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("%s %s: %w", method, url, err)
	}
	defer resp.Body.Close()
	respBytes, _ := io.ReadAll(resp.Body)

	if resp.StatusCode >= 400 {
		// Try to unmarshal the error envelope Central returns.
		var errEnv struct {
			Error   string `json:"error"`
			Message string `json:"message"`
		}
		_ = json.Unmarshal(respBytes, &errEnv)
		if errEnv.Error != "" {
			return &APIError{
				Status:  resp.StatusCode,
				Code:    errEnv.Error,
				Message: errEnv.Message,
			}
		}
		return &APIError{
			Status:  resp.StatusCode,
			Message: string(respBytes),
		}
	}
	if into == nil || len(respBytes) == 0 {
		return nil
	}
	if err := json.Unmarshal(respBytes, into); err != nil {
		return fmt.Errorf("decode response: %w", err)
	}
	return nil
}

// APIError is what client calls return on non-2xx responses. The UI checks the
// Status field to distinguish auth failures (403) from missing resources (404)
// from server errors (5xx).
type APIError struct {
	Status  int    `json:"status"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
}

func (e *APIError) Error() string {
	if e.Code != "" {
		return fmt.Sprintf("api: %d %s: %s", e.Status, e.Code, e.Message)
	}
	return fmt.Sprintf("api: %d: %s", e.Status, e.Message)
}

// ---- Typed response shapes (mirrors what Central returns) ----

type WhoamiResponse struct {
	ProgenitorID  string    `json:"progenitor_id"`
	OU            []string  `json:"ou"`
	CertNotAfter  time.Time `json:"cert_not_after"`
	ServerVersion string    `json:"server_version"`
}

type Keeper struct {
	ID                   string                   `json:"id"`
	WorkspaceID          string                   `json:"workspace_id"`
	DisplayName          string                   `json:"display_name"`
	Platform             string                   `json:"platform"`
	Arch                 string                   `json:"arch"`
	Hostname             string                   `json:"hostname"`
	AgentVersion         string                   `json:"agent_version"`
	CertNotAfter         time.Time                `json:"cert_not_after"`
	EnrolledAt           time.Time                `json:"enrolled_at"`
	LastSeenAt           *time.Time               `json:"last_seen_at,omitempty"`
	RevokedAt            *time.Time               `json:"revoked_at,omitempty"`
	Connected            bool                     `json:"connected"`
	PublicKeyFingerprint string                   `json:"public_key_fingerprint"`
	Resources            *KeeperResourcesSnapshot `json:"resources,omitempty"`
}

type KeeperResourcesSnapshot struct {
	ReportedAt     time.Time `json:"reported_at"`
	CPUCores       int       `json:"cpu_cores"`
	CPUPercentUsed float64   `json:"cpu_percent_used"`
	MemTotalBytes  uint64    `json:"mem_total_bytes"`
	MemUsedBytes   uint64    `json:"mem_used_bytes"`
	DiskTotalBytes uint64    `json:"disk_total_bytes"`
	DiskUsedBytes  uint64    `json:"disk_used_bytes"`
}

type KeeperList struct {
	Keepers []Keeper `json:"keepers"`
}

type Workspace struct {
	ID                        string  `json:"id"`
	Slug                      string  `json:"slug"`
	DisplayName               string  `json:"display_name"`
	OwnerUserID               *string `json:"owner_user_id,omitempty"`
	MockPlanKey               string  `json:"mock_plan_key"`
	MockSubscriptionStatus    string  `json:"mock_subscription_status"`
	MockSubscriptionSeatLimit int     `json:"mock_subscription_seat_limit"`
}

type WorkspaceList struct {
	Workspaces []Workspace `json:"workspaces"`
}

type WorkspaceMembership struct {
	WorkspaceID  string   `json:"workspace_id"`
	UserID       string   `json:"user_id"`
	UserEmail    string   `json:"user_email"`
	Role         string   `json:"role"`
	Capabilities []string `json:"capabilities"`
}

type WorkspaceMembershipList struct {
	Members []WorkspaceMembership `json:"members"`
}

type Instance struct {
	InstanceID  string    `json:"instance_id"`
	WorkspaceID string    `json:"workspace_id"`
	KeeperID    string    `json:"keeper_id"`
	EggID       string    `json:"egg_id"`
	DisplayName string    `json:"display_name"`
	State       string    `json:"state"`
	Hostname    *string   `json:"hostname,omitempty"`
	HostPort    *int      `json:"host_port,omitempty"`
	PublicPort  *int      `json:"public_port,omitempty"`
	MemoryBytes int64     `json:"memory_bytes"`
	CPUShares   int64     `json:"cpu_shares"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type InstanceList struct {
	Instances []Instance `json:"instances"`
}

type InstanceLogsTailResult struct {
	InstanceID string   `json:"instance_id"`
	Lines      []string `json:"lines"`
	Truncated  bool     `json:"truncated"`
}

type InstanceFileEntry struct {
	Path           string `json:"path"`
	Name           string `json:"name"`
	IsDir          bool   `json:"is_dir"`
	SizeBytes      int64  `json:"size_bytes"`
	ModifiedAtUnix int64  `json:"modified_at_unix"`
}

type InstanceFileListResult struct {
	InstanceID string              `json:"instance_id"`
	Path       string              `json:"path"`
	Entries    []InstanceFileEntry `json:"entries"`
}

type InstanceFileReadResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	Content    []byte `json:"content"`
	SizeBytes  int64  `json:"size_bytes"`
}

type InstanceFileWriteRequest struct {
	Path          string `json:"path"`
	Content       []byte `json:"content"`
	CreateParents bool   `json:"create_parents,omitempty"`
}

type InstanceFileWriteResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	SizeBytes  int64  `json:"size_bytes"`
}

type InstanceFileDeleteRequest struct {
	Path      string `json:"path"`
	Recursive bool   `json:"recursive,omitempty"`
}

type InstanceFileDeleteResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	Deleted    bool   `json:"deleted"`
}

type InstanceFileRenameRequest struct {
	Path    string `json:"path"`
	NewPath string `json:"new_path"`
}

type InstanceFileRenameResult struct {
	InstanceID string `json:"instance_id"`
	Path       string `json:"path"`
	NewPath    string `json:"new_path"`
}

type ConsoleWriteRequest struct {
	Input string `json:"input"`
}

type ConsoleWriteResponse struct {
	TaskID string `json:"task_id"`
}

type Backup struct {
	BackupID    string     `json:"backup_id"`
	InstanceID  string     `json:"instance_id"`
	DisplayName string     `json:"display_name"`
	Status      string     `json:"status"`
	StorageMode string     `json:"storage_mode"`
	TotalBytes  int64      `json:"total_bytes"`
	ChunkCount  int        `json:"chunk_count"`
	Encrypted   bool       `json:"encrypted"`
	CreatedAt   time.Time  `json:"created_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	Error       string     `json:"error,omitempty"`
}

type BackupList struct {
	Backups []Backup `json:"backups"`
}

type Schedule struct {
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

type ScheduleList struct {
	Schedules []Schedule `json:"schedules"`
}

type Task struct {
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

type TaskList struct {
	Tasks []Task `json:"tasks"`
}

type AuditEntry struct {
	ID       int64          `json:"id"`
	At       time.Time      `json:"at"`
	Kind     string         `json:"kind"`
	Actor    string         `json:"actor"`
	KeeperID *string        `json:"keeper_id,omitempty"`
	Details  map[string]any `json:"details"`
}

type AuditList struct {
	Entries []AuditEntry `json:"entries"`
}

// ---- Typed endpoint methods ----

func (c *Client) Whoami(ctx context.Context) (*WhoamiResponse, error) {
	var out WhoamiResponse
	if err := c.do(ctx, "GET", "whoami", nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ListKeepers(ctx context.Context) ([]Keeper, error) {
	var out KeeperList
	if err := c.do(ctx, "GET", "keepers", nil, &out); err != nil {
		return nil, err
	}
	return out.Keepers, nil
}

func (c *Client) ListWorkspaces(ctx context.Context) ([]Workspace, error) {
	var out WorkspaceList
	if err := c.do(ctx, "GET", "workspaces", nil, &out); err != nil {
		return nil, err
	}
	return out.Workspaces, nil
}

type CreateWorkspaceRequest struct {
	Slug                      string `json:"slug"`
	DisplayName               string `json:"display_name"`
	OwnerUserID               string `json:"owner_user_id,omitempty"`
	MockPlanKey               string `json:"mock_plan_key,omitempty"`
	MockSubscriptionStatus    string `json:"mock_subscription_status,omitempty"`
	MockSubscriptionSeatLimit int    `json:"mock_subscription_seat_limit,omitempty"`
}

func (c *Client) CreateWorkspace(ctx context.Context, req CreateWorkspaceRequest) (*Workspace, error) {
	var out Workspace
	if err := c.do(ctx, "POST", "workspaces", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ListWorkspaceMembers(ctx context.Context, workspaceID string) ([]WorkspaceMembership, error) {
	var out WorkspaceMembershipList
	if err := c.do(ctx, "GET", "workspaces/"+workspaceID+"/members", nil, &out); err != nil {
		return nil, err
	}
	return out.Members, nil
}

type UpsertWorkspaceMemberRequest struct {
	UserID       string   `json:"user_id"`
	Role         string   `json:"role"`
	Capabilities []string `json:"capabilities"`
}

func (c *Client) UpsertWorkspaceMember(ctx context.Context, workspaceID string, req UpsertWorkspaceMemberRequest) (*WorkspaceMembership, error) {
	var out WorkspaceMembership
	if err := c.do(ctx, "POST", "workspaces/"+workspaceID+"/members", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) AssignKeeperWorkspace(ctx context.Context, keeperID, workspaceID string) error {
	return c.do(ctx, "POST", "keepers/"+keeperID+"/workspace", map[string]string{"workspace_id": workspaceID}, nil)
}

func (c *Client) ListAllInstances(ctx context.Context) ([]Instance, error) {
	var out InstanceList
	if err := c.do(ctx, "GET", "instances", nil, &out); err != nil {
		return nil, err
	}
	return out.Instances, nil
}

func (c *Client) ListInstancesForKeeper(ctx context.Context, keeperID string) ([]Instance, error) {
	var out InstanceList
	if err := c.do(ctx, "GET", "keepers/"+keeperID+"/instances", nil, &out); err != nil {
		return nil, err
	}
	return out.Instances, nil
}

type CreateInstanceRequest struct {
	EggID       string            `json:"egg_id"`
	DisplayName string            `json:"display_name"`
	Hostname    string            `json:"hostname,omitempty"`
	Env         map[string]string `json:"env,omitempty"`
	MemoryBytes int64             `json:"memory_bytes"`
	CPUShares   int64             `json:"cpu_shares"`
}

type CreateInstanceResponse struct {
	InstanceID string `json:"instance_id"`
	TaskID     string `json:"task_id"`
	Hostname   string `json:"hostname,omitempty"`
	PublicPort *int   `json:"public_port,omitempty"`
}

func (c *Client) CreateInstance(ctx context.Context, keeperID string, req CreateInstanceRequest) (*CreateInstanceResponse, error) {
	var out CreateInstanceResponse
	if err := c.do(ctx, "POST", "keepers/"+keeperID+"/instances", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) StartInstance(ctx context.Context, instanceID string) error {
	return c.do(ctx, "POST", "instances/"+instanceID+"/start", nil, nil)
}

func (c *Client) StopInstance(ctx context.Context, instanceID string) error {
	return c.do(ctx, "POST", "instances/"+instanceID+"/stop", nil, nil)
}

func (c *Client) DeleteInstance(ctx context.Context, instanceID string) error {
	return c.do(ctx, "DELETE", "instances/"+instanceID, nil, nil)
}

func (c *Client) GetInstanceLogs(ctx context.Context, instanceID string, lines int) (*InstanceLogsTailResult, error) {
	var out InstanceLogsTailResult
	path := fmt.Sprintf("instances/%s/logs?lines=%d", instanceID, lines)
	if err := c.do(ctx, "GET", path, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) WriteInstanceConsole(ctx context.Context, instanceID string, req ConsoleWriteRequest) (*ConsoleWriteResponse, error) {
	var out ConsoleWriteResponse
	if err := c.do(ctx, "POST", "instances/"+instanceID+"/console", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ListInstanceFiles(ctx context.Context, instanceID, path string) (*InstanceFileListResult, error) {
	var out InstanceFileListResult
	reqPath := fmt.Sprintf("instances/%s/files?path=%s", instanceID, path)
	if err := c.do(ctx, "GET", reqPath, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ReadInstanceFile(ctx context.Context, instanceID, path string) (*InstanceFileReadResult, error) {
	var out InstanceFileReadResult
	reqPath := fmt.Sprintf("instances/%s/file?path=%s", instanceID, path)
	if err := c.do(ctx, "GET", reqPath, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) WriteInstanceFile(ctx context.Context, instanceID string, req InstanceFileWriteRequest) (*InstanceFileWriteResult, error) {
	var out InstanceFileWriteResult
	if err := c.do(ctx, "PUT", "instances/"+instanceID+"/file", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteInstanceFile(ctx context.Context, instanceID string, req InstanceFileDeleteRequest) (*InstanceFileDeleteResult, error) {
	var out InstanceFileDeleteResult
	if err := c.do(ctx, "DELETE", "instances/"+instanceID+"/file", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) RenameInstanceFile(ctx context.Context, instanceID string, req InstanceFileRenameRequest) (*InstanceFileRenameResult, error) {
	var out InstanceFileRenameResult
	if err := c.do(ctx, "POST", "instances/"+instanceID+"/file/rename", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type CreateBackupRequest struct {
	DisplayName      string `json:"display_name,omitempty"`
	StorageMode      string `json:"storage_mode,omitempty"`
	Encrypted        bool   `json:"encrypted,omitempty"`
	StopDuringBackup bool   `json:"stop_during_backup,omitempty"`
}

type CreateBackupResponse struct {
	BackupID    string `json:"backup_id"`
	TaskID      string `json:"task_id"`
	InstanceID  string `json:"instance_id"`
	StorageMode string `json:"storage_mode"`
	Encrypted   bool   `json:"encrypted"`
}

func (c *Client) CreateBackup(ctx context.Context, instanceID string, req CreateBackupRequest) (*CreateBackupResponse, error) {
	var out CreateBackupResponse
	if err := c.do(ctx, "POST", "instances/"+instanceID+"/backups", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ListBackupsForInstance(ctx context.Context, instanceID string) ([]Backup, error) {
	var out BackupList
	if err := c.do(ctx, "GET", "instances/"+instanceID+"/backups", nil, &out); err != nil {
		return nil, err
	}
	return out.Backups, nil
}

func (c *Client) DeleteBackup(ctx context.Context, backupID string) error {
	return c.do(ctx, "DELETE", "backups/"+backupID, nil, nil)
}

type RestoreBackupRequest struct {
	TargetInstanceID string `json:"target_instance_id"`
}

type RestoreBackupResponse struct {
	BackupID         string `json:"backup_id"`
	TargetInstanceID string `json:"target_instance_id"`
	TaskID           string `json:"task_id"`
}

func (c *Client) RestoreBackup(ctx context.Context, backupID string, req RestoreBackupRequest) (*RestoreBackupResponse, error) {
	var out RestoreBackupResponse
	if err := c.do(ctx, "POST", "backups/"+backupID+"/restore", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type CreateScheduleRequest struct {
	CronExpr    string `json:"cron_expr"`
	Retention   int    `json:"retention,omitempty"`
	StorageMode string `json:"storage_mode,omitempty"`
	Encrypt     bool   `json:"encrypt,omitempty"`
}

func (c *Client) CreateSchedule(ctx context.Context, instanceID string, req CreateScheduleRequest) (*Schedule, error) {
	var out Schedule
	if err := c.do(ctx, "POST", "instances/"+instanceID+"/backup-schedule", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) ListAllSchedules(ctx context.Context) ([]Schedule, error) {
	var out ScheduleList
	if err := c.do(ctx, "GET", "schedules", nil, &out); err != nil {
		return nil, err
	}
	return out.Schedules, nil
}

func (c *Client) ListTasks(ctx context.Context, instanceID string, limit int) ([]Task, error) {
	path := "tasks"
	var query []string
	if instanceID != "" {
		query = append(query, "instance_id="+instanceID)
	}
	if limit > 0 {
		query = append(query, fmt.Sprintf("limit=%d", limit))
	}
	if len(query) > 0 {
		path += "?" + strings.Join(query, "&")
	}
	var out TaskList
	if err := c.do(ctx, "GET", path, nil, &out); err != nil {
		return nil, err
	}
	return out.Tasks, nil
}

func (c *Client) ListAudit(ctx context.Context, limit int) ([]AuditEntry, error) {
	path := "audit"
	if limit > 0 {
		path += fmt.Sprintf("?limit=%d", limit)
	}
	var out AuditList
	if err := c.do(ctx, "GET", path, nil, &out); err != nil {
		return nil, err
	}
	return out.Entries, nil
}

type MintTokenRequest struct {
	Note string `json:"note,omitempty"`
}

type MintTokenResponse struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
}

func (c *Client) MintEnrollmentToken(ctx context.Context, req MintTokenRequest) (*MintTokenResponse, error) {
	var out MintTokenResponse
	if err := c.do(ctx, "POST", "enrollment-tokens", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
