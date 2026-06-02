// Package api implements Central's HTTP surface.
//
// Two logical endpoints in Phase 1:
//
//  1. Admin API on the admin listener (Progenitor-facing). No auth yet in
//     Phase 1 (see TODO). Runs plain HTTP in dev; production should
//     front it with a reverse proxy that terminates TLS and does auth.
//
//  2. Keeper API on the keeper listener (mTLS). Holds:
//     POST /api/v1/enroll     (public to anyone with a valid token)
//     GET  /ws                (requires mTLS client cert)
//
// Splitting listeners means we can bind different certs and different auth
// rules without interleaving middleware. The tradeoff is two HTTP servers
// in one process, which is fine.
package api

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/auth"
	"github.com/xkstudios/atgs/central/internal/config"
	"github.com/xkstudios/atgs/central/internal/crl"
	"github.com/xkstudios/atgs/central/internal/dispatcher"
	"github.com/xkstudios/atgs/central/internal/routing"
	"github.com/xkstudios/atgs/central/internal/signingkey"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/central/internal/wsmux"
	sharedpki "github.com/xkstudios/atgs/shared/pki"
	"github.com/xkstudios/atgs/shared/protocol"
)

// Server bundles the handlers and their dependencies.
type Server struct {
	Cfg              *config.Config
	Store            *store.Store
	CA               *auth.CA
	Hub              *wsmux.Hub
	Dispatcher       *dispatcher.Dispatcher
	RoutingPublisher *routing.Publisher
	BackupHandlers   *backupHandlers // Phase 4: optional, nil disables backups
	SigningKey       *signingkey.Key // Phase 7: envelope signing; optional (nil = sig disabled)
	CRL              *crl.List       // Phase 7: revocation list; optional
	Log              *slog.Logger
}

// --- Admin listener ---

