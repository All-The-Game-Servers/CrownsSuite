-- 0003_routing.up.sql
--
-- Phase 3 adds the fields and sequence needed for relay routing.
--
-- Why version is global across the whole routing table (not per-row): relays
-- resume sync by saying "I have version N, give me everything newer." A
-- global monotonic version makes that trivial. Per-row versions would force
-- a scan on resume.
--
-- hostname is UNIQUE across non-deleted instances. Deletes free the name
-- immediately so a new instance can reuse it.

ALTER TABLE instances
    ADD COLUMN hostname  TEXT,
    ADD COLUMN host_port INT;

-- Partial unique index: hostname must be unique among living instances.
CREATE UNIQUE INDEX idx_instances_hostname_unique
    ON instances (hostname)
    WHERE hostname IS NOT NULL AND deleted_at IS NULL;

-- Global sequence for the routing version. BIGINT so it doesn't wrap this century.
CREATE SEQUENCE routing_version_seq AS BIGINT START 1;

-- routing_events is an append-only log of routing changes. Relays read from
-- here when resuming with a known_version > 0. The rows are never deleted;
-- trimming is a Phase 7 concern (retain N most recent versions).
--
-- event types:
--   'upsert' -> hostname now points at (instance_id, keeper_id, host_port)
--   'delete' -> hostname no longer routes anywhere
CREATE TABLE routing_events (
    version      BIGINT PRIMARY KEY DEFAULT nextval('routing_version_seq'),
    at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_type   TEXT NOT NULL,
    hostname     TEXT NOT NULL,
    instance_id  UUID,
    keeper_id    UUID,
    host_port    INT
);

CREATE INDEX idx_routing_events_hostname ON routing_events (hostname, version DESC);
