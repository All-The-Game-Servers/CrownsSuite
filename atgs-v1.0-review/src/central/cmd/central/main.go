// Central is the ATGS control plane.
//
// Subcommands:
//
//	central serve                    run the HTTP and WebSocket servers
//	central migrate                  apply database migrations
//	central bootstrap-ca             one-time CA key + cert generation
//	central mint-enrollment-token    mint a single-use token for a new Keeper
//
// Dev defaults target 127.0.0.1 and a local Postgres. Production overrides
// every listener and the DB URL through ATGS_CENTRAL_* env vars.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/postgres"
	_ "github.com/golang-migrate/migrate/v4/source/file"
	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/api"
	"github.com/xkstudios/atgs/central/internal/auditsink"
	"github.com/xkstudios/atgs/central/internal/auth"
	"github.com/xkstudios/atgs/central/internal/authn"
	"github.com/xkstudios/atgs/central/internal/backupstore"
	"github.com/xkstudios/atgs/central/internal/config"
	"github.com/xkstudios/atgs/central/internal/crl"
	"github.com/xkstudios/atgs/central/internal/cryptoutil"
	"github.com/xkstudios/atgs/central/internal/dispatcher"
	"github.com/xkstudios/atgs/central/internal/ratelimit"
	"github.com/xkstudios/atgs/central/internal/restracker"
	"github.com/xkstudios/atgs/central/internal/routing"
	"github.com/xkstudios/atgs/central/internal/scheduler"
	"github.com/xkstudios/atgs/central/internal/signingkey"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/central/internal/wsmux"
	"github.com/xkstudios/atgs/shared/envelope"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	cmd := os.Args[1]

	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))

	cfg, err := config.Load()
	if err != nil {
		log.Error("load config", "err", err)
		os.Exit(1)
	}

	switch cmd {
	case "serve":
		if err := runServe(cfg, log); err != nil {
			log.Error("serve", "err", err)
			os.Exit(1)
		}
	case "migrate":
		if err := runMigrate(cfg, log); err != nil {
			log.Error("migrate", "err", err)
			os.Exit(1)
		}
	case "bootstrap-ca":
		if err := runBootstrapCA(cfg, log); err != nil {
			log.Error("bootstrap-ca", "err", err)
			os.Exit(1)
		}
	case "mint-enrollment-token":
		if err := runMintToken(cfg, log); err != nil {
			log.Error("mint-enrollment-token", "err", err)
			os.Exit(1)
		}
	case "mint-relay-cert":
		if err := runMintRelayCert(cfg, log); err != nil {
			log.Error("mint-relay-cert", "err", err)
			os.Exit(1)
		}
	case "mint-progenitor-cert":
		if err := runMintProgenitorCert(cfg, log); err != nil {
			log.Error("mint-progenitor-cert", "err", err)
			os.Exit(1)
		}
	case "create-admin":
		if err := runCreateAdmin(cfg, log); err != nil {
			log.Error("create-admin", "err", err)
			os.Exit(1)
		}
	case "setup":
		if err := runSetup(cfg, log); err != nil {
			log.Error("setup", "err", err)
			os.Exit(1)
		}
	case "-h", "--help", "help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", cmd)
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `central <command>

Commands:
  setup                     interactive first-run wizard (RECOMMENDED)
  serve                     run the control plane (admin + keeper listeners)
  migrate                   apply database migrations
  bootstrap-ca              generate the internal CA (run once)
  create-admin <email>      create an admin user (password via stdin)
  mint-enrollment-token     mint a single-use Keeper enrollment token
  mint-relay-cert           issue a cert+key bundle for a new relay
  mint-progenitor-cert      issue a cert+key bundle for a Progenitor install

Configuration is read from environment variables. See README.`)
}