// AdminHandler builds the mux for the admin API.
func (s *Server) AdminHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/version", s.handleVersion)

	// Phase 8: authentication (public — these ARE the auth endpoints)
	mux.HandleFunc("POST /api/v1/auth/login", s.handleLogin)
	mux.HandleFunc("POST /api/v1/auth/logout", s.handleLogout)
	mux.HandleFunc("GET /api/v1/auth/whoami", s.RequireUser(s.handleWhoami))

	// Phase 8: user management (admin only)
	mux.HandleFunc("POST /api/v1/users", s.RequireRole("admin", s.handleCreateUser))
	mux.HandleFunc("GET /api/v1/users", s.RequireRole("admin", s.handleListUsers))
	mux.HandleFunc("POST /api/v1/users/{id}/disable", s.RequireRole("admin", s.handleDisableUser))
	mux.HandleFunc("GET /api/v1/workspaces", s.RequireRole("admin", s.handleListWorkspaces))
	mux.HandleFunc("POST /api/v1/workspaces", s.RequireRole("admin", s.handleCreateWorkspace))
	mux.HandleFunc("GET /api/v1/workspaces/{id}/members", s.RequireUser(s.handleListWorkspaceMembers))
	mux.HandleFunc("POST /api/v1/workspaces/{id}/members", s.RequireUser(s.handleUpsertWorkspaceMember))
	mux.HandleFunc("POST /api/v1/keepers/{id}/workspace", s.RequireRole("admin", s.handleAssignKeeperWorkspace))

	// Workspace-aware client API for Keeper and future end-user clients.
	mux.HandleFunc("GET /api/v1/client/session", s.RequireUser(s.handleClientSession))
	mux.HandleFunc("GET /api/v1/client/workspaces", s.RequireUser(s.handleClientListWorkspaces))
	mux.HandleFunc("GET /api/v1/client/workspaces/{id}/keepers", s.RequireUser(s.handleClientListWorkspaceKeepers))
	mux.HandleFunc("GET /api/v1/client/workspaces/{id}/instances", s.RequireUser(s.handleClientListWorkspaceInstances))
	mux.HandleFunc("POST /api/v1/client/workspaces/{id}/keepers/{keeper_id}/instances", s.RequireUser(s.handleClientCreateInstance))
	mux.HandleFunc("POST /api/v1/client/workspaces/{id}/instances/{instance_id}/start", s.RequireUser(s.handleClientStartInstance))
	mux.HandleFunc("POST /api/v1/client/workspaces/{id}/instances/{instance_id}/stop", s.RequireUser(s.handleClientStopInstance))
	mux.HandleFunc("DELETE /api/v1/client/workspaces/{id}/instances/{instance_id}", s.RequireUser(s.handleClientDeleteInstance))
	mux.HandleFunc("GET /api/v1/client/workspaces/{id}/instances/{instance_id}/logs", s.RequireUser(s.handleClientInstanceLogs))
	mux.HandleFunc("POST /api/v1/client/workspaces/{id}/instances/{instance_id}/console", s.RequireUser(s.handleClientInstanceConsoleWrite))
	mux.HandleFunc("GET /api/v1/client/workspaces/{id}/instances/{instance_id}/files", s.RequireUser(s.handleClientInstanceFileList))
	mux.HandleFunc("GET /api/v1/client/workspaces/{id}/instances/{instance_id}/file", s.RequireUser(s.handleClientInstanceFileRead))
	mux.HandleFunc("PUT /api/v1/client/workspaces/{id}/instances/{instance_id}/file", s.RequireUser(s.handleClientInstanceFileWrite))
	mux.HandleFunc("DELETE /api/v1/client/workspaces/{id}/instances/{instance_id}/file", s.RequireUser(s.handleClientInstanceFileDelete))
	mux.HandleFunc("POST /api/v1/client/workspaces/{id}/instances/{instance_id}/file/rename", s.RequireUser(s.handleClientInstanceFileRename))
	if s.BackupHandlers != nil {
		mux.HandleFunc("GET /api/v1/client/workspaces/{id}/backups", s.RequireUser(s.handleClientListWorkspaceBackups))
		mux.HandleFunc("POST /api/v1/client/workspaces/{id}/instances/{instance_id}/backups", s.RequireUser(s.handleClientCreateBackup))
		mux.HandleFunc("GET /api/v1/client/workspaces/{id}/instances/{instance_id}/backups", s.RequireUser(s.handleClientListBackups))
		mux.HandleFunc("DELETE /api/v1/client/workspaces/{id}/backups/{backup_id}", s.RequireUser(s.handleClientDeleteBackup))
		mux.HandleFunc("POST /api/v1/client/workspaces/{id}/backups/{backup_id}/restore", s.RequireUser(s.handleClientRestoreBackup))
		mux.HandleFunc("POST /api/v1/client/workspaces/{id}/instances/{instance_id}/backup-schedule", s.RequireUser(s.handleClientCreateSchedule))
		mux.HandleFunc("GET /api/v1/client/workspaces/{id}/schedules", s.RequireUser(s.handleClientListSchedules))
	}

	mux.HandleFunc("POST /api/v1/enrollment-tokens", s.handleMintEnrollmentToken)
	mux.HandleFunc("GET /api/v1/keepers", s.handleListKeepers)
	mux.HandleFunc("POST /api/v1/keepers/{id}/revoke", s.handleRevokeKeeper)
	// Phase 2 routes:
	mux.HandleFunc("POST /api/v1/keepers/{id}/instances", s.handleCreateInstance)
	mux.HandleFunc("GET /api/v1/keepers/{id}/instances", s.handleListInstances)
	mux.HandleFunc("POST /api/v1/instances/{id}/start", s.handleStartInstance)
	mux.HandleFunc("POST /api/v1/instances/{id}/stop", s.handleStopInstance)
	mux.HandleFunc("DELETE /api/v1/instances/{id}", s.handleDeleteInstance)
	mux.HandleFunc("GET /api/v1/instances/{id}/logs", s.handleInstanceLogs)
	mux.HandleFunc("POST /api/v1/instances/{id}/console", s.handleInstanceConsoleWrite)
	mux.HandleFunc("GET /api/v1/tasks", s.handleListTasks)
	mux.HandleFunc("GET /api/v1/tasks/{id}", s.handleGetTask)
	// Phase 4 routes (only wired if BackupHandlers is configured):
	if s.BackupHandlers != nil {
		mux.HandleFunc("POST /api/v1/instances/{id}/backups", s.BackupHandlers.createBackup)
		mux.HandleFunc("GET /api/v1/instances/{id}/backups", s.BackupHandlers.listBackups)
		mux.HandleFunc("GET /api/v1/backups/{backup_id}", s.BackupHandlers.getBackup)
		mux.HandleFunc("DELETE /api/v1/backups/{backup_id}", s.BackupHandlers.deleteBackup)
		mux.HandleFunc("POST /api/v1/backups/{backup_id}/restore", s.BackupHandlers.restoreBackup)
		mux.HandleFunc("POST /api/v1/instances/{id}/backup-schedule", s.BackupHandlers.createSchedule)
	}
	return loggingMiddleware(s.Log, mux)
}

