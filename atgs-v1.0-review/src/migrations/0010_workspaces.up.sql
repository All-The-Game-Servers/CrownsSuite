-- Phase 10 / v1.2: workspace-first multi-tenant foundation.
--
-- Upgrade strategy:
--   1. Introduce workspaces + memberships without changing any stable IDs.
--   2. Create one default workspace for pre-v1.2 deployments.
--   3. Backfill every keeper, instance, backup, schedule, and enrollment token
--      into that default workspace.
--   4. Keep relay/routing behavior unchanged; the new workspace fields are
--      purely ownership / authorization metadata in v1.2.

CREATE TABLE IF NOT EXISTS workspaces (
    workspace_id                 UUID PRIMARY KEY,
    slug                         TEXT NOT NULL UNIQUE,
    display_name                 TEXT NOT NULL,
    owner_user_id                UUID REFERENCES users(user_id) ON DELETE SET NULL,
    mock_plan_key                TEXT NOT NULL DEFAULT 'founder',
    mock_subscription_status     TEXT NOT NULL DEFAULT 'active',
    mock_subscription_seat_limit INT NOT NULL DEFAULT 1,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS workspace_memberships (
    workspace_id  UUID NOT NULL REFERENCES workspaces(workspace_id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    role          TEXT NOT NULL,
    capabilities  JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_workspace_memberships_user
    ON workspace_memberships (user_id, workspace_id);

CREATE TABLE IF NOT EXISTS workspace_subscriptions (
    workspace_id    UUID PRIMARY KEY REFERENCES workspaces(workspace_id) ON DELETE CASCADE,
    plan_key        TEXT NOT NULL DEFAULT 'founder',
    status          TEXT NOT NULL DEFAULT 'active',
    seat_limit      INT NOT NULL DEFAULT 1,
    note            TEXT NOT NULL DEFAULT '',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE enrollment_tokens
    ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES workspaces(workspace_id) ON DELETE SET NULL;

ALTER TABLE keepers
    ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES workspaces(workspace_id) ON DELETE RESTRICT;

ALTER TABLE instances
    ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES workspaces(workspace_id) ON DELETE RESTRICT;

ALTER TABLE backups
    ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES workspaces(workspace_id) ON DELETE RESTRICT;

ALTER TABLE backup_schedules
    ADD COLUMN IF NOT EXISTS workspace_id UUID REFERENCES workspaces(workspace_id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_keepers_workspace ON keepers (workspace_id);
CREATE INDEX IF NOT EXISTS idx_instances_workspace ON instances (workspace_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_backups_workspace ON backups (workspace_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_backup_schedules_workspace ON backup_schedules (workspace_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_enrollment_tokens_workspace ON enrollment_tokens (workspace_id) WHERE used_at IS NULL;

INSERT INTO workspaces (
    workspace_id, slug, display_name, owner_user_id,
    mock_plan_key, mock_subscription_status, mock_subscription_seat_limit
)
SELECT
    '00000000-0000-0000-0000-000000001200'::uuid,
    'default',
    'Default Workspace',
    (
        SELECT user_id
        FROM users
        WHERE role IN ('admin', 'operator') AND disabled_at IS NULL
        ORDER BY created_at ASC
        LIMIT 1
    ),
    'founder',
    'active',
    8
WHERE NOT EXISTS (
    SELECT 1 FROM workspaces WHERE workspace_id = '00000000-0000-0000-0000-000000001200'::uuid
);

INSERT INTO workspace_subscriptions (workspace_id, plan_key, status, seat_limit, note)
SELECT
    workspace_id,
    mock_plan_key,
    mock_subscription_status,
    mock_subscription_seat_limit,
    'Backfilled default subscription state during v1.2 migration'
FROM workspaces
WHERE workspace_id = '00000000-0000-0000-0000-000000001200'::uuid
ON CONFLICT (workspace_id) DO NOTHING;

INSERT INTO workspace_memberships (workspace_id, user_id, role, capabilities)
SELECT
    '00000000-0000-0000-0000-000000001200'::uuid,
    user_id,
    CASE
        WHEN role IN ('admin', 'operator') THEN 'owner'
        ELSE 'member'
    END,
    CASE
        WHEN role IN ('admin', 'operator') THEN
            '["instance.lifecycle","logs.read","console.write","files.read","files.write","backups.manage","schedules.manage","members.manage","keepers.view"]'::jsonb
        ELSE
            '["logs.read","console.write","files.read","files.write","backups.manage","schedules.manage","keepers.view"]'::jsonb
    END
FROM users
WHERE disabled_at IS NULL
ON CONFLICT (workspace_id, user_id) DO NOTHING;

UPDATE enrollment_tokens
SET workspace_id = '00000000-0000-0000-0000-000000001200'::uuid
WHERE workspace_id IS NULL;

UPDATE keepers
SET workspace_id = '00000000-0000-0000-0000-000000001200'::uuid
WHERE workspace_id IS NULL;

UPDATE instances
SET workspace_id = '00000000-0000-0000-0000-000000001200'::uuid
WHERE workspace_id IS NULL;

UPDATE backups
SET workspace_id = COALESCE(
    backups.workspace_id,
    (SELECT i.workspace_id FROM instances i WHERE i.instance_id = backups.instance_id),
    '00000000-0000-0000-0000-000000001200'::uuid
)
WHERE workspace_id IS NULL;

UPDATE backup_schedules
SET workspace_id = COALESCE(
    backup_schedules.workspace_id,
    (SELECT i.workspace_id FROM instances i WHERE i.instance_id = backup_schedules.instance_id),
    '00000000-0000-0000-0000-000000001200'::uuid
)
WHERE workspace_id IS NULL;

ALTER TABLE enrollment_tokens
    ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE keepers
    ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE instances
    ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE backups
    ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE backup_schedules
    ALTER COLUMN workspace_id SET NOT NULL;
