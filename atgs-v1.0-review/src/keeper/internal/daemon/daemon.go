// Package daemon is the keeper's core runtime: identity, store, Docker
// runtime, task handler, control + data channels. It exposes Boot() which
// brings everything up and returns a Runtime object that can be queried
// by the UI (or just ignored in headless mode).
//
// Splitting this out of cmd/keeper/main.go lets both the headless entry
// point and the Wails embedded entry point share a single boot sequence.
package daemon

import (
	"context"
	"fmt"
	"log/slog"
	"path/filepath"

	"github.com/xkstudios/atgs/keeper/internal/client"
	"github.com/xkstudios/atgs/keeper/internal/config"
	"github.com/xkstudios/atgs/keeper/internal/datachannel"
	"github.com/xkstudios/atgs/keeper/internal/enroll"
	"github.com/xkstudios/atgs/keeper/internal/fakeruntime"
	"github.com/xkstudios/atgs/keeper/internal/localstore"
	"github.com/xkstudios/atgs/keeper/internal/pause"
	"github.com/xkstudios/atgs/keeper/internal/runtime"
	"github.com/xkstudios/atgs/keeper/internal/runtimeiface"
	"github.com/xkstudios/atgs/keeper/internal/tasks"
	"github.com/xkstudios/atgs/shared/egg"
)

// Runtime holds references to everything the UI layer might want to poke at.
// Headless mode constructs it and then just Runs(); UI mode hands it to
// the Wails App to bind against.
type Runtime struct {
	Config     *config.Config
	Log        *slog.Logger
	Identity   *enroll.Identity
	Store      *localstore.Store
	Runtime    runtimeiface.Runtime
	Registry   *egg.Registry
	Handler    *tasks.Handler
	Client     *client.Client
	DataClient *datachannel.Client
	DataRoot   string
	Pause      *pause.Controller
}

// Boot performs the full keeper startup sequence: load config, identity
// (or enroll), open store, load eggs, init runtime, build handler and
// clients. Returns a Runtime ready for Run() to be called, or an error
// if any step fails irrecoverably.
//
// Boot does NOT start background goroutines. Call Run() on the returned
// Runtime to start the control channel and data channel loops.
func Boot(ctx context.Context, cfg *config.Config, log *slog.Logger) (*Runtime, error) {
	// --- Identity: load or enroll ---
	identity, err := enroll.LoadFromDisk(cfg.StateDir)
	if err != nil {
		return nil, fmt.Errorf("load identity: %w", err)
	}
	if identity == nil {
		if cfg.EnrollToken == "" {
			return nil, fmt.Errorf("no identity on disk and ATGS_ENROLL_TOKEN not set (state_dir=%s)", cfg.StateDir)
		}
		log.Info("enrolling with central", "central_url", cfg.CentralEnrollURL)
		identity, err = enroll.Enroll(ctx, cfg.CentralEnrollURL, cfg.EnrollToken, cfg.StateDir, cfg.AgentVersion, cfg.InsecureSkipVerify)
		if err != nil {
			return nil, fmt.Errorf("enrollment: %w", err)
		}
		log.Info("enrolled", "keeper_id", identity.KeeperID, "cert_not_after", identity.CertNotAfter)
	} else {
		log.Info("identity loaded", "keeper_id", identity.KeeperID, "cert_not_after", identity.CertNotAfter)
	}

	// --- Local store ---
	local, err := localstore.Open(cfg.StateDir)
	if err != nil {
		return nil, fmt.Errorf("open local store: %w", err)
	}

	// --- Eggs ---
	registry, err := egg.LoadRegistry(cfg.EggsDir, func(dir string, err error) {
		log.Warn("skip egg", "dir", dir, "err", err)
	})
	if err != nil {
		return nil, fmt.Errorf("load egg registry: %w", err)
	}
	log.Info("eggs loaded", "dir", cfg.EggsDir, "count", registry.Count())

	// --- Runtime (Docker or fake) ---
	dataRoot := cfg.DataRoot
	if dataRoot == "" {
		dataRoot = filepath.Join(cfg.StateDir, "instances")
	}
	var rt runtimeiface.Runtime
	if cfg.FakeDocker {
		rt = fakeruntime.New()
		log.Warn("USING FAKE DOCKER RUNTIME (test mode only, never in production)")
	} else {
		real, err := runtime.New(runtime.Config{DataRoot: dataRoot, Registry: registry})
		if err != nil {
			return nil, fmt.Errorf("init runtime: %w", err)
		}
		rt = real
	}
	if err := rt.Ping(ctx); err != nil {
		_ = rt.Close()
		return nil, fmt.Errorf("runtime ping (is docker running?): %w", err)
	}
	log.Info("runtime ready", "data_root", dataRoot, "fake", cfg.FakeDocker)

	// --- Pause controller (Phase 6) ---
	pauseCtrl := pause.New(rt, local, log)

	// --- Task handler ---
	handler := &tasks.Handler{
		Runtime:            rt,
		Store:              local,
		Log:                log,
		Identity:           identity,
		InsecureSkipVerify: cfg.InsecureSkipVerify,
		DataRoot:           dataRoot,
		Pause:              pauseCtrl,
	}

	// --- Control channel ---
	ctrl := client.New(client.Config{
		Identity:     identity,
		AgentVersion: cfg.AgentVersion,
		Log:          log,
		Handler:      handler,
	})

	// --- Data channel ---
	dataClient := datachannel.New(datachannel.Config{
		Identity:           identity,
		RelayDataURLs:      cfg.RelayDataURLs,
		InsecureSkipVerify: cfg.InsecureSkipVerify,
		Store:              local,
		Log:                log,
	})

	return &Runtime{
		Config:     cfg,
		Log:        log,
		Identity:   identity,
		Store:      local,
		Runtime:    rt,
		Registry:   registry,
		Handler:    handler,
		Client:     ctrl,
		DataClient: dataClient,
		DataRoot:   dataRoot,
		Pause:      pauseCtrl,
	}, nil
}

// Run starts the control and data channel goroutines and blocks until
// one of them errors or ctx is cancelled. The caller is responsible for
// calling Close() on the returned Runtime afterwards.
func (r *Runtime) Run(ctx context.Context) error {
	errCh := make(chan error, 2)
	go func() { errCh <- r.Client.Run(ctx) }()
	go func() { errCh <- r.DataClient.Run(ctx) }()
	err := <-errCh
	if err == context.Canceled {
		return nil
	}
	return err
}

// Close releases the runtime's Docker and store resources. Safe to call
// multiple times.
func (r *Runtime) Close() {
	if r.Runtime != nil {
		_ = r.Runtime.Close()
	}
	if r.Store != nil {
		_ = r.Store.Close()
	}
}