// --- Keeper listener ---

// KeeperListenerHandler is what actually mounts on the keeper listener port.
// It routes by path prefix:
//   - /api/v1/admin/*    -> ProgenitorHandler (requires OU=ATGS Progenitor)
//   - everything else    -> KeeperHandler (keeper / relay / enrollment)
//
// Both sub-trees share the listener's mTLS config; the individual handlers
// (relay-sync, progenitorAuthMiddleware) enforce their own OU requirements.
func (s *Server) KeeperListenerHandler() http.Handler {
	keeperH := s.KeeperHandler()
	progH := s.ProgenitorHandler()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/v1/admin/") {
			progH.ServeHTTP(w, r)
			return
		}
		keeperH.ServeHTTP(w, r)
	})
}

// KeeperHandler builds the mux for the Keeper-facing listener. Enrollment
// does NOT require mTLS (the Keeper has no cert yet); /ws does.
//
// /api/v1/relay-sync also lives here because it needs mTLS and the keeper
// listener is already set up for that. The handler itself further restricts
// to certs with OU=ATGS Relay.
func (s *Server) KeeperHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/enroll", s.handleEnroll)
	mux.HandleFunc("GET /ws", s.handleWebSocket)
	mux.Handle("GET /api/v1/relay-sync", &RelaySyncHandler{
		Store:             s.Store,
		Publisher:         s.RoutingPublisher,
		Log:               s.Log,
		Version:           s.Cfg.ServerVersion,
		SnapshotThreshold: 1000,
	})
	// Phase 4 chunk ingest/egress lives on the keeper listener so it rides
	// the existing mTLS plumbing. Requires a client cert chaining to Central's CA.
	if s.BackupHandlers != nil {
		mux.HandleFunc("PUT /api/v1/chunks/{sha256}", s.BackupHandlers.putChunk)
		mux.HandleFunc("HEAD /api/v1/chunks/{sha256}", s.BackupHandlers.headChunk)
		mux.HandleFunc("GET /api/v1/chunks/{sha256}", s.BackupHandlers.getChunk)
	}
	return loggingMiddleware(s.Log, mux)
}

// KeeperTLSConfig returns a *tls.Config for the Keeper listener.
//
// Requirements:
//   - Server presents a cert signed by Central's CA (so Keepers verify us).
//   - Client cert is REQUESTED but optional, verified if present. We cannot
//     require mTLS at the TLS layer because enrollment happens BEFORE the
//     Keeper has a cert. Handlers enforce mTLS per-route.
//
// For dev we issue Central a self-signed leaf under the same CA, using
// "localhost" and 127.0.0.1 as SANs.
func (s *Server) KeeperTLSConfig() (*tls.Config, error) {
	serverCertPEM, serverKeyPEM, err := devServerCert(s.CA)
	if err != nil {
		return nil, fmt.Errorf("build server cert: %w", err)
	}
	cert, err := tls.X509KeyPair(serverCertPEM, serverKeyPEM)
	if err != nil {
		return nil, fmt.Errorf("load server keypair: %w", err)
	}

	caPool := x509.NewCertPool()
	caPool.AppendCertsFromPEM(s.CA.CertPEM())

	cfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		ClientCAs:    caPool,
		ClientAuth:   tls.VerifyClientCertIfGiven,
		MinVersion:   tls.VersionTLS12,
	}

	// Phase 7: reject revoked client certs at handshake time. VerifyPeerCertificate
	// runs after standard chain validation succeeds; if empty we let
	// VerifyClientCertIfGiven's own behaviour (unauth-allowed paths like
	// /enroll) apply.
	if s.CRL != nil {
		cfg.VerifyPeerCertificate = func(_ [][]byte, chains [][]*x509.Certificate) error {
			if len(chains) == 0 || len(chains[0]) == 0 {
				return nil // no client cert presented; per-endpoint logic handles
			}
			leaf := chains[0][0]
			if s.CRL.IsRevoked(leaf) {
				return fmt.Errorf("client certificate %s has been revoked", leaf.SerialNumber.Text(16))
			}
			return nil
		}
	}

	return cfg, nil
}

