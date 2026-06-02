// Package runtime is the Keeper's Docker runtime.
//
// Responsibilities:
//   - Create containers from egg manifests (pull image, wire volumes, apply
//     env, set resource limits).
//   - Start, stop, delete, and inspect containers.
//   - Tail logs.
//
// Naming convention: containers are named atgs-<short-instance-id> so a
// human looking at `docker ps` can tell at a glance which container belongs
// to which Central instance.
//
// Volumes: each instance gets a host directory at <data_root>/<instance_id>
// mounted into the container's first declared data volume. In Phase 2 we
// only support one data volume per egg; multi-volume eggs will need a
// manifest extension.
package runtime

import (
	"bufio"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/image"
	"github.com/docker/docker/api/types/mount"
	"github.com/docker/docker/client"
	"github.com/docker/go-connections/nat"

	"github.com/xkstudios/atgs/keeper/internal/runtimeiface"
	"github.com/xkstudios/atgs/shared/egg"
)

// Runtime wraps a Docker client with opinionated helpers for ATGS instance
// lifecycle.
type Runtime struct {
	docker   *client.Client
	dataRoot string        // host path root for per-instance volumes
	registry *egg.Registry // loaded eggs, for image lookup
}

type Config struct {
	DataRoot string        // e.g. /var/lib/atgs/instances
	Registry *egg.Registry // already loaded
}

func New(cfg Config) (*Runtime, error) {
	if cfg.DataRoot == "" {
		return nil, errors.New("data_root is required")
	}
	if cfg.Registry == nil {
		return nil, errors.New("egg registry is required")
	}
	if err := os.MkdirAll(cfg.DataRoot, 0o750); err != nil {
		return nil, fmt.Errorf("mkdir data root: %w", err)
	}
	d, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return nil, fmt.Errorf("docker client: %w", err)
	}
	return &Runtime{docker: d, dataRoot: cfg.DataRoot, registry: cfg.Registry}, nil
}

func (r *Runtime) Close() error {
	if r.docker != nil {
		return r.docker.Close()
	}
	return nil
}

// Ping checks that the Docker daemon is reachable. Called at startup so
// the Keeper fails early with a clear message if Docker isn't running.
func (r *Runtime) Ping(ctx context.Context) error {
	_, err := r.docker.Ping(ctx)
	return err
}

// --- Instance create ---

func (r *Runtime) CreateInstance(ctx context.Context, p runtimeiface.CreateParams) (*runtimeiface.CreateResult, error) {
	manifest := r.registry.Get(p.EggID)
	if manifest == nil {
		return nil, fmt.Errorf("unknown egg: %s", p.EggID)
	}

	// Ensure the image is present. Pull if not.
	if err := r.ensureImage(ctx, manifest.DockerImage); err != nil {
		return nil, fmt.Errorf("ensure image %s: %w", manifest.DockerImage, err)
	}

	// Prepare per-instance host volume directory.
	instanceDir := filepath.Join(r.dataRoot, p.InstanceID)
	if err := os.MkdirAll(instanceDir, 0o750); err != nil {
		return nil, fmt.Errorf("mkdir instance dir: %w", err)
	}

	// Build env: start with egg defaults, then overlay caller-supplied env.
	envList := mergeEnv(manifest.Env, p.Env)

	// Wire port mappings. In Phase 2 we don't expose ports to the public
	// internet; the Keeper binds to 127.0.0.1 so only the local relay (or
	// local testing) can reach them. Phase 3 will change how this works.
	exposed := nat.PortSet{}
	portBindings := nat.PortMap{}
	for _, ep := range manifest.Ports {
		natPort, err := nat.NewPort(ep.Protocol, fmt.Sprintf("%d", ep.ContainerPort))
		if err != nil {
			return nil, fmt.Errorf("port %d/%s: %w", ep.ContainerPort, ep.Protocol, err)
		}
		exposed[natPort] = struct{}{}
		// Phase 8: public-facing ports bind to 0.0.0.0 so external clients
		// can connect directly. Bedrock needs this because there's no UDP
		// relay yet. Non-public ports stay on loopback for relay-only access.
		hostIP := "127.0.0.1"
		if ep.Public {
			hostIP = "0.0.0.0"
		}
		portBindings[natPort] = []nat.PortBinding{
			{HostIP: hostIP, HostPort: "0"}, // 0 = Docker picks free port
		}
	}

	// Mount the first declared data volume. Multi-volume support is future.
	var mounts []mount.Mount
	if len(manifest.DataVolumes) > 0 {
		mounts = append(mounts, mount.Mount{
			Type:   mount.TypeBind,
			Source: instanceDir,
			Target: manifest.DataVolumes[0],
		})
	}

	containerName := "atgs-" + shortID(p.InstanceID)

	resp, err := r.docker.ContainerCreate(
		ctx,
		&container.Config{
			Image:        manifest.DockerImage,
			Env:          envList,
			ExposedPorts: exposed,
			Labels: map[string]string{
				"atgs.instance_id":  p.InstanceID,
				"atgs.egg_id":       p.EggID,
				"atgs.display_name": p.DisplayName,
			},
			AttachStdout: true,
			AttachStderr: true,
			OpenStdin:    true, // keeps a stdin handle available for later console work
			Tty:          false,
		},
		&container.HostConfig{
			PortBindings: portBindings,
			Mounts:       mounts,
			RestartPolicy: container.RestartPolicy{
				Name: "unless-stopped",
			},
			Resources: container.Resources{
				Memory:    p.MemoryBytes,
				CPUShares: p.CPUShares,
			},
		},
		nil, // networking defaults
		nil, // platform defaults
		containerName,
	)
	if err != nil {
		return nil, fmt.Errorf("container create: %w", err)
	}
	// NOTE: host port is NOT detected here. Docker does not populate
	// NetworkSettings.Ports until ContainerStart runs. The keeper's task
	// handler calls DetectHostPort AFTER StartInstance and reports the
	// port to Central via the instance.start result. See docs/phase3-status.md.
	return &runtimeiface.CreateResult{ContainerID: resp.ID, HostPort: 0}, nil
}

