// Relay is the ATGS player-traffic data plane.
//
// One relay process holds:
//   - An ingress TCP listener for incoming player connections (port 25565).
//   - A data-channel mTLS WebSocket server for Keeper connections (port 7443).
//   - A peer mTLS WebSocket server for inter-relay coordination (port 7444).
//   - A routing-table sync client to Central.
//   - A peer mesh client connecting outbound to every configured peer.
//
// Subcommands:
//
//	relay serve     run all listeners
//	relay version   print binary version
//
// Configuration is via ATGS_RELAY_* environment variables. See
// internal/config/config.go for the full list.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"os/signal"
	"sync"
	"syscall"

	"github.com/xkstudios/atgs/relay/internal/config"
	"github.com/xkstudios/atgs/relay/internal/datachannel"
	"github.com/xkstudios/atgs/relay/internal/identity"
	"github.com/xkstudios/atgs/relay/internal/ingress"
	"github.com/xkstudios/atgs/relay/internal/peering"
	"github.com/xkstudios/atgs/relay/internal/registry"
	"github.com/xkstudios/atgs/relay/internal/routing"
	"github.com/xkstudios/atgs/relay/internal/syncclient"
	"github.com/xkstudios/atgs/relay/internal/tlsutil"
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

	switch cmd {
	case "serve":
		if err := runServe(log); err != nil {
			log.Error("serve", "err", err)
			os.Exit(1)
		}
	case "version":
		fmt.Println("atgs-relay 0.3.0-phase3")
	case "-h", "--help", "help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", cmd)
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `atgs-relay <command>

Commands:
  serve     run the relay (ingress + data channel + peering listeners)
  version   print binary version

Configuration is read from environment variables (ATGS_RELAY_*).
See docs/relay-protocol.md for architecture.`)
}

func runServe(log *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}

	if !cfg.HasIdentity() {
		return errors.New("no relay identity on disk. Provision with " +
			"`central mint-relay-cert` and place the bundle in " + cfg.StateDir)
	}

	id, err := identity.Load(cfg.StateDir)
	if err != nil {
		return fmt.Errorf("load identity: %w", err)
	}
	log.Info("relay identity loaded",
		"relay_id", id.RelayID,
		"cert_not_after", id.CertNotAfter)

	serverTLS, err := tlsutil.ServerConfig(id)
	if err != nil {
		return fmt.Errorf("server tls config: %w", err)
	}

	// Routing cache (SQLite-backed).
	cache, err := routing.Open(cfg.StateDir)
	if err != nil {
		return fmt.Errorf("open routing cache: %w", err)
	}
	defer cache.Close()
	log.Info("routing cache opened",
		"known_version", cache.KnownVersion(),
		"size", cache.Size())

	// Client TLS for dialing Central's sync endpoint. ServerName comes from
	// the configured URL host.
	syncURL, err := url.Parse(cfg.CentralSyncURL)
	if err != nil {
		return fmt.Errorf("parse central_sync_url: %w", err)
	}
	clientTLS, err := tlsutil.ClientConfig(id, syncURL.Hostname())
	if err != nil {
		return fmt.Errorf("client tls config: %w", err)
	}
	// Dev mode: accept the self-signed cert Central issues itself.
	if cfg.DevMode {
		clientTLS.InsecureSkipVerify = true
	}
	state := registry.NewState(id.RelayID, log)

	syncCli := syncclient.New(syncclient.Config{
		CentralSyncURL: cfg.CentralSyncURL,
		TLSConfig:      clientTLS,
		RelayID:        id.RelayID,
		RelayVersion:   cfg.Version,
		Cache:          cache,
		Log:            log,
	})

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	ingressLn := ingress.New(cfg.IngressAddr, cache, state, log)
	bedrockMgr := ingress.NewBedrock(cfg.BedrockBindHost, cfg.BedrockIdleTimeout, cache, state, log)
	dataSrv := datachannel.New(cfg.DataChannelAddr, serverTLS, state, log)
	peerSrv := peering.NewServer(cfg.PeerAddr, serverTLS, state, cache, cfg.PeerEndpoints, log)

	var wg sync.WaitGroup
	errCh := make(chan error, 5)

	wg.Add(5)
	go func() {
		defer wg.Done()
		if err := syncCli.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
			errCh <- fmt.Errorf("sync: %w", err)
		}
	}()
	go func() {
		defer wg.Done()
		if err := ingressLn.Serve(ctx); err != nil {
			errCh <- fmt.Errorf("ingress: %w", err)
		}
	}()
	go func() {
		defer wg.Done()
		if err := bedrockMgr.Serve(ctx); err != nil && !errors.Is(err, context.Canceled) {
			errCh <- fmt.Errorf("bedrock ingress: %w", err)
		}
	}()
	go func() {
		defer wg.Done()
		if err := dataSrv.Serve(ctx); err != nil {
			errCh <- fmt.Errorf("data channel: %w", err)
		}
	}()
	go func() {
		defer wg.Done()
		if err := peerSrv.Serve(ctx); err != nil {
			errCh <- fmt.Errorf("peering: %w", err)
		}
	}()

	log.Info("relay serving",
		"relay_id", id.RelayID,
		"ingress", cfg.IngressAddr,
		"bedrock_bind_host", cfg.BedrockBindHost,
		"data_channel", cfg.DataChannelAddr,
		"peer", cfg.PeerAddr,
		"peers", cfg.PeerEndpoints,
		"version", cfg.Version)

	// Block until either context cancellation (SIGINT/SIGTERM) or a listener
	// returns an error.
	select {
	case <-ctx.Done():
		log.Info("shutdown requested")
	case err := <-errCh:
		log.Error("listener failed", "err", err)
		stop()
	}

	wg.Wait()
	log.Info("relay shut down cleanly")
	return nil
}
