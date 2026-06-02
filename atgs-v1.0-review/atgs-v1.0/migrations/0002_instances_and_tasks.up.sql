-- 0002_instances_and_tasks.up.sql
--
-- Phase 2 adds the concepts of "instance" (a game server that exists on a
-- Keeper) and "task" (a unit of work dispatched to a Keeper).
--
-- Instance vs container: an instance is the Central-side record of a server
-- that is supposed to exist. The container_id is the Keeper-side Docker
-- container that implements it. If the container is gone but the instance
-- row exists, that's a drift condition Central will surface.
--
-- Tasks are an audit trail + a work queue. For Phase 2 the "queue" is very
-- simple: tasks are dispatched immediately if the Keeper is connected,
-- otherwise they're marked queued and dispatched when the Keeper connects.

CREATE TABLE IF NOT EXISTS instances (
    instance_id    UUID PRIMARY KEY,
    keeper_id      UUID NOT NULL REFERENCES keepers(keeper_id),
    egg_id         TEXT NOT NULL,
    display_name   TEXT NOT NULL,
    state          TEXT NOT NULL DEFAULT 'created',  -- created | running | stopped | error | deleted
    container_id   TEXT,                             -- populated after keeper creates container
    memory_bytes   BIGINT NOT NULL,
    cpu_shares     BIGINT NOT NULL,
    env            JSONB NOT NULL DEFAULT '{}'::jsonb,
    port_mappings  JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ
);

CREATE INDEX idx_instances_keeper ON instances (keeper_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_instances_state ON instances (state) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS tasks (
    task_id         UUID PRIMARY KEY,
    keeper_id       UUID NOT NULL REFERENCES keepers(keeper_id),
    instance_id     UUID REFERENCES instances(instance_id),   -- null for keeper-level tasks
    kind            TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'queued',           -- queued | dispatched | running | succeeded | failed | timed_out
    payload         JSONB NOT NULL,
    result          JSONB,                                    -- set on succeeded/failed
    error_code      TEXT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at   TIMESTAMPTZ,
    acked_at        TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    timeout_secs    INT NOT NULL DEFAULT 60
);

CREATE INDEX idx_tasks_keeper ON tasks (keeper_id, created_at DESC);
CREATE INDEX idx_tasks_instance ON tasks (instance_id, created_at DESC) WHERE instance_id IS NOT NULL;
CREATE INDEX idx_tasks_pending ON tasks (keeper_id) WHERE status IN ('queued', 'dispatched', 'running');