// --- Handlers ---

type versionResp struct {
	ServerVersion   string `json:"server_version"`
	ProtocolVersion int    `json:"protocol_version"`
}

func (s *Server) handleVersion(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, versionResp{
		ServerVersion:   s.Cfg.ServerVersion,
		ProtocolVersion: protocol.ProtocolVersion,
	})
}

type mintEnrollmentTokenReq struct {
	Note        string `json:"note"`
	WorkspaceID string `json:"workspace_id,omitempty"`
}

type mintEnrollmentTokenResp struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
	Note      string    `json:"note"`
}

func (s *Server) handleMintEnrollmentToken(w http.ResponseWriter, r *http.Request) {
	// TODO(phase7): require Progenitor auth here. For Phase 1 the admin
	// listener is assumed to be bound to loopback.
	var req mintEnrollmentTokenReq
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&req)
	}

	raw, err := generateToken(32)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "token_gen", err.Error())
		return
	}
	workspaceID := store.DefaultWorkspaceUUID
	if strings.TrimSpace(req.WorkspaceID) != "" {
		workspaceID, err = uuid.Parse(strings.TrimSpace(req.WorkspaceID))
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_workspace_id", err.Error())
			return
		}
		if _, err := s.Store.GetWorkspace(r.Context(), workspaceID); err != nil {
			writeError(w, http.StatusNotFound, "workspace_not_found", err.Error())
			return
		}
	}
	expires := time.Now().Add(s.Cfg.EnrollmentTokenTTL)
	if err := s.Store.CreateEnrollmentToken(r.Context(), store.CreateEnrollmentTokenParams{
		TokenHash:   store.HashToken(raw),
		WorkspaceID: workspaceID,
		CreatedBy:   "progenitor:local", // Phase 1 stub
		Note:        req.Note,
		ExpiresAt:   expires,
	}); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	_ = s.Store.WriteAudit(r.Context(), store.AuditEntry{
		Kind:    "enrollment.minted",
		Actor:   "progenitor:local",
		Details: map[string]any{"note": req.Note, "expires_at": expires},
	})
	writeJSON(w, http.StatusCreated, mintEnrollmentTokenResp{
		Token:     raw,
		ExpiresAt: expires,
		Note:      req.Note,
	})
}

