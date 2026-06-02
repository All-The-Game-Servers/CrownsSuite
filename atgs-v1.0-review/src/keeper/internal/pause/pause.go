// Package pause manages the keeper's "paused" state.
//
// When paused:
//   - New instance.start and instance.create tasks are deferred (the handler
//     returns a transient error and Central re-queues).
//   - All currently-running containers are SIGSTOPped via `docker pause`.
//     They stay in RAM and resume instantly on unpause.
//
// The controller is the source of truth for pause state. Both the tray
// menu and the main window call Pause()/Unpause() on it; both call
// IsPaused() to render the current state.
//
// Design choice: pause is purely local. Central is informed via the next
// periodic agent status update (so Progenitor shows paused keepers
// distinctly), but Central does not gate anything on it. This means a
// keeper can self-pause without round-tripping anywhere.
package pause

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/xkstudios/atgs/keeper/internal/localstore"
	"github.com/xkstudios/atgs/keeper/internal/runtimeiface"
)

// Controller is the pause state machine. Zero value is unpaused.
type Controller struct {
	mu       sync.RWMutex
	paused   bool
	pausedAt time.Time
	reason   string

	runtime runtimeiface.Runtime
	store   *localstore.Store
	log     *slog.Logger

	// listeners fires whenever pause state changes. The tray + main window
	// both subscribe to redraw themselves.
	lmu       sync.Mutex
	listeners []chan State
}

type State struct {
	Paused   bool      `json:"paused"`
	PausedAt time.Time `json:"paused_at,omitempty"`
	Reason   string    `json:"reason,omitempty"`
}

func New(rt runtimeiface.Runtime, store *localstore.Store, log *slog.Logger) *Controller {
	return &Controller{runtime: rt, store: store, log: log}
}

// IsPaused reports whether the keeper is currently paused. Safe to call
// from any goroutine.
func (c *Controller) IsPaused() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.paused
}

// State returns a full snapshot.
func (c *Controller) State() State {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return State{Paused: c.paused, PausedAt: c.pausedAt, Reason: c.reason}
}

// Subscribe returns a channel that receives a State on every change. Caller
// must drain promptly; the notifier uses non-blocking sends so slow readers
// miss updates rather than stalling other listeners. Call the returned
// cancel func to unsubscribe.
func (c *Controller) Subscribe() (<-chan State, func()) {
	ch := make(chan State, 4)
	c.lmu.Lock()
	c.listeners = append(c.listeners, ch)
	c.lmu.Unlock()
	cancel := func() {
		c.lmu.Lock()
		defer c.lmu.Unlock()
		for i, l := range c.listeners {
			if l == ch {
				c.listeners = append(c.listeners[:i], c.listeners[i+1:]...)
				close(l)
				return
			}
		}
	}
	return ch, cancel
}

func (c *Controller) notify(s State) {
	c.lmu.Lock()
	defer c.lmu.Unlock()
	for _, l := range c.listeners {
		select {
		case l <- s:
		default: // drop if listener is slow
		}
	}
}

// Pause transitions into paused state and SIGSTOPs all running containers.
// Idempotent: calling Pause when already paused is a no-op.
func (c *Controller) Pause(ctx context.Context, reason string) error {
	c.mu.Lock()
	if c.paused {
		c.mu.Unlock()
		return nil
	}
	c.paused = true
	c.pausedAt = time.Now()
	c.reason = reason
	state := State{Paused: true, PausedAt: c.pausedAt, Reason: reason}
	c.mu.Unlock()

	c.log.Info("keeper paused", "reason", reason)
	c.notify(state)

	// Enumerate running instances and pause each. Failures are logged but
	// not returned — pausing the keeper should not be blocked by one
	// misbehaving container.
	insts, err := c.store.ListInstances(ctx)
	if err != nil {
		c.log.Warn("pause: list instances", "err", err)
		return nil
	}
	for _, inst := range insts {
		if inst.ContainerID == "" || inst.State != "running" {
			continue
		}
		if err := c.runtime.PauseInstance(ctx, inst.ContainerID); err != nil {
			c.log.Warn("pause: container", "instance_id", inst.InstanceID, "err", err)
			continue
		}
		c.log.Info("paused container", "instance_id", inst.InstanceID)
	}
	return nil
}

// Unpause transitions out of paused state and SIGCONTs all running
// containers. Idempotent.
func (c *Controller) Unpause(ctx context.Context) error {
	c.mu.Lock()
	if !c.paused {
		c.mu.Unlock()
		return nil
	}
	c.paused = false
	c.pausedAt = time.Time{}
	c.reason = ""
	c.mu.Unlock()

	c.log.Info("keeper unpaused")
	c.notify(State{Paused: false})

	insts, err := c.store.ListInstances(ctx)
	if err != nil {
		c.log.Warn("unpause: list instances", "err", err)
		return nil
	}
	for _, inst := range insts {
		if inst.ContainerID == "" {
			continue
		}
		if err := c.runtime.UnpauseInstance(ctx, inst.ContainerID); err != nil {
			// Log but don't fail — container may not actually be paused
			// (e.g. crashed while we were paused). Next start will fix it.
			c.log.Debug("unpause: container", "instance_id", inst.InstanceID, "err", err)
			continue
		}
		c.log.Info("unpaused container", "instance_id", inst.InstanceID)
	}
	return nil
}

// ErrKeeperPaused is what the task handler returns when a start/create
// task arrives during pause. Central's dispatcher recognises this and
// re-queues rather than failing the task permanently.
var ErrKeeperPaused = errors.New("keeper is paused; task deferred")
