-- Phase 9: operator visibility for keeper resource health.
--
-- Stores the latest resource sample Central has received from each Keeper.
-- This is advisory data for operators and future scheduling/migration prep.

CREATE TABLE IF NOT EXISTS keeper_resource_snapshots (
    keeper_id         UUID PRIMARY KEY REFERENCES keepers(keeper_id) ON DELETE CASCADE,
    reported_at       TIMESTAMPTZ NOT NULL,
    cpu_cores         INT NOT NULL,
    cpu_percent_used  DOUBLE PRECISION NOT NULL,
    mem_total_bytes   BIGINT NOT NULL,
    mem_used_bytes    BIGINT NOT NULL,
    disk_total_bytes  BIGINT NOT NULL,
    disk_used_bytes   BIGINT NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_keeper_resource_snapshots_reported_at
    ON keeper_resource_snapshots (reported_at DESC);
