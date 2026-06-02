-- 0004_backups.up.sql
--
-- Phase 4: Backups and restores.
--
-- Three tables:
--
--   backups           one row per backup, with status and manifest metadata
--   backup_chunks     content-addressed chunks; many-to-many with backups via
--                     the chunks stored in the manifest json (not a join table
--                     because chunks dedupe globally and ordering matters)
--   backup_schedules  cron-style scheduled backups per instance
--
-- A backup is "created" the moment the API returns; it transitions through
-- pending -> uploading -> complete or failed. Restore works off complete ones.

CREATE TYPE backup_status AS ENUM ('pending', 'uploading', 'complete', 'failed');
CREATE TYPE backup_storage AS ENUM ('central_fs', 'object_storage');

CREATE TABLE backups (
    backup_id         UUID PRIMARY KEY,
    instance_id       UUID NOT NULL REFERENCES instances(instance_id),
    display_name      TEXT NOT NULL DEFAULT '',
    status            backup_status NOT NULL DEFAULT 'pending',
    storage_mode      backup_storage NOT NULL,
    -- Size and chunk count; 0 until first upload lands.
    total_bytes       BIGINT NOT NULL DEFAULT 0,
    chunk_count       INT    NOT NULL DEFAULT 0,
    -- Content of the egg + env at backup time. Captured in Central from the
    -- instance row at create time so the restore doesn't depend on the source
    -- instance still existing.
    egg_id            TEXT NOT NULL,
    env_json          JSONB NOT NULL DEFAULT '{}'::jsonb,
    memory_bytes      BIGINT NOT NULL DEFAULT 0,
    cpu_shares        BIGINT NOT NULL DEFAULT 0,
    -- Manifest is a JSON document listing chunk hashes in order. Populated
    -- when the backup reaches 'uploading' status and finalized on 'complete'.
    --   {"chunks": [{"sha256": "...", "size": 4194304, "seq": 0}, ...],
    --    "total_size": 123456, "archive_format": "tar",
    --    "encrypted": true, "key_fingerprint": "sha256:abc..."}
    manifest          JSONB,
    -- Encryption: if encrypted, the per-backup symmetric key is stored here,
    -- wrapped by Central's master key (kept out of the DB, in Central's config).
    -- For Phase 4, we store the wrapped key directly in this column; Phase 7
    -- hardening would move this to a KMS.
    encrypted         BOOLEAN NOT NULL DEFAULT FALSE,
    wrapped_data_key  BYTEA,
    -- Object storage backups store the bucket+prefix+key of the manifest.
    -- Central_fs backups use central_fs_path (filesystem path relative to
    -- the backup root configured in Central).
    object_storage_uri TEXT,
    central_fs_path    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ,
    error_message     TEXT
);

CREATE INDEX idx_backups_instance ON backups (instance_id, created_at DESC);
CREATE INDEX idx_backups_status   ON backups (status) WHERE status IN ('pending', 'uploading');

-- Chunks are content-addressed and deduplicated globally. A chunk uploaded
-- by backup A is also available to backup B if their content happens to match.
-- sha256 is hex-encoded (64 chars) for readability in logs; the storage
-- layer uses the same string as the object key / filename.
CREATE TABLE backup_chunks (
    sha256        TEXT PRIMARY KEY,
    size_bytes    INT NOT NULL,
    storage_mode  backup_storage NOT NULL,
    -- Whichever of these two is populated depends on storage_mode.
    central_fs_path    TEXT,
    object_storage_uri TEXT,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- refcount: how many non-deleted backups reference this chunk.
    -- Updated by trigger or by the backup deletion path; a chunk with
    -- refcount=0 can be garbage collected.
    ref_count     INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_backup_chunks_refcount ON backup_chunks (ref_count) WHERE ref_count = 0;

CREATE TABLE backup_schedules (
    schedule_id   UUID PRIMARY KEY,
    instance_id   UUID NOT NULL REFERENCES instances(instance_id),
    cron_expr     TEXT NOT NULL, -- e.g. "0 3 * * *" for 3am daily
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    -- Retention: keep this many most recent backups; older ones auto-deleted.
    -- 0 means no auto-pruning.
    retention     INT NOT NULL DEFAULT 7,
    -- Storage choice for this schedule's backups; if null, Central's default applies.
    storage_mode  backup_storage,
    -- Whether to encrypt scheduled backups.
    encrypt       BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at   TIMESTAMPTZ NOT NULL,
    last_run_at   TIMESTAMPTZ,
    last_backup_id UUID REFERENCES backups(backup_id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_backup_schedules_next_run ON backup_schedules (next_run_at) WHERE enabled = TRUE;
CREATE INDEX idx_backup_schedules_instance ON backup_schedules (instance_id);