func (s *Server) handleEnroll(w http.ResponseWriter, r *http.Request) {
	var req protocol.EnrollmentRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "decode", err.Error())
		return
	}
	if req.Token == "" || req.CSRPEM == "" {
		writeError(w, http.StatusBadRequest, "invalid", "token and csr_pem are required")
		return
	}

	keeperID := uuid.New()

	// Sign the CSR before touching the database. If signing fails (bad CSR,
	// etc.) we haven't mutated any state.
	certPEM, err := s.CA.SignKeeper([]byte(req.CSRPEM), keeperID.String())
	if err != nil {
		writeError(w, http.StatusBadRequest, "sign_csr", err.Error())
		return
	}
	notAfter := time.Now().Add(sharedpki.KeeperCertLifetime)

	// Phase 7: parse the cert serial for CRL reference on revoke.
	var certSerialHex string
	if block, _ := pem.Decode(certPEM); block != nil {
		if parsed, err := x509.ParseCertificate(block.Bytes); err == nil && parsed.SerialNumber != nil {
			certSerialHex = strings.ToLower(parsed.SerialNumber.Text(16))
		}
	}

	displayName := strings.TrimSpace(req.Hostname)
	if displayName == "" {
		displayName = keeperID.String()[:8]
	}

	// Phase 7: parse the Keeper's Ed25519 public key. Validate it's exactly
	// 32 bytes; reject otherwise. Allow it to be absent during the rollout
	// window (policy gate is elsewhere).
	var ed25519Key []byte
	if req.Ed25519PublicKey != "" {
		ed25519Key, err = hex.DecodeString(req.Ed25519PublicKey)
		if err != nil {
			writeError(w, http.StatusBadRequest, "bad_ed25519", "ed25519_public_key must be hex-encoded")
			return
		}
		if len(ed25519Key) != ed25519.PublicKeySize {
			writeError(w, http.StatusBadRequest, "bad_ed25519",
				fmt.Sprintf("ed25519_public_key must be %d bytes", ed25519.PublicKeySize))
			return
		}
	}

	// Atomically: validate token, insert keeper, consume token. One transaction.
	if err := s.Store.CompleteEnrollment(r.Context(), store.HashToken(req.Token), store.CreateKeeperParams{
		ID:                   keeperID,
		DisplayName:          displayName,
		PublicKeyFingerprint: req.PublicKeyFingerprint,
		Platform:             req.Platform,
		Arch:                 req.Arch,
		Hostname:             req.Hostname,
		AgentVersion:         req.AgentVersion,
		CertNotAfter:         notAfter,
		Ed25519PublicKey:     ed25519Key,
		CertSerialHex:        certSerialHex,
	}); err != nil {
		switch {
		case errors.Is(err, store.ErrNotFound):
			writeError(w, http.StatusUnauthorized, "bad_token", "unknown token")
		case errors.Is(err, store.ErrTokenExpired):
			writeError(w, http.StatusUnauthorized, "expired_token", "token has expired")
		case errors.Is(err, store.ErrTokenAlreadyUsed):
			writeError(w, http.StatusUnauthorized, "used_token", "token already consumed")
		default:
			writeError(w, http.StatusInternalServerError, "store", err.Error())
		}
		return
	}

	_ = s.Store.WriteAudit(r.Context(), store.AuditEntry{
		Kind:     "enrollment.completed",
		Actor:    "keeper:" + keeperID.String(),
		KeeperID: &keeperID,
		Details: map[string]any{
			"platform":      req.Platform,
			"arch":          req.Arch,
			"hostname":      req.Hostname,
			"agent_version": req.AgentVersion,
			"fingerprint":   req.PublicKeyFingerprint,
		},
	})

	resp := protocol.EnrollmentResponse{
		KeeperID:          keeperID.String(),
		CertificatePEM:    string(certPEM),
		CACertificatePEM:  string(s.CA.CertPEM()),
		CentralWSEndpoint: deriveWSEndpoint(s.Cfg.KeeperListenAddr),
		CertNotAfterUnix:  notAfter.Unix(),
	}
	if s.SigningKey != nil {
		resp.CentralEd25519PublicKey = s.SigningKey.PublicHex()
	}
	writeJSON(w, http.StatusCreated, resp)
}

type keeperView struct {
	ID                   string               `json:"id"`
	WorkspaceID          string               `json:"workspace_id"`
	DisplayName          string               `json:"display_name"`
	Platform             string               `json:"platform"`
	Arch                 string               `json:"arch"`
	Hostname             string               `json:"hostname"`
	AgentVersion         string               `json:"agent_version"`
	CertNotAfter         time.Time            `json:"cert_not_after"`
	EnrolledAt           time.Time            `json:"enrolled_at"`
	LastSeenAt           *time.Time           `json:"last_seen_at,omitempty"`
	RevokedAt            *time.Time           `json:"revoked_at,omitempty"`
	Connected            bool                 `json:"connected"`
	PublicKeyFingerprint string               `json:"public_key_fingerprint"`
	Resources            *keeperResourcesView `json:"resources,omitempty"`
}

type keeperResourcesView struct {
	ReportedAt     time.Time `json:"reported_at"`
	CPUCores       int       `json:"cpu_cores"`
	CPUPercentUsed float64   `json:"cpu_percent_used"`
	MemTotalBytes  uint64    `json:"mem_total_bytes"`
	MemUsedBytes   uint64    `json:"mem_used_bytes"`
	DiskTotalBytes uint64    `json:"disk_total_bytes"`
	DiskUsedBytes  uint64    `json:"disk_used_bytes"`
}