func runServe(cfg *config.Config, log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	st, err := store.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("store: %w", err)
	}
	defer st.Close()

	ca, err := auth.LoadCA(cfg.CADir)
	if err != nil {
		return fmt.Errorf("load ca (run bootstrap-ca first?): %w", err)
	}

	// Phase 7: load the Ed25519 envelope signing key if present. Absent is
	// allowed during the transition window; strict mode is gated by
	// cfg.RequireSignedTasks elsewhere.
	signKey, err := signingkey.Load(cfg.CADir)
	if err != nil {
		return fmt.Errorf("load signing key: %w", err)
	}
	if signKey != nil {
		log.Info("central signing key loaded", "public_key_hex", signKey.PublicHex())
	} else {
		log.Warn("no central signing key found; signed envelopes disabled")
	}

	// Phase 7: load CRL. Missing file = empty list; operators don't need to
	// bootstrap it manually.
	crlList, err := crl.Load(cfg.CADir)
	if err != nil {
		return fmt.Errorf("load crl: %w", err)
	}
	log.Info("crl loaded", "dir", cfg.CADir, "entries", len(crlList.List()))

	// Phase 7: audit streaming sink
	if cfg.AuditSyslogPath != "" {
		sink, err := auditsink.NewFileSink(cfg.AuditSyslogPath, log)
		if err != nil {
			return fmt.Errorf("audit sink: %w", err)
		}
		store.SetAuditSink(sink)
		log.Info("audit sink configured", "path", cfg.AuditSyslogPath)
	}

	hubEvents := &api.HubEventHandler{Store: st, Log: log}
	hub := wsmux.NewHub(wsmux.Config{
		PingInterval:  cfg.PingInterval,
		PingTimeout:   cfg.PingTimeout,
		ServerVersion: cfg.ServerVersion,
	}, st, hubEvents)

	// Phase 7: wire signing policy. Signer is built from our loaded key;
	// verifier lookup reads each keeper's ed25519 pubkey from the store
	// at connect time.
	if signKey != nil {
		signer, err := envelope.NewSigner(signKey.Private)
		if err != nil {
			return fmt.Errorf("build central signer: %w", err)
		}
		lookup := func(keeperID uuid.UUID) *envelope.Verifier {
			raw, err := st.GetKeeperEd25519(context.Background(), keeperID)
			if err != nil || len(raw) == 0 {
				return nil
			}
			v, err := envelope.NewVerifier(raw, 0)
			if err != nil {
				log.Warn("verifier construction failed", "keeper_id", keeperID, "err", err)
				return nil
			}
			return v
		}
		hub.SetSigningPolicy(signer, lookup, cfg.RequireSignedTasks)
		log.Info("envelope signing enabled", "require_signed", cfg.RequireSignedTasks)
	} else if cfg.RequireSignedTasks {
		return fmt.Errorf("ATGS_CENTRAL_REQUIRE_SIGNED_TASKS is true but no signing key exists (run bootstrap-ca)")
	}

	routingPub := routing.NewPublisher(log)

	disp := dispatcher.New(hub, st, routingPub, log)
	hub.SetTaskHandler(disp)
	hubEvents.Dispatcher = disp // enable task flush on reconnect

	// Phase 7: per-keeper token bucket
	disp.SetLimiter(ratelimit.New(cfg.TaskRateLimitPerMin, cfg.TaskRateLimitBurst))
	log.Info("task rate limiter installed",
		"per_min", cfg.TaskRateLimitPerMin, "burst", cfg.TaskRateLimitBurst)

	// --- Phase 4: Backup subsystem ---
	fsBackend, err := backupstore.NewFSBackend(cfg.BackupRoot)
	if err != nil {
		return fmt.Errorf("backup fs backend: %w", err)
	}
	var masterKey *cryptoutil.MasterKey
	if cfg.BackupMasterKeyPath != "" {
		masterKey, err = cryptoutil.LoadMasterKey(cfg.BackupMasterKeyPath)
		if err != nil {
			return fmt.Errorf("load master key: %w", err)
		}
		log.Info("backup master key loaded", "path", cfg.BackupMasterKeyPath)
	} else {
		log.Info("backup master key not configured; encrypted backups will be rejected")
	}
	defaultMode := store.BackupStorageCentralFS
	if cfg.BackupStorageDefault == "object_storage" {
		defaultMode = store.BackupStorageObject
	}
	// keeperURL is the base URL for chunk upload. Keepers use their mTLS
	// identity to reach Central's keeper listener; chunks are served from
	// /api/v1/chunks/ there.
	keeperURL := fmt.Sprintf("https://%s/api/v1/chunks", cfg.KeeperListenAddr)
	backups := api.NewBackupHandlers(st, disp, fsBackend, masterKey, defaultMode, cfg.BackupChunkSize, keeperURL, log)

	srv := &api.Server{
		Cfg:              cfg,
		Store:            st,
		CA:               ca,
		Hub:              hub,
		Dispatcher:       disp,
		RoutingPublisher: routingPub,
		BackupHandlers:   backups,
		SigningKey:       signKey,
		CRL:              crlList,
		Log:              log,
	}

	tlsCfg, err := srv.KeeperTLSConfig()
	if err != nil {
		return fmt.Errorf("tls config: %w", err)
	}

	adminSrv := &http.Server{
		Addr:              cfg.AdminListenAddr,
		Handler:           srv.AdminHandler(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	keeperSrv := &http.Server{
		Addr:              cfg.KeeperListenAddr,
		Handler:           srv.KeeperListenerHandler(),
		TLSConfig:         tlsCfg,
		ReadHeaderTimeout: 10 * time.Second,
	}

	errCh := make(chan error, 3)
	go func() {
		log.Info("admin listener up", "addr", cfg.AdminListenAddr)
		if err := adminSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- fmt.Errorf("admin: %w", err)
		}
	}()
	go func() {
		log.Info("keeper listener up", "addr", cfg.KeeperListenAddr, "tls", true)
		// Empty cert/key file args: TLSConfig.Certificates is populated.
		if err := keeperSrv.ListenAndServeTLS("", ""); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- fmt.Errorf("keeper: %w", err)
		}
	}()
	// Phase 4: backup scheduler
	sched := scheduler.New(scheduler.Config{
		Store:        st,
		Dispatcher:   disp,
		MasterKey:    masterKey,
		DefaultMode:  defaultMode,
		ChunkSize:    cfg.BackupChunkSize,
		ChunkBaseURL: keeperURL,
		Log:          log,
	})
	go func() {
		if err := sched.Run(ctx); err != nil {
			errCh <- fmt.Errorf("scheduler: %w", err)
		}
	}()

	// Phase 7: resource cap tracker
	tracker := restracker.New(st, log, 2*time.Minute)
	go func() {
		if err := tracker.Run(ctx); err != nil {
			errCh <- fmt.Errorf("restracker: %w", err)
		}
	}()

	// Phase 8: admin bootstrap (first boot only, idempotent on restart)
	if err := bootstrapAdminIfNeeded(ctx, st, cfg, log); err != nil {
		return fmt.Errorf("admin bootstrap: %w", err)
	}

	// Phase 8: periodic expired-session janitor
	go runSessionCleanup(ctx, st, cfg.SessionCleanupTick, log)

	select {
	case <-ctx.Done():
		log.Info("shutdown requested")
	case err := <-errCh:
		log.Error("listener failed", "err", err)
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = adminSrv.Shutdown(shutdownCtx)
	_ = keeperSrv.Shutdown(shutdownCtx)
	return nil
}

