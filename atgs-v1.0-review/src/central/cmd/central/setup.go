package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/authn"
	"github.com/xkstudios/atgs/central/internal/config"
	"github.com/xkstudios/atgs/central/internal/store"
)

// runSetup is `central setup`. A first-run wizard that handles the sequence
// of steps an operator would otherwise have to run manually:
//
//  1. Check Postgres is reachable
//  2. Run migrations
//  3. Bootstrap CA + signing key (if not already done)
//  4. Create admin user (if none exists)
//  5. Mint a first enrollment token for the operator's first keeper
//  6. Optionally generate a systemd unit file
//  7. Print a summary with next steps
//
// The wizard is idempotent: running it twice is safe. Each step checks
// whether the output already exists and skips if so.
func runSetup(cfg *config.Config, log *slog.Logger) error {
	const dbTimeout = 10 * time.Second

	openStore := func() (*store.Store, error) {
		ctx, cancel := context.WithTimeout(context.Background(), dbTimeout)
		defer cancel()
		return store.New(ctx, cfg.DatabaseURL)
	}
	withDBTimeout := func(fn func(context.Context) error) error {
		ctx, cancel := context.WithTimeout(context.Background(), dbTimeout)
		defer cancel()
		return fn(ctx)
	}

	fmt.Println()
	fmt.Println("================================================")
	fmt.Println("   ATGS Central - First-Run Setup")
	fmt.Println("================================================")
	fmt.Println()

	// Step 1 - Postgres connectivity
	fmt.Print("Checking Postgres connection... ")
	st, err := openStore()
	if err != nil {
		fmt.Println("FAILED")
		fmt.Println()
		fmt.Printf("  Could not connect to %s\n", cfg.DatabaseURL)
		fmt.Println("  Ensure Postgres is running and the ATGS_CENTRAL_DATABASE_URL")
		fmt.Println("  env var points to a reachable database with a user that")
		fmt.Println("  has CREATE privileges.")
		fmt.Println()
		fmt.Println("  Example for local dev:")
		fmt.Println("    createdb atgs")
		fmt.Println("    createuser atgs -s")
		fmt.Println("    export ATGS_CENTRAL_DATABASE_URL=\"postgres://atgs:atgs@localhost/atgs?sslmode=disable\"")
		return err
	}
	defer st.Close()
	fmt.Println("OK")

	// Step 2 - Migrations
	fmt.Print("Applying database migrations... ")
	if err := runMigrate(cfg, log); err != nil {
		fmt.Println("FAILED")
		return err
	}
	fmt.Println("OK")

	// Reopen store post-migration so it picks up fresh schema.
	st.Close()
	st, err = openStore()
	if err != nil {
		return fmt.Errorf("reopen store: %w", err)
	}

	// Step 3 - CA + signing key
	caFile := filepath.Join(cfg.CADir, "ca.crt")
	if _, err := os.Stat(caFile); os.IsNotExist(err) {
		fmt.Print("Generating CA and signing key... ")
		if err := runBootstrapCA(cfg, log); err != nil {
			fmt.Println("FAILED")
			return err
		}
		fmt.Println("OK")
	} else {
		fmt.Println("CA already exists, skipping generation")
	}

	// Step 4 - Admin user
	var users []store.User
	if err := withDBTimeout(func(ctx context.Context) error {
		var err error
		users, err = st.ListUsers(ctx)
		return err
	}); err != nil {
		return fmt.Errorf("list users: %w", err)
	}
	if len(users) == 0 {
		fmt.Println()
		fmt.Println("Creating initial admin user.")
		email := prompt("Admin email: ")
		if email == "" {
			return fmt.Errorf("admin email required")
		}
		password := prompt("Admin password (min 12 chars): ")
		if len(password) < 12 {
			return fmt.Errorf("password must be at least 12 characters")
		}
		hash, err := authn.HashPassword(password)
		if err != nil {
			return fmt.Errorf("hash password: %w", err)
		}
		var id uuid.UUID
		if err := withDBTimeout(func(ctx context.Context) error {
			var err error
			id, err = st.CreateUser(ctx, strings.ToLower(strings.TrimSpace(email)), hash, "admin")
			return err
		}); err != nil {
			return fmt.Errorf("create admin: %w", err)
		}
		fmt.Printf("Admin user created (id=%s)\n", id)
	} else {
		fmt.Printf("Admin user already exists (%d users total), skipping\n", len(users))
	}

	// Step 5 - First enrollment token
	fmt.Print("Minting first enrollment token... ")
	tokenRaw := make([]byte, 32)
	if _, err := rand.Read(tokenRaw); err != nil {
		return fmt.Errorf("token rand: %w", err)
	}
	token := hex.EncodeToString(tokenRaw)
	tokenHash := store.HashToken(token)
	tokenExpiresAt := time.Now().Add(cfg.EnrollmentTokenTTL)
	if err := withDBTimeout(func(ctx context.Context) error {
		return st.CreateEnrollmentToken(ctx, store.CreateEnrollmentTokenParams{
			TokenHash:   tokenHash,
			WorkspaceID: store.DefaultWorkspaceUUID,
			CreatedBy:   "setup-wizard",
			Note:        "first keeper",
			ExpiresAt:   tokenExpiresAt,
		})
	}); err != nil {
		return fmt.Errorf("create enrollment token: %w", err)
	}
	fmt.Println("OK")

	// Step 6 - Systemd unit (optional)
	fmt.Println()
	if promptYN("Install systemd unit for Central? (y/N): ") {
		if os.Geteuid() != 0 {
			fmt.Println("Installing the systemd unit requires root. Either re-run setup with sudo, or skip this step and set up systemd manually later.")
			if !promptYN("Skip systemd setup and continue? (y/N): ") {
				return fmt.Errorf("aborted: systemd install requires root")
			}
		} else {
			binPath, _ := os.Executable()
			unitPath := "/etc/systemd/system/atgs-central.service"
			unit := fmt.Sprintf(`[Unit]
Description=ATGS Central
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
ExecStart=%s serve
Restart=on-failure
RestartSec=5s
Environment=ATGS_CENTRAL_CA_DIR=%s
Environment=ATGS_CENTRAL_DATABASE_URL=%s

[Install]
WantedBy=multi-user.target
`, binPath, cfg.CADir, cfg.DatabaseURL)
			if err := os.WriteFile(unitPath, []byte(unit), 0o644); err != nil {
				return fmt.Errorf("write %s: %w", unitPath, err)
			}
			fmt.Printf("  Unit file written to %s\n", unitPath)
			fmt.Println("  Run: systemctl daemon-reload && systemctl enable --now atgs-central")
		}
	}

	// Step 7 - Summary
	fmt.Println()
	fmt.Println("================================================")
	fmt.Println("   Setup Complete")
	fmt.Println("================================================")
	fmt.Println()
	fmt.Println("Next steps:")
	fmt.Println()
	fmt.Println("  1. Start the server (if systemd wasn't installed):")
	fmt.Printf("       %s serve\n", binPathBest())
	fmt.Println()
	fmt.Println("  2. Enroll your first keeper using this token:")
	fmt.Println()
	fmt.Printf("       %s\n", token)
	fmt.Println()
	fmt.Printf("     (expires %s)\n", tokenExpiresAt.Format(time.RFC1123))
	fmt.Println()
	fmt.Println("     On the keeper host:")
	fmt.Println("       export ATGS_ENROLL_TOKEN=<token above>")
	fmt.Printf("       export ATGS_KEEPER_CENTRAL_URL=https://%s\n", cfg.KeeperListenAddr)
	fmt.Println("       keeper")
	fmt.Println()
	fmt.Println("  3. Mint a Progenitor cert bundle for remote admin:")
	fmt.Printf("       %s mint-progenitor-cert /tmp/prog-bundle\n", binPathBest())
	fmt.Println()
	fmt.Println("  4. Admin API is available at:")
	fmt.Printf("       http://%s\n", cfg.AdminListenAddr)
	fmt.Println()
	return nil
}

// stdinScanner is shared across prompt calls so piped input reads
// one line per prompt reliably. Creating a new scanner per call would
// cause bufio to over-read the underlying stdin reader.
var stdinScanner = bufio.NewScanner(os.Stdin)

// prompt reads one line from stdin.
func prompt(msg string) string {
	fmt.Print(msg)
	if !stdinScanner.Scan() {
		return ""
	}
	return strings.TrimSpace(stdinScanner.Text())
}

// promptYN returns true if the user typed y or yes (case-insensitive).
func promptYN(msg string) bool {
	ans := strings.ToLower(prompt(msg))
	return ans == "y" || ans == "yes"
}

// binPathBest returns the executable path for use in docs, falling back
// to "central" if we can't resolve it.
func binPathBest() string {
	p, err := os.Executable()
	if err != nil {
		return "central"
	}
	return p
}