func (s *Server) handleListKeepers(w http.ResponseWriter, r *http.Request) {
	keepers, err := s.Store.ListKeepers(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}
	liveSet := map[uuid.UUID]bool{}
	for _, id := range s.Hub.ConnectedKeepers() {
		liveSet[id] = true
	}
	out := make([]keeperView, 0, len(keepers))
	for _, k := range keepers {
		var resources *keeperResourcesView
		if snap, err := s.Store.LatestKeeperResourcesReport(r.Context(), k.ID); err != nil {
			s.Log.Warn("latest keeper resources lookup failed", "keeper_id", k.ID, "err", err)
		} else if snap != nil {
			resources = &keeperResourcesView{
				ReportedAt:     snap.At,
				CPUCores:       snap.CPUCores,
				CPUPercentUsed: snap.CPUPercent,
				MemTotalBytes:  snap.MemTotalBytes,
				MemUsedBytes:   snap.MemUsedBytes,
				DiskTotalBytes: snap.DiskTotalBytes,
				DiskUsedBytes:  snap.DiskUsedBytes,
			}
		}
		out = append(out, keeperView{
			ID:                   k.ID.String(),
			WorkspaceID:          k.WorkspaceID.String(),
			DisplayName:          k.DisplayName,
			Platform:             k.Platform,
			Arch:                 k.Arch,
			Hostname:             k.Hostname,
			AgentVersion:         k.AgentVersion,
			CertNotAfter:         k.CertNotAfter,
			EnrolledAt:           k.EnrolledAt,
			LastSeenAt:           k.LastSeenAt,
			RevokedAt:            k.RevokedAt,
			Connected:            liveSet[k.ID],
			PublicKeyFingerprint: k.PublicKeyFingerprint,
			Resources:            resources,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"keepers": out})
}

func (s *Server) handleRevokeKeeper(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := uuid.Parse(idStr)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_id", err.Error())
		return
	}

	// Optional reason from body
	var req struct {
		Reason string `json:"reason"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	if req.Reason == "" {
		req.Reason = "revoked via admin API"
	}

	// 1. Mark revoked in DB (existing behavior)
	if err := s.Store.RevokeKeeper(r.Context(), id); err != nil {
		writeError(w, http.StatusInternalServerError, "store", err.Error())
		return
	}

	// 2. Add to CRL so TLS handshake on reconnect fails. Look up the keeper's
	//    cert serial via their record.
	if s.CRL != nil {
		if serial, err := s.Store.GetKeeperCertSerial(r.Context(), id); err == nil && serial != nil {
			if err := s.CRL.Add(serial, req.Reason); err != nil {
				s.Log.Warn("CRL add failed", "keeper_id", id, "err", err)
			}
		} else {
			s.Log.Warn("no cert serial for keeper, CRL skipped", "keeper_id", id)
		}
	}

	// 3. Dispatch a revoke task so the keeper wipes its own state if online.
	if s.Dispatcher != nil {
		_, _, err := s.Dispatcher.SendTask(r.Context(), dispatcher.SendTaskParams{
			KeeperID: id,
			Kind:     protocol.TaskKeeperRevoke,
			Payload:  protocol.KeeperRevokePayload{Reason: req.Reason, Actor: "progenitor"},
		}, false)
		if err != nil {
			// Not fatal — CRL is the durable backstop.
			s.Log.Warn("dispatch revoke task failed", "keeper_id", id, "err", err)
		}
	}

	_ = s.Store.WriteAudit(r.Context(), store.AuditEntry{
		Kind:     "keeper.revoked",
		Actor:    "progenitor:local",
		KeeperID: &id,
		Details:  map[string]any{"reason": req.Reason},
	})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	// Require a client cert signed by our CA.
	if r.TLS == nil || len(r.TLS.VerifiedChains) == 0 || len(r.TLS.PeerCertificates) == 0 {
		writeError(w, http.StatusUnauthorized, protocol.ErrUnauthorized, "client certificate required")
		return
	}
	leaf := r.TLS.PeerCertificates[0]
	keeperID, err := uuid.Parse(leaf.Subject.CommonName)
	if err != nil {
		writeError(w, http.StatusUnauthorized, protocol.ErrUnauthorized, "invalid keeper id in cert CN")
		return
	}
	// Keeper must exist and not be revoked.
	keeper, err := s.Store.GetKeeper(r.Context(), keeperID)
	if err != nil {
		writeError(w, http.StatusUnauthorized, protocol.ErrUnauthorized, "unknown keeper")
		return
	}
	if keeper.RevokedAt != nil {
		writeError(w, http.StatusForbidden, "revoked", "keeper is revoked")
		return
	}

	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: false,
	})
	if err != nil {
		s.Log.Warn("ws accept failed", "err", err)
		return
	}

	remoteAddr := r.RemoteAddr
	// Long-lived. The handler does not return until the ws closes.
	if err := s.Hub.Serve(r.Context(), ws, keeperID, remoteAddr, s.Log); err != nil {
		s.Log.Debug("hub session ended", "keeper_id", keeperID, "err", err)
	}
}

// --- HubEventHandler ---

// HubEventHandler bridges wsmux.Hub events into the store + audit log.
// If Dispatcher is non-nil, it also flushes queued tasks to the keeper on
// reconnect.
type HubEventHandler struct {
	Store      *store.Store
	Dispatcher *dispatcher.Dispatcher
	Log        *slog.Logger
}

func (h *HubEventHandler) OnKeeperConnected(ctx context.Context, sessionID uuid.UUID, keeperID uuid.UUID, remoteAddr string, hello protocol.KeeperHello) {
	if err := h.Store.CreateSession(ctx, store.CreateSessionParams{
		SessionID:  sessionID,
		KeeperID:   keeperID,
		RemoteAddr: remoteAddr,
	}); err != nil {
		h.Log.Warn("create session failed", "err", err)
	}
	_ = h.Store.TouchKeeperLastSeen(ctx, keeperID)
	_ = h.Store.WriteAudit(ctx, store.AuditEntry{
		Kind:     "keeper.connected",
		Actor:    "keeper:" + keeperID.String(),
		KeeperID: &keeperID,
		Details: map[string]any{
			"session_id":    sessionID,
			"remote_addr":   remoteAddr,
			"agent_version": hello.AgentVersion,
		},
	})
	h.Log.Info("keeper connected",
		"keeper_id", keeperID, "session_id", sessionID,
		"remote_addr", remoteAddr, "agent", hello.AgentVersion)

	// Flush any tasks that were queued while this keeper was offline.
	if h.Dispatcher != nil {
		// Detach from the request-scoped ctx: flushing shouldn't be
		// cancelled by a single transient event.
		go h.Dispatcher.FlushQueued(context.Background(), keeperID)
	}
}

func (h *HubEventHandler) OnKeeperDisconnected(ctx context.Context, sessionID uuid.UUID, keeperID uuid.UUID, reason string) {
	if err := h.Store.EndSession(ctx, sessionID, reason); err != nil {
		h.Log.Warn("end session failed", "err", err)
	}
	_ = h.Store.WriteAudit(ctx, store.AuditEntry{
		Kind:     "keeper.disconnected",
		Actor:    "keeper:" + keeperID.String(),
		KeeperID: &keeperID,
		Details:  map[string]any{"session_id": sessionID, "reason": reason},
	})
	h.Log.Info("keeper disconnected", "keeper_id", keeperID, "session_id", sessionID, "reason", reason)
}

// --- helpers ---

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

type errResp struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, errResp{Error: code, Message: msg})
}

func generateToken(bytes int) (string, error) {
	b := make([]byte, bytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func deriveWSEndpoint(keeperListenAddr string) string {
	// In dev we publish wss://<listen>/ws. Production should override via
	// config once a public hostname is in play.
	host, port, err := net.SplitHostPort(keeperListenAddr)
	if err != nil {
		return "wss://" + keeperListenAddr + "/ws"
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	return fmt.Sprintf("wss://%s:%s/ws", host, port)
}

func loggingMiddleware(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusRecorder{ResponseWriter: w, status: 200}
		next.ServeHTTP(sw, r)
		log.Info("http",
			"method", r.Method,
			"path", r.URL.Path,
			"status", sw.status,
			"dur_ms", time.Since(start).Milliseconds(),
			"remote", r.RemoteAddr,
		)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

// Hijack forwards to the underlying ResponseWriter so WebSocket upgrades work
// through this middleware. coder/websocket needs the real ResponseWriter to
// implement http.Hijacker, which standard Go servers do.
func (s *statusRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := s.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("underlying ResponseWriter does not implement http.Hijacker")
	}
	return h.Hijack()
}

// Flush forwards to the underlying ResponseWriter for any streaming handlers.
func (s *statusRecorder) Flush() {
	if f, ok := s.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}
