// Package scheduler runs scheduled backups.
//
// Every tick (default 30s) the scheduler asks the store for schedules whose
// next_run_at <= now, dispatches a backup task for each, records the run,
// and computes the next fire time from the cron expression. After a
// successful backup, it runs retention pruning per the schedule's retention
// value.
//
// The scheduler is intentionally simple: no distributed locking, no HA
// failover. Phase 4 runs one Central process per deployment; multi-Central
// setups are a Phase 8 concern (would need leader election).
package scheduler

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"

	"github.com/xkstudios/atgs/central/internal/cryptoutil"
	"github.com/xkstudios/atgs/central/internal/dispatcher"
	"github.com/xkstudios/atgs/central/internal/store"
	"github.com/xkstudios/atgs/shared/protocol"
)

// cronParser accepts the common 5-field format ("0 3 * * *"). Descriptor
// support (@daily, @hourly) is enabled for convenience.
var cronParser = cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow | cron.Descriptor)

type Runner struct {
	store        *store.Store
	dispatcher   *dispatcher.Dispatcher
	masterKey    *cryptoutil.MasterKey
	defaultMode  store.BackupStorageMode
	chunkSize    int
	chunkBaseURL string
	log          *slog.Logger
	tick         time.Duration
}

type Config struct {
	Store        *store.Store
	Dispatcher   *dispatcher.Dispatcher
	MasterKey    *cryptoutil.MasterKey
	DefaultMode  store.BackupStorageMode
	ChunkSize    int
	ChunkBaseURL string
	Log          *slog.Logger
	Tick         time.Duration
}

func New(cfg Config) *Runner {
	if cfg.Tick <= 0 {
		cfg.Tick = 30 * time.Second
	}
	if cfg.DefaultMode == "" {
		cfg.DefaultMode = store.BackupStorageCentralFS
	}
	return &Runner{
		store:        cfg.Store,
		dispatcher:   cfg.Dispatcher,
		masterKey:    cfg.MasterKey,
		defaultMode:  cfg.DefaultMode,
		chunkSize:    cfg.ChunkSize,
		chunkBaseURL: cfg.ChunkBaseURL,
		log:          cfg.Log,
		tick:         cfg.Tick,
	}
}

// Run blocks until ctx is cancelled. Scheduler always returns nil on clean
// shutdown; transient errors are logged and retried on the next tick.
func (r *Runner) Run(ctx context.Context) error {
	r.log.Info("backup scheduler started", "tick", r.tick)
	t := time.NewTicker(r.tick)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case now := <-t.C:
			r.runOnce(ctx, now)
		}
	}
}

func (r *Runner) runOnce(ctx context.Context, now time.Time) {
	due, err := r.store.DueSchedules(ctx, now)
	if err != nil {
		r.log.Warn("scheduler: fetch due schedules", "err", err)
		return
	}
	for _, sch := range due {
		r.fire(ctx, sch, now)
	}
}