// --- Lifecycle ---

func (r *Runtime) StartInstance(ctx context.Context, containerID string) error {
	if err := r.docker.ContainerStart(ctx, containerID, container.StartOptions{}); err != nil {
		return fmt.Errorf("container start: %w", err)
	}
	return nil
}

// StopInstance gracefully stops the container. timeout is how long we'll
// wait for a SIGTERM before SIGKILL.
func (r *Runtime) StopInstance(ctx context.Context, containerID string, timeout time.Duration) error {
	secs := int(timeout.Seconds())
	if secs < 1 {
		secs = 10
	}
	if err := r.docker.ContainerStop(ctx, containerID, container.StopOptions{Timeout: &secs}); err != nil {
		return fmt.Errorf("container stop: %w", err)
	}
	return nil
}

// PauseInstance SIGSTOPs the container via the Docker pause API. Memory
// stays resident, CPU drops to zero, network stays open (though TCP
// connections will time out player-side if pause lasts too long).
func (r *Runtime) PauseInstance(ctx context.Context, containerID string) error {
	if err := r.docker.ContainerPause(ctx, containerID); err != nil {
		return fmt.Errorf("container pause: %w", err)
	}
	return nil
}

// UnpauseInstance resumes a paused container. If the container is not
// paused (already running, or exited) Docker returns an error that we
// swallow — the end state (running or gone) is the same either way.
func (r *Runtime) UnpauseInstance(ctx context.Context, containerID string) error {
	if err := r.docker.ContainerUnpause(ctx, containerID); err != nil {
		// Not-paused and not-running errors are OK; everything else is a
		// real problem.
		if client.IsErrNotFound(err) {
			return nil
		}
		// Docker returns a 409 for "not paused"; the error string contains
		// "is not paused". We treat that as success.
		if strings.Contains(err.Error(), "is not paused") {
			return nil
		}
		return fmt.Errorf("container unpause: %w", err)
	}
	return nil
}

// DeleteInstance removes the container (force=true so a running container
// is killed first). Also wipes the instance's host data directory.
func (r *Runtime) DeleteInstance(ctx context.Context, instanceID, containerID string) error {
	if containerID != "" {
		err := r.docker.ContainerRemove(ctx, containerID, container.RemoveOptions{
			Force:         true,
			RemoveVolumes: false, // volumes are bind mounts to dataRoot
		})
		if err != nil && !client.IsErrNotFound(err) {
			return fmt.Errorf("container remove: %w", err)
		}
	}
	instanceDir := filepath.Join(r.dataRoot, instanceID)
	if err := os.RemoveAll(instanceDir); err != nil {
		return fmt.Errorf("rm data dir: %w", err)
	}
	return nil
}

// InspectInstance returns basic state info about a container.
func (r *Runtime) InspectInstance(ctx context.Context, containerID string) (*runtimeiface.InspectResult, error) {
	info, err := r.docker.ContainerInspect(ctx, containerID)
	if err != nil {
		return nil, err
	}
	out := &runtimeiface.InspectResult{State: info.State.Status}
	if info.State.StartedAt != "" {
		if t, err := time.Parse(time.RFC3339Nano, info.State.StartedAt); err == nil {
			out.StartedAt = t
		}
	}
	if info.State.Status == "exited" {
		code := info.State.ExitCode
		out.ExitCode = &code
	}
	return out, nil
}

// --- Logs ---

