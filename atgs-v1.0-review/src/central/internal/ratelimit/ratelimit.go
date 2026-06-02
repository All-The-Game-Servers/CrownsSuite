// Package ratelimit provides a per-keeper token-bucket limiter for task
// dispatch. Phase 7 choice: 100/min burstable to 500.
//
// Implementation: x/time/rate.Limiter (golang.org/x/time/rate) per keeper,
// stored in a map guarded by a mutex. Limiters are created lazily on first
// use and retained for the process lifetime — ATGS has O(hundreds) of
// keepers at most, so memory cost is negligible.
//
// Allow() is non-blocking; callers that exceed the rate receive false and
// should fail or defer their task. This matches the dispatcher semantics
// where exceeding the limit is a caller problem, not a waiting problem.
package ratelimit

import (
	"sync"

	"github.com/google/uuid"
	"golang.org/x/time/rate"
)

// Limiter holds per-keeper token buckets.
type Limiter struct {
	mu       sync.Mutex
	buckets  map[uuid.UUID]*rate.Limiter
	perMin   int
	burst    int
}

// New builds a limiter with the given steady-state rate (per minute) and
// burst capacity. Values <= 0 fall back to 100/500 defaults.
func New(perMin, burst int) *Limiter {
	if perMin <= 0 {
		perMin = 100
	}
	if burst <= 0 {
		burst = 500
	}
	return &Limiter{
		buckets: make(map[uuid.UUID]*rate.Limiter),
		perMin:  perMin,
		burst:   burst,
	}
}

// Allow reports whether the given keeper may dispatch one more task right now.
// Returns true and consumes a token on success; returns false when the
// bucket is empty.
func (l *Limiter) Allow(keeperID uuid.UUID) bool {
	l.mu.Lock()
	b, ok := l.buckets[keeperID]
	if !ok {
		// Convert per-minute to per-second for rate.Limiter.
		b = rate.NewLimiter(rate.Limit(float64(l.perMin)/60.0), l.burst)
		l.buckets[keeperID] = b
	}
	l.mu.Unlock()
	return b.Allow()
}

// Reserve returns how long until one token would be available. Useful for
// reporting Retry-After to clients.
func (l *Limiter) Reserve(keeperID uuid.UUID) (ok bool, delaySeconds int) {
	l.mu.Lock()
	b, exists := l.buckets[keeperID]
	if !exists {
		b = rate.NewLimiter(rate.Limit(float64(l.perMin)/60.0), l.burst)
		l.buckets[keeperID] = b
	}
	l.mu.Unlock()
	r := b.Reserve()
	if !r.OK() {
		return false, 0
	}
	d := r.Delay()
	r.Cancel()
	if d == 0 {
		return true, 0
	}
	return false, int(d.Seconds()) + 1
}