func runMigrate(cfg *config.Config, log *slog.Logger) error {
	m, err := migrate.New("file://migrations", cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("migrate new: %w", err)
	}
	defer m.Close()
	if err := m.Up(); err != nil {
		if errors.Is(err, migrate.ErrNoChange) {
			log.Info("migrate: no change")
			return nil
		}
		return err
	}
	log.Info("migrate: applied")
	return nil
}

func runBootstrapCA(cfg *config.Config, log *slog.Logger) error {
	ca, err := auth.Bootstrap(cfg.CADir)
	if err != nil {
		return err
	}
	log.Info("ca bootstrapped", "dir", cfg.CADir)

	// Phase 7: also generate the Ed25519 envelope signing key. Lives in the
	// same dir as the CA for operator convenience; Phase 7 admin docs will
	// point to both files as the critical secrets to protect.
	sk, err := signingkey.Bootstrap(cfg.CADir)
	if err != nil {
		return fmt.Errorf("bootstrap signing key: %w", err)
	}
	log.Info("central signing key generated",
		"public_key_hex", sk.PublicHex(),
		"hint", "Keepers will receive this automatically at enrollment")

	// Print the CA cert so operators can pin it out of band if they want.
	fmt.Println(string(ca.CertPEM()))
	fmt.Printf("\nCentral Ed25519 public key:\n  %s\n", sk.PublicHex())
	return nil
}

