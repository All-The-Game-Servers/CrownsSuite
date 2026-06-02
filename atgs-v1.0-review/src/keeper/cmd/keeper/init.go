package main

import (
	"bufio"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

// runInit is `keeper init`. Walks the operator through setting up a fresh
// Keeper install: Central URL, enrollment token, state directory, Docker
// check. Writes the values to a .env file that `keeper` reads on launch.
//
// Idempotent: running it again overwrites the .env but leaves keeper state
// (identity, instances) alone. Operators who already enrolled don't lose
// their keeper; they just update the config.
func runInit() error {
	fmt.Println()
	fmt.Println("════════════════════════════════════════════════")
	fmt.Println("   ATGS Keeper — First-Run Setup")
	fmt.Println("════════════════════════════════════════════════")
	fmt.Println()

	sc := bufio.NewScanner(os.Stdin)
	prompt := func(msg string) string {
		fmt.Print(msg)
		if !sc.Scan() {
			return ""
		}
		return strings.TrimSpace(sc.Text())
	}

	// Central URL
	centralURL := prompt("Central URL (e.g. https://atgs.example.com:8443): ")
	if centralURL == "" {
		return fmt.Errorf("central URL required")
	}
	u, err := url.Parse(centralURL)
	if err != nil || u.Scheme != "https" {
		return fmt.Errorf("central URL must be a valid https:// URL")
	}
	centralURL = strings.TrimRight(centralURL, "/")

	// Enrollment token
	fmt.Println()
	fmt.Println("Enrollment token: this is the one-time token from Central.")
	fmt.Println("Get it by running `central setup` on the central host, or")
	fmt.Println("POST /api/v1/enrollment-tokens on a running central.")
	token := prompt("Token: ")
	if token == "" {
		return fmt.Errorf("enrollment token required")
	}
	if len(token) < 32 {
		return fmt.Errorf("token looks wrong (length %d, expected 64)", len(token))
	}

	// State dir
	defaultState := defaultStateDirFor()
	stateDir := prompt(fmt.Sprintf("State directory [%s]: ", defaultState))
	if stateDir == "" {
		stateDir = defaultState
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return fmt.Errorf("create state dir: %w", err)
	}

	// Eggs dir
	defaultEggs := "./eggs"
	eggsDir := prompt(fmt.Sprintf("Eggs directory [%s]: ", defaultEggs))
	if eggsDir == "" {
		eggsDir = defaultEggs
	}

	// Warn about Docker
	fmt.Println()
	fmt.Println("Docker: the keeper spawns game server containers via Docker.")
	fmt.Println("  On this machine you'll need:")
	fmt.Println("    - Docker Engine (Linux) or Docker Desktop (Win/Mac) installed")
	fmt.Println("    - This user able to run `docker ps` without sudo")
	fmt.Println()
	if !promptYN(sc, "Continue (y/N)? ") {
		return fmt.Errorf("aborted")
	}

	// Write .env file next to the binary
	exe, _ := os.Executable()
	envPath := filepath.Join(filepath.Dir(exe), "keeper.env")
	envContent := fmt.Sprintf(`# ATGS Keeper config — written by 'keeper init'
# Any of these can be overridden at runtime via environment variable.

ATGS_KEEPER_CENTRAL_URL=%s
ATGS_ENROLL_TOKEN=%s
ATGS_KEEPER_STATE_DIR=%s
ATGS_KEEPER_EGGS_DIR=%s
ATGS_KEEPER_INSECURE_TLS=false
`, centralURL, token, stateDir, eggsDir)

	if err := os.WriteFile(envPath, []byte(envContent), 0o600); err != nil {
		return fmt.Errorf("write %s: %w", envPath, err)
	}

	fmt.Println()
	fmt.Println("════════════════════════════════════════════════")
	fmt.Println("   Setup Complete")
	fmt.Println("════════════════════════════════════════════════")
	fmt.Println()
	fmt.Printf("Config written to:\n  %s\n", envPath)
	fmt.Println()
	fmt.Println("To start the keeper:")
	fmt.Println()
	fmt.Printf("  set -a; . %s; set +a\n", envPath)
	fmt.Printf("  %s\n", exe)
	fmt.Println()
	fmt.Println("The keeper will enroll with Central on first launch and then")
	fmt.Println("maintain a persistent control channel.")
	fmt.Println()
	return nil
}

func promptYN(sc *bufio.Scanner, msg string) bool {
	fmt.Print(msg)
	if !sc.Scan() {
		return false
	}
	ans := strings.ToLower(strings.TrimSpace(sc.Text()))
	return ans == "y" || ans == "yes"
}

func defaultStateDirFor() string {
	// Mirror config.defaultStateDir logic without pulling in the private helper.
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, ".atgs-keeper")
	}
	return "./.atgs-keeper"
}