// TailLogs returns up to lines of the most recent container output,
// combining stdout and stderr.
func (r *Runtime) TailLogs(ctx context.Context, containerID string, lines int) ([]string, bool, error) {
	if lines <= 0 {
		lines = 100
	}
	opts := container.LogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Tail:       fmt.Sprintf("%d", lines),
		Timestamps: false,
	}
	rc, err := r.docker.ContainerLogs(ctx, containerID, opts)
	if err != nil {
		return nil, false, fmt.Errorf("container logs: %w", err)
	}
	defer rc.Close()

	// Docker multiplexes stdout/stderr when TTY=false using an 8-byte header
	// per frame: [stream, 0, 0, 0, sz, sz, sz, sz]. We demux by reading
	// frames and passing the payload through.
	var out []string
	truncated := false
	hdr := make([]byte, 8)
	sb := &strings.Builder{}
	for {
		_, err := io.ReadFull(rc, hdr)
		if err == io.EOF {
			break
		}
		if err != nil {
			return out, truncated, fmt.Errorf("read log header: %w", err)
		}
		size := binary.BigEndian.Uint32(hdr[4:8])
		frame := make([]byte, size)
		if _, err := io.ReadFull(rc, frame); err != nil {
			return out, truncated, fmt.Errorf("read log frame: %w", err)
		}
		sb.Write(frame)
	}
	scanner := bufio.NewScanner(strings.NewReader(sb.String()))
	// Large buffer in case a single log line is huge.
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		out = append(out, scanner.Text())
	}
	if len(out) > lines {
		truncated = true
		out = out[len(out)-lines:]
	}
	return out, truncated, nil
}

func (r *Runtime) WriteConsole(ctx context.Context, containerID string, input string) error {
	attach, err := r.docker.ContainerAttach(ctx, containerID, container.AttachOptions{
		Stream: true,
		Stdin:  true,
	})
	if err != nil {
		return fmt.Errorf("container attach: %w", err)
	}
	defer attach.Close()

	if _, err := io.WriteString(attach.Conn, strings.TrimRight(input, "\r\n")+"\n"); err != nil {
		return fmt.Errorf("console write: %w", err)
	}
	_ = attach.CloseWrite()
	return nil
}

// --- helpers ---

func (r *Runtime) ensureImage(ctx context.Context, imageRef string) error {
	// Try inspect first. If the image exists locally we're done.
	_, _, err := r.docker.ImageInspectWithRaw(ctx, imageRef)
	if err == nil {
		return nil
	}
	if !client.IsErrNotFound(err) {
		return err
	}
	// Pull.
	rc, err := r.docker.ImagePull(ctx, imageRef, image.PullOptions{})
	if err != nil {
		return fmt.Errorf("image pull: %w", err)
	}
	defer rc.Close()
	// Drain the pull progress stream. If we don't consume it, the pull
	// effectively stalls.
	dec := json.NewDecoder(rc)
	for {
		var m map[string]any
		if err := dec.Decode(&m); err != nil {
			if err == io.EOF {
				break
			}
			return fmt.Errorf("read pull progress: %w", err)
		}
	}
	return nil
}

func mergeEnv(base, overlay map[string]string) []string {
	merged := make(map[string]string, len(base)+len(overlay))
	for k, v := range base {
		merged[k] = v
	}
	for k, v := range overlay {
		merged[k] = v
	}
	out := make([]string, 0, len(merged))
	for k, v := range merged {
		out = append(out, fmt.Sprintf("%s=%s", k, v))
	}
	return out
}

func shortID(uuidStr string) string {
	// First 8 hex chars. Enough for human skimming; full UUID is in labels.
	if len(uuidStr) > 8 {
		return uuidStr[:8]
	}
	return uuidStr
}

// DetectHostPort satisfies runtimeiface.Runtime. Loads the egg manifest,
// inspects the running container, and returns the TCP host port Docker
// bound for the first exposed port. Must be called AFTER StartInstance;
// returns 0 (not an error) if the port isn't yet populated, which is the
// expected state between ContainerCreate and ContainerStart.
func (r *Runtime) DetectHostPort(ctx context.Context, containerID string, eggID string) (int, error) {
	manifest := r.registry.Get(eggID)
	if manifest == nil {
		return 0, fmt.Errorf("egg %q not loaded", eggID)
	}
	info, err := r.docker.ContainerInspect(ctx, containerID)
	if err != nil {
		return 0, err
	}

	for _, ep := range manifest.Ports {
		natPort, err := nat.NewPort(ep.Protocol, fmt.Sprintf("%d", ep.ContainerPort))
		if err != nil {
			continue
		}
		if info.NetworkSettings != nil {
			if bindings, ok := info.NetworkSettings.Ports[natPort]; ok {
				for _, binding := range bindings {
					if binding.HostPort != "" {
						var port int
						if _, err := fmt.Sscanf(binding.HostPort, "%d", &port); err == nil && port > 0 {
							return port, nil
						}
					}
				}
			}
		}
	}
	return 0, nil
}