func runMintRelayCert(cfg *config.Config, log *slog.Logger) error {
	// Usage: central mint-relay-cert <output-dir>
	//
	// Writes the bundle files (relay.id, client.crt, client.key, ca.crt)
	// into output-dir with correct permissions. The operator then copies
	// that directory to the relay host as its StateDir.
	if len(os.Args) < 3 {
		return errors.New("usage: central mint-relay-cert <output-dir>")
	}
	outDir := os.Args[2]
	if err := os.MkdirAll(outDir, 0o700); err != nil {
		return fmt.Errorf("mkdir %s: %w", outDir, err)
	}

	ca, err := auth.LoadCA(cfg.CADir)
	if err != nil {
		return fmt.Errorf("load ca (run bootstrap-ca first?): %w", err)
	}

	relayID := uuid.NewString()
	bundle, err := ca.IssueRelayCert(relayID)
	if err != nil {
		return fmt.Errorf("issue: %w", err)
	}

	writes := []struct {
		name string
		data []byte
		perm os.FileMode
	}{
		{"relay.id", []byte(bundle.RelayID + "\n"), 0o644},
		{"client.crt", bundle.CertPEM, 0o644},
		{"client.key", bundle.KeyPEM, 0o600},
		{"ca.crt", bundle.CACertPEM, 0o644},
	}
	for _, w := range writes {
		path := filepath.Join(outDir, w.name)
		if err := os.WriteFile(path, w.data, w.perm); err != nil {
			return fmt.Errorf("write %s: %w", path, err)
		}
	}
	log.Info("relay cert minted",
		"relay_id", relayID,
		"output_dir", outDir,
		"cert_not_after", time.Now().Add(auth.RelayCertLifetime).Format(time.RFC3339))
	fmt.Printf("\nRelay bundle written to %s\n", outDir)
	fmt.Printf("  relay_id: %s\n", relayID)
	fmt.Printf("  cert valid until: %s\n", time.Now().Add(auth.RelayCertLifetime).Format(time.RFC3339))
	fmt.Printf("\nCopy this directory to the relay host as its ATGS_RELAY_STATE_DIR.\n")
	return nil
}

func runMintProgenitorCert(cfg *config.Config, log *slog.Logger) error {
	// Usage: central mint-progenitor-cert <output-dir>
	//
	// Writes the bundle files (progenitor.id, client.crt, client.key, ca.crt)
	// into output-dir. The operator imports that bundle into the Progenitor
	// desktop app at first launch.
	if len(os.Args) < 3 {
		return errors.New("usage: central mint-progenitor-cert <output-dir>")
	}
	outDir := os.Args[2]
	if err := os.MkdirAll(outDir, 0o700); err != nil {
		return fmt.Errorf("mkdir %s: %w", outDir, err)
	}

	ca, err := auth.LoadCA(cfg.CADir)
	if err != nil {
		return fmt.Errorf("load ca (run bootstrap-ca first?): %w", err)
	}

	progenitorID := uuid.NewString()
	bundle, err := ca.IssueProgenitorCert(progenitorID)
	if err != nil {
		return fmt.Errorf("issue: %w", err)
	}

	writes := []struct {
		name string
		data []byte
		perm os.FileMode
	}{
		{"progenitor.id", []byte(bundle.ProgenitorID + "\n"), 0o644},
		{"client.crt", bundle.CertPEM, 0o644},
		{"client.key", bundle.KeyPEM, 0o600},
		{"ca.crt", bundle.CACertPEM, 0o644},
	}
	for _, w := range writes {
		path := filepath.Join(outDir, w.name)
		if err := os.WriteFile(path, w.data, w.perm); err != nil {
			return fmt.Errorf("write %s: %w", path, err)
		}
	}
	log.Info("progenitor cert minted",
		"progenitor_id", progenitorID,
		"output_dir", outDir,
		"cert_not_after", time.Now().Add(auth.ProgenitorCertLifetime).Format(time.RFC3339))
	fmt.Printf("\nProgenitor bundle written to %s\n", outDir)
	fmt.Printf("  progenitor_id: %s\n", progenitorID)
	fmt.Printf("  cert valid until: %s\n", time.Now().Add(auth.ProgenitorCertLifetime).Format(time.RFC3339))
	fmt.Printf("\nImport this directory into the Progenitor Console at first launch.\n")
	return nil
}