func (r *Runner) fire(ctx context.Context, sch store.BackupSchedule, now time.Time) {
	log := r.log.With("schedule_id", sch.ScheduleID, "instance_id", sch.InstanceID)

	// Look up the instance so we know which keeper to dispatch to and what
	// egg/env to capture.
	inst, err := r.store.GetInstance(ctx, sch.InstanceID)
	if err != nil {
		log.Warn("scheduler: instance lookup", "err", err)
		r.advance(ctx, sch, now, uuid.Nil) // advance anyway so a deleted instance doesn't loop
		return
	}

	storageMode := r.defaultMode
	if sch.StorageMode != nil {
		storageMode = *sch.StorageMode
	}

	var wrapped, unwrapped []byte
	if sch.Encrypt {
		if r.masterKey == nil {
			log.Warn("scheduler: encrypted schedule but no master key configured; skipping")
			r.advance(ctx, sch, now, uuid.Nil)
			return
		}
		unwrapped, err = cryptoutil.GenerateDataKey()
		if err != nil {
			log.Warn("scheduler: data key gen", "err", err)
			r.advance(ctx, sch, now, uuid.Nil)
			return
		}
		wrapped, err = r.masterKey.Wrap(unwrapped)
		if err != nil {
			log.Warn("scheduler: wrap", "err", err)
			r.advance(ctx, sch, now, uuid.Nil)
			return
		}
	}

	backupID := uuid.New()
	envJSON, _ := json.Marshal(inst.Env)

	if err := r.store.CreateBackup(ctx, store.CreateBackupParams{
		BackupID:       backupID,
		WorkspaceID:    inst.WorkspaceID,
		InstanceID:     sch.InstanceID,
		DisplayName:    fmt.Sprintf("scheduled: %s", sch.CronExpr),
		StorageMode:    storageMode,
		Encrypted:      sch.Encrypt,
		WrappedDataKey: wrapped,
		EggID:          inst.EggID,
		EnvJSON:        envJSON,
		MemoryBytes:    inst.MemoryBytes,
		CPUShares:      inst.CPUShares,
	}); err != nil {
		log.Warn("scheduler: create backup row", "err", err)
		r.advance(ctx, sch, now, uuid.Nil)
		return
	}

	payload := protocol.BackupCreatePayload{
		BackupID:       backupID.String(),
		InstanceID:     sch.InstanceID.String(),
		ChunkSizeBytes: r.chunkSize,
		Encrypted:      sch.Encrypt,
		EncryptionKey:  unwrapped,
		ChunkUploadURL: r.chunkBaseURL,
	}
	taskID, _, err := r.dispatcher.SendTask(ctx, dispatcher.SendTaskParams{
		KeeperID:   inst.KeeperID,
		InstanceID: &sch.InstanceID,
		Kind:       protocol.TaskBackupCreate,
		Payload:    payload,
	}, false)
	if err != nil {
		log.Warn("scheduler: dispatch backup", "err", err)
		_ = r.store.FailBackup(ctx, backupID, "scheduler dispatch failed: "+err.Error())
		r.advance(ctx, sch, now, backupID)
		return
	}
	log.Info("scheduled backup fired",
		"backup_id", backupID,
		"task_id", taskID,
		"encrypted", sch.Encrypt)

	r.advance(ctx, sch, now, backupID)
	r.pruneRetention(ctx, sch)
}

// advance computes the next fire time from the cron expression and persists it.
func (r *Runner) advance(ctx context.Context, sch store.BackupSchedule, now time.Time, lastBackupID uuid.UUID) {
	schedule, err := cronParser.Parse(sch.CronExpr)
	if err != nil {
		// Bad cron expression: move forward an hour so we don't loop.
		r.log.Warn("scheduler: invalid cron, advancing 1h", "schedule_id", sch.ScheduleID, "expr", sch.CronExpr, "err", err)
		_ = r.store.AdvanceSchedule(ctx, sch.ScheduleID, now, now.Add(1*time.Hour), lastBackupID)
		return
	}
	next := schedule.Next(now)
	if err := r.store.AdvanceSchedule(ctx, sch.ScheduleID, now, next, lastBackupID); err != nil {
		r.log.Warn("scheduler: advance schedule", "err", err)
	}
}

// pruneRetention deletes backups beyond the schedule's retention window.
// Retention of 0 means "keep all".
func (r *Runner) pruneRetention(ctx context.Context, sch store.BackupSchedule) {
	if sch.Retention <= 0 {
		return
	}
	old, err := r.store.ListOldBackupsForRetention(ctx, sch.InstanceID, sch.Retention)
	if err != nil {
		r.log.Warn("scheduler: list old backups", "err", err)
		return
	}
	for _, id := range old {
		if err := r.store.DeleteBackup(ctx, id); err != nil {
			r.log.Warn("scheduler: delete old backup", "backup_id", id, "err", err)
			continue
		}
		r.log.Info("scheduler: pruned old backup", "backup_id", id, "schedule_id", sch.ScheduleID)
	}
}

// NextFromExpression is a helper the API uses to compute next_run_at at
// create time. Exposed so the handler doesn't import cron directly.
func NextFromExpression(expr string, from time.Time) (time.Time, error) {
	s, err := cronParser.Parse(expr)
	if err != nil {
		return time.Time{}, err
	}
	return s.Next(from), nil
}
