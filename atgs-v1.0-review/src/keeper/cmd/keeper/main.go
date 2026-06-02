// Keeper is the ATGS daemon that runs on a host contributing hardware to the
// platform. It enrolls with Central on first run, then maintains a persistent
// outbound control channel to receive and execute tasks.
//
// Phase 6: the keeper is now a single binary that can run in two modes.
//
//   Headless (--headless or ATGS_KEEPER_HEADLESS=true):
//     Pure daemon. No window, no tray. Used in Docker images and on
//     server installs where no display server is present.
//
//   GUI (default on desktop platforms):
//     Full Wails app. Tray icon with quick pause/unpause, main window
//     with resource meters and instance list. Intended for non-technical
//     hosts on Windows and Linux desktops.
//
// Environment:
//
//	ATGS_KEEPER_CENTRAL_URL      e.g. https://127.0.0.1:8443
//	ATGS_ENROLL_TOKEN            one-time token (first run only)
//	ATGS_KEEPER_STATE_DIR        identity + local db (default: ./.atgs-keeper)
//	ATGS_KEEPER_EGGS_DIR         egg manifests (default: ./eggs)
//	ATGS_KEEPER_DATA_ROOT        per-instance volumes (default: <state_dir>/instances)
//	ATGS_KEEPER_INSECURE_TLS     set to "false" in production (default: "true")
//	ATGS_KEEPER_HEADLESS         "true" to force headless mode (default: "false")
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/xkstudios/atgs/keeper/internal/config"
	"github.com/xkstudios/atgs/keeper/internal/daemon"
)

var version = "0.6.0-phase6"

func main() {
	// Subcommand: `keeper init` runs the first-run wizard then exits.
	if len(os.Args) > 1 && os.Args[1] == "init" {
		if err := runInit(); err != nil {
			fmt.Fprintln(os.Stderr, "init:", err)
			os.Exit(1)
		}
		return
	}

	headless := flag.Bool("headless", false, "Run without tray/window UI")
	flag.Parse()
	if os.Getenv("ATGS_KEEPER_HEADLESS") == "true" {
		*headless = true
	}

	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	log.Info("keeper starting", "version", version, "headless", *headless)

	cfg, err := config.Load()
	if err != nil {
		log.Error("load config", "err", err)
		os.Exit(1)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	rt, err := daemon.Boot(ctx, cfg, log)
	if err != nil {
		log.Error("daemon boot", "err", err)
		os.Exit(1)
	}
	defer rt.Close()

	if *headless {
		if err := rt.Run(ctx); err != nil {
			log.Error("keeper ended with error", "err", err)
			os.Exit(1)
		}
		log.Info("keeper shut down cleanly")
		return
	}

	// GUI path. runGUI is defined in main_gui.go (desktop build tag) or
	// stubbed to fall back to headless mode (headless build tag).
	if err := runGUI(ctx, rt); err != nil {
		log.Error("keeper ended with error", "err", err)
		os.Exit(1)
	}
	log.Info("keeper shut down cleanly")
}