func runMintToken(cfg *config.Config, log *slog.Logger) error {
	// Call our own admin endpoint locally. Avoids duplicating handler logic.
	// Requires the server to already be running. For CLI ergonomics we also
	// support a direct-DB mode as a fallback.
	_ = log
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		"http://"+cfg.AdminListenAddr+"/api/v1/enrollment-tokens", nil)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("POST /api/v1/enrollment-tokens: %w (is central serve running?)", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		return fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var out struct {
		Token     string    `json:"token"`
		ExpiresAt time.Time `json:"expires_at"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return err
	}
	fmt.Printf("Enrollment token (expires %s):\n%s\n",
		out.ExpiresAt.Format(time.RFC3339), out.Token)
	return nil
}

// runCreateAdmin is the `central create-admin <email>` subcommand. Reads the
// password from stdin (or ATGS_ADMIN_PASSWORD_STDIN) so it doesn't land in
// shell history. Creates a single admin user; subsequent users are created
// via the /api/v1/users endpoint by that admin.
func runCreateAdmin(cfg *config.Config, log *slog.Logger) error {
	if len(os.Args) < 3 {
		return errors.New("usage: central create-admin <email>")
	}
	email := strings.TrimSpace(strings.ToLower(os.Args[2]))
	if email == "" {
		return errors.New("email required")
	}

	// Password: env first (for CI/scripting), then prompt.
	password := os.Getenv("ATGS_ADMIN_PASSWORD_STDIN")
	if password == "" {
		fmt.Fprint(os.Stderr, "password: ")
		buf, err := io.ReadAll(os.Stdin)
		if err != nil {
			return fmt.Errorf("read password: %w", err)
		}
		password = strings.TrimRight(string(buf), "\r\n")
	}
	if len(password) < 12 {
		return errors.New("password must be at least 12 characters")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	st, err := store.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("store: %w", err)
	}
	defer st.Close()

	hash, err := authn.HashPassword(password)
	if err != nil {
		return fmt.Errorf("hash: %w", err)
	}
	id, err := st.CreateUser(ctx, email, hash, "admin")
	if err != nil {
		return fmt.Errorf("create user: %w", err)
	}
	log.Info("admin created", "user_id", id, "email", email)
	return nil
}

// bootstrapAdminIfNeeded creates the initial admin user on first boot if:
//   - ATGS_CENTRAL_ADMIN_EMAIL and ATGS_CENTRAL_ADMIN_PASSWORD are both set
//   - AND the users table is currently empty
//
// This is idempotent: a second boot with the same env vars does nothing.
func bootstrapAdminIfNeeded(ctx context.Context, st *store.Store, cfg *config.Config, log *slog.Logger) error {
	if cfg.AdminBootstrapEmail == "" || cfg.AdminBootstrapPassword == "" {
		return nil
	}
	// Any user existing means bootstrap has happened.
	users, err := st.ListUsers(ctx)
	if err != nil {
		return fmt.Errorf("list users: %w", err)
	}
	if len(users) > 0 {
		log.Debug("admin bootstrap skipped (users already exist)")
		return nil
	}
	hash, err := authn.HashPassword(cfg.AdminBootstrapPassword)
	if err != nil {
		return fmt.Errorf("hash bootstrap password: %w", err)
	}
	email := strings.TrimSpace(strings.ToLower(cfg.AdminBootstrapEmail))
	id, err := st.CreateUser(ctx, email, hash, "admin")
	if err != nil {
		return fmt.Errorf("create bootstrap admin: %w", err)
	}
	log.Warn("BOOTSTRAP ADMIN CREATED — change the password immediately",
		"user_id", id, "email", email)
	return nil
}

// runSessionCleanup is a periodic janitor that purges expired sessions.
func runSessionCleanup(ctx context.Context, st *store.Store, tick time.Duration, log *slog.Logger) {
	if tick <= 0 {
		tick = 15 * time.Minute
	}
	t := time.NewTicker(tick)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			n, err := st.PurgeExpiredSessions(ctx)
			if err != nil {
				log.Warn("session cleanup failed", "err", err)
				continue
			}
			if n > 0 {
				log.Info("session cleanup", "purged", n)
			}
		}
	}
}
