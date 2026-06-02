//go:build keeper_headless

package main

import (
	"context"
	"github.com/xkstudios/atgs/keeper/internal/daemon"
)

// In headless builds (built with -tags keeper_headless, which we'll use
// for Docker images), the GUI code is excluded and runGUI falls back to
// plain daemon mode. This lets us ship a truly GTK-free Linux server
// binary when needed.
func runGUI(ctx context.Context, rt *daemon.Runtime) error {
	rt.Log.Info("keeper_headless build tag: GUI disabled, running as daemon")
	return rt.Run(ctx)
}
