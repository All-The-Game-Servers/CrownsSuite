//go:build !keeper_headless

package main

import (
	"context"
	"embed"
	"fmt"
	"log/slog"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"
	"github.com/wailsapp/wails/v2/pkg/options/windows"

	"github.com/xkstudios/atgs/keeper/internal/daemon"
	"github.com/xkstudios/atgs/keeper/internal/metrics"
	"github.com/xkstudios/atgs/keeper/internal/pause"
)

//go:embed all:frontend/dist
var guiAssets embed.FS

// runGUI starts the daemon in a goroutine and opens the Wails window.
//
// Tray support (deferred to Phase 6.1):
//
// Wails v2 does not ship a built-in system tray. Integrating a third-party
// tray library (getlantern/systray) requires coordinating main-thread
// ownership with Wails itself, which is fragile especially on Linux.
// Phase 6 ships with window-only; the window has a clear pause control
// which covers the critical "give me my machine back" use case. Tray
// lands in Phase 6.1 once we decide between migrating to Wails v3 (tray
// built-in, alpha) or shipping our own carefully-gated goroutine.
func runGUI(ctx context.Context, rt *daemon.Runtime) error {
	// Kick the daemon's background goroutines. When ctx is cancelled
	// (window closed via tray "Quit", or SIGINT), Run returns.
	daemonErrCh := make(chan error, 1)
	go func() {
		daemonErrCh <- rt.Run(ctx)
	}()

	api := &GUIAPI{rt: rt, log: rt.Log}

	err := wails.Run(&options.App{
		Title:             "ATGS Keeper",
		Width:             720,
		Height:            620,
		MinWidth:          520,
		MinHeight:         480,
		BackgroundColour:  &options.RGBA{R: 8, G: 9, B: 11, A: 1},
		AssetServer:       &assetserver.Options{Assets: guiAssets},
		OnStartup:         api.startup,
		Bind:              []any{api},

		// Windows-specific polish: hide to system tray on close, not quit
		Windows: &windows.Options{
			WebviewIsTransparent:              false,
			WindowIsTranslucent:               false,
			DisableWindowIcon:                 false,
			DisableFramelessWindowDecorations: false,
		},
		Linux: &linux.Options{
			ProgramName: "atgs-keeper",
		},
	})

	// When Wails returns, cancel the daemon context to shut it down cleanly.
	if err != nil {
		return fmt.Errorf("wails: %w", err)
	}
	// Drain daemon goroutine
	select {
	case err := <-daemonErrCh:
		return err
	default:
		return nil
	}
}

// GUIAPI is what the frontend calls. Every exported method is bound
// to window.go.main.GUIAPI by Wails codegen.
type GUIAPI struct {
	ctx context.Context
	rt  *daemon.Runtime
	log *slog.Logger
}

func (a *GUIAPI) startup(ctx context.Context) {
	a.ctx = ctx
}

// ---- Status ----

type StatusResponse struct {
	KeeperID    string      `json:"keeper_id"`
	Version     string      `json:"version"`
	CentralURL  string      `json:"central_url"`
	DataRoot    string      `json:"data_root"`
	Paused      bool        `json:"paused"`
	PauseReason string      `json:"pause_reason,omitempty"`
	PausedAt    string      `json:"paused_at,omitempty"`
	Host        *metrics.HostStats `json:"host,omitempty"`
}

func (a *GUIAPI) Status() (*StatusResponse, error) {
	st := a.rt.Pause.State()
	host, _ := metrics.Host(a.rt.DataRoot)
	resp := &StatusResponse{
		KeeperID:   a.rt.Identity.KeeperID,
		Version:    version,
		CentralURL: a.rt.Config.CentralEnrollURL,
		DataRoot:   a.rt.DataRoot,
		Paused:     st.Paused,
		Host:       host,
	}
	if st.Paused {
		resp.PauseReason = st.Reason
		resp.PausedAt = st.PausedAt.Format("2006-01-02 15:04:05")
	}
	return resp, nil
}

// ---- Instances ----

type InstanceRow struct {
	InstanceID  string `json:"instance_id"`
	EggID       string `json:"egg_id"`
	DisplayName string `json:"display_name"`
	State       string `json:"state"`
	ContainerID string `json:"container_id,omitempty"`
}

func (a *GUIAPI) Instances() ([]InstanceRow, error) {
	insts, err := a.rt.Store.ListInstances(a.ctx)
	if err != nil {
		return nil, err
	}
	out := make([]InstanceRow, 0, len(insts))
	for _, i := range insts {
		out = append(out, InstanceRow{
			InstanceID:  i.InstanceID,
			EggID:       i.EggID,
			DisplayName: i.DisplayName,
			State:       i.State,
			ContainerID: i.ContainerID,
		})
	}
	return out, nil
}

// ---- Pause controls ----

func (a *GUIAPI) Pause(reason string) error {
	if reason == "" {
		reason = "manual pause from tray"
	}
	return a.rt.Pause.Pause(a.ctx, reason)
}

func (a *GUIAPI) Unpause() error {
	return a.rt.Pause.Unpause(a.ctx)
}

// ---- Pause info for tray title ----

// pauseOrNot is a helper to render a Ø or ▶ style icon in the tray
// label. Kept simple; the frontend handles more nuanced rendering.
func pauseOrNot(c *pause.Controller) string {
	if c.IsPaused() {
		return "paused"
	}
	return "running"
}
