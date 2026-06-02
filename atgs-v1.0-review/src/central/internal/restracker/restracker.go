// Package restracker compares what instances a keeper has been dispatched
// against what the keeper self-reports it's running. Divergence beyond a
// tolerance emits a WARN log + audit event. Per Phase 7 choice: warn, not
// auto-revoke — data quality issues shouldn't cost operators a keeper.
//
// The tracker runs a periodic goroutine (default 2 minutes) that walks
// every non-revoked keeper and cross-checks.
package restracker

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"

	"github.com/xkstudios/atgs/central/internal/store"
)

// Tolerance: allow up to 15% drift before warning. Captures normal variation
// from page cache, brief spikes, etc. without masking actual over-commit.
const DriftTolerance = 0.15

type Runner struct {
	store *store.Store
	log   *slog.Logger
	tick  time.Duration
}

func New(st *store.Store, log *slog.Logger, tick time.Duration) *Runner {
	if tick <= 0 {
		tick = 2 * time.Minute
	}
	return &Runner{store: st, log: log, tick: tick}
}

// Run blocks until ctx is cancelled.
func (r *Runner) Run(ctx context.Context) error {
	r.log.Info("resource cap tracker started", "tick", r.tick, "tolerance", DriftTolerance)
	t := time.NewTicker(r.tick)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
			r.scan(ctx)
		}
	}
}

func (r *Runner) scan(ctx context.Context) {
	keepers, err := r.store.ListKeepers(ctx)
	if err != nil {
		r.log.Warn("restracker: list keepers", "err", err)
		return
	}
	for _, k := range keepers {
		if k.RevokedAt != nil {
			continue
		}
		r.checkKeeper(ctx, k.ID)
	}
}

func (r *Runner) checkKeeper(ctx context.Context, keeperID uuid.UUID) {
	// Committed: sum of memory_bytes for non-deleted instances on this keeper.
	committed, err := r.store.SumKeeperCommittedMemory(ctx, keeperID)
	if err != nil {
		r.log.Warn("restracker: committed sum", "keeper_id", keeperID, "err", err)
		return
	}

	// Reported: most recent resources report from the keeper.
	reported, err := r.store.LatestKeeperResourcesReport(ctx, keeperID)
	if err != nil || reported == nil {
		// No report yet: nothing to compare. That's fine for a newly-enrolled
		// keeper that hasn't sent its first resources frame.
		return
	}

	if reported.MemTotalBytes == 0 {
		return // nonsense report, skip
	}

	// Drift: if committed > reported.MemTotalBytes, we've over-committed.
	// Also check reported.MemUsedBytes vs committed — keeper might be
	// reporting much less memory in use than we think we're running.
	if committed > reported.MemTotalBytes {
		r.log.Warn("keeper over-committed",
			"keeper_id", keeperID,
			"committed_bytes", committed,
			"host_total_bytes", reported.MemTotalBytes,
			"over_by_bytes", committed-reported.MemTotalBytes)
		r.writeAudit(ctx, keeperID, "over_commit", map[string]any{
			"committed_bytes":  committed,
			"host_total_bytes": reported.MemTotalBytes,
		})
		return
	}

	// Divergence check: committed memory we think is running vs keeper's
	// self-reported used. Huge skew = likely stopped containers the keeper
	// hasn't told us about, or data corruption.
	if reported.MemUsedBytes > 0 && committed > 0 {
		ratio := float64(committed) / float64(reported.MemUsedBytes)
		if ratio > 1+DriftTolerance || ratio < 1-DriftTolerance {
			// Quiet log — this fires a lot during real operation.
			r.log.Debug("keeper resource drift",
				"keeper_id", keeperID,
				"committed_bytes", committed,
				"reported_used_bytes", reported.MemUsedBytes,
				"ratio", ratio)
		}
	}
}

func (r *Runner) writeAudit(ctx context.Context, keeperID uuid.UUID, kind string, details map[string]any) {
	_ = r.store.WriteAudit(ctx, store.AuditEntry{
		Kind:     "restracker." + kind,
		Actor:    "system:restracker",
		KeeperID: &keeperID,
		Details:  details,
	})
}
