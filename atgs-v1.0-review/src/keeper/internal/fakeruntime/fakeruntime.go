// Package fakeruntime is an in-memory Runtime implementation for e2e tests
// in environments without Docker. It keeps a map of "containers" with fake
// IDs and state, and produces canned log output so the logs.tail task
// returns something verifiable.
//
// NEVER enable this in production. It is gated by ATGS_KEEPER_FAKE_DOCKER=true
// in the Keeper config.
package fakeruntime

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"github.com/xkstudios/atgs/keeper/internal/runtimeiface"
)

type fakeContainer struct {
	id          string
	instanceID  string
	eggID       string
	displayName string
	hostPort    int
	state       string // created, running, stopped
	startedAt   time.Time
	logs        []string
}

// Runtime is the fake implementation.
type Runtime struct {
	mu         sync.Mutex
	containers map[string]*fakeContainer // keyed by container id
}

// New returns a Runtime satisfying runtimeiface.Runtime.
func New() *Runtime {
	return &Runtime{containers: make(map[string]*fakeContainer)}
}

func (r *Runtime) Ping(ctx context.Context) error { return nil }

func (r *Runtime) Close() error { return nil }

func (r *Runtime) CreateInstance(ctx context.Context, p runtimeiface.CreateParams) (*runtimeiface.CreateResult, error) {
	id := randID()
	r.mu.Lock()
	defer r.mu.Unlock()
	r.containers[id] = &fakeContainer{
		id:          id,
		instanceID:  p.InstanceID,
		eggID:       p.EggID,
		displayName: p.DisplayName,
		hostPort:    45000 + len(r.containers) + 1,
		state:       "created",
		logs: []string{
			fmt.Sprintf("[fake] container created for instance %s", p.InstanceID),
			fmt.Sprintf("[fake] egg=%s memory=%d cpu_shares=%d", p.EggID, p.MemoryBytes, p.CPUShares),
		},
	}
	return &runtimeiface.CreateResult{
		ContainerID: id,
		HostPort:    r.containers[id].hostPort,
	}, nil
}

func (r *Runtime) StartInstance(ctx context.Context, containerID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return fmt.Errorf("no such container")
	}
	c.state = "running"
	c.startedAt = time.Now()
	c.logs = append(c.logs, "[fake] container started")
	return nil
}

// DetectHostPort satisfies runtimeiface.Runtime. The fake runtime assigned a
// port at CreateInstance time; we just return it.
func (r *Runtime) DetectHostPort(ctx context.Context, containerID string, eggID string) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return 0, fmt.Errorf("no such container")
	}
	return c.hostPort, nil
}

func (r *Runtime) StopInstance(ctx context.Context, containerID string, timeout time.Duration) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return fmt.Errorf("no such container")
	}
	c.state = "stopped"
	c.logs = append(c.logs, "[fake] container stopped")
	return nil
}

func (r *Runtime) PauseInstance(ctx context.Context, containerID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return fmt.Errorf("no such container")
	}
	c.state = "paused"
	c.logs = append(c.logs, "[fake] container paused")
	return nil
}

func (r *Runtime) UnpauseInstance(ctx context.Context, containerID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return nil // idempotent
	}
	if c.state == "paused" {
		c.state = "running"
		c.logs = append(c.logs, "[fake] container unpaused")
	}
	return nil
}

func (r *Runtime) DeleteInstance(ctx context.Context, instanceID, containerID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.containers, containerID)
	return nil
}

func (r *Runtime) InspectInstance(ctx context.Context, containerID string) (*runtimeiface.InspectResult, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return nil, fmt.Errorf("no such container")
	}
	return &runtimeiface.InspectResult{
		State:     c.state,
		StartedAt: c.startedAt,
	}, nil
}

func (r *Runtime) TailLogs(ctx context.Context, containerID string, lines int) ([]string, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return nil, false, fmt.Errorf("no such container")
	}
	if lines <= 0 {
		lines = 100
	}
	out := append([]string(nil), c.logs...)
	truncated := false
	if len(out) > lines {
		out = out[len(out)-lines:]
		truncated = true
	}
	return out, truncated, nil
}

func (r *Runtime) WriteConsole(ctx context.Context, containerID string, input string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	c, ok := r.containers[containerID]
	if !ok {
		return fmt.Errorf("no such container")
	}
	c.logs = append(c.logs, fmt.Sprintf("[fake-console] %s", input))
	return nil
}

func randID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "fake-" + hex.EncodeToString(b)
}
