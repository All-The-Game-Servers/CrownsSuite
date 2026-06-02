// Package runtimeiface is the common interface that both the real Docker
// runtime and the fake in-memory runtime satisfy. The tasks handler depends
// on this interface, not on the concrete Docker implementation, so we can
// swap in a fake for end-to-end testing in environments without Docker.
package runtimeiface

import (
	"context"
	"time"
)

// CreateParams are the arguments to Create.
type CreateParams struct {
	InstanceID  string
	EggID       string
	DisplayName string
	Env         map[string]string
	MemoryBytes int64
	CPUShares   int64
}

// CreateResult is what Create returns.
type CreateResult struct {
	ContainerID string
	HostPort    int
}

// InspectResult is what Inspect returns.
type InspectResult struct {
	State     string // running, exited, created, ...
	StartedAt time.Time
	ExitCode  *int
}

// Runtime is the contract. Every implementation must be safe for concurrent
// use by the task handler goroutines.
type Runtime interface {
	Ping(ctx context.Context) error
	CreateInstance(ctx context.Context, p CreateParams) (*CreateResult, error)
	StartInstance(ctx context.Context, containerID string) error
	// DetectHostPort returns the TCP host port Docker bound for the first
	// exposed port in the egg manifest, or 0 if none could be determined.
	// Should be called AFTER StartInstance; against real Docker, ports are
	// not populated in ContainerInspect until the container actually starts.
	// The manifest is passed so the implementation knows which egg port to
	// look up without another round trip through the keeper's state.
	DetectHostPort(ctx context.Context, containerID string, eggID string) (int, error)
	StopInstance(ctx context.Context, containerID string, timeout time.Duration) error
	// PauseInstance sends SIGSTOP to the container's processes (docker pause).
	// Memory stays resident; CPU goes to zero. Unpause resumes instantly.
	PauseInstance(ctx context.Context, containerID string) error
	// UnpauseInstance resumes a paused container. If the container was never
	// paused, the implementation should return a nil error (idempotent).
	UnpauseInstance(ctx context.Context, containerID string) error
	DeleteInstance(ctx context.Context, instanceID, containerID string) error
	InspectInstance(ctx context.Context, containerID string) (*InspectResult, error)
	TailLogs(ctx context.Context, containerID string, lines int) ([]string, bool, error)
	WriteConsole(ctx context.Context, containerID string, input string) error
	Close() error
}
