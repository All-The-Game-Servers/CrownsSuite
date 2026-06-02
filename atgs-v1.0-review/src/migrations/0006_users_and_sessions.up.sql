-- Phase 8: Users. ATGS now has human identities distinct from machine
-- identities (keepers/relays/progenitor). Single-tenant: all users belong
-- to the single ATGS deployment; no org_id scoping. If we ever go
-- multi-tenant, add org_id and backfill from a default org.
--
-- Password hashes use argon2id. The hash column stores the full PHC string
-- ($argon2id$v=19$m=...$t=...$p=...$salt$hash) so algorithm params are
-- self-describing and we can rotate without schema changes.
--
-- stripe_customer_id is nullable because users are created before any
-- payment method; the billing subsystem populates it on first subscription.

CREATE TYPE user_role AS ENUM ('admin', 'operator', 'viewer');

CREATE TABLE IF NOT EXISTS users (
    user_id            UUID PRIMARY KEY,
    email              TEXT NOT NULL UNIQUE,
    password_hash      TEXT NOT NULL,                 -- argon2id PHC string
    role               user_role NOT NULL DEFAULT 'operator',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at      TIMESTAMPTZ,
    stripe_customer_id TEXT UNIQUE,                   -- filled on first billing event
    disabled_at        TIMESTAMPTZ                    -- soft-disable; nullable
);

CREATE INDEX idx_users_role ON users (role) WHERE disabled_at IS NULL;

-- Sessions. Stored server-side so we can revoke on logout, role change, or
-- password rotation. Token is a random 32-byte value; client receives it as
-- an HTTP-only cookie. The server stores sha256(token) to avoid leaking
-- tokens if the table is ever dumped.
CREATE TABLE IF NOT EXISTS user_sessions (
    token_hash   TEXT PRIMARY KEY,                    -- sha256 hex
    user_id      UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_ip    INET,
    user_agent   TEXT
);

CREATE INDEX idx_user_sessions_user ON user_sessions (user_id);
CREATE INDEX idx_user_sessions_expires ON user_sessions (expires_at);

-- Instance ownership. Nullable because instances created pre-Phase-8 have
-- no owner; those are implicitly owned by the admin user. Billing sums
-- slots over instances where owner_user_id IS NOT NULL.
ALTER TABLE instances
    ADD COLUMN owner_user_id UUID REFERENCES users(user_id) ON DELETE SET NULL;

CREATE INDEX idx_instances_owner ON instances (owner_user_id)
    WHERE deleted_at IS NULL AND owner_user_id IS NOT NULL;

COMMENT ON COLUMN instances.owner_user_id IS
    'The user who owns this instance for billing and ACL purposes. '
    'NULL means system-owned (pre-Phase-8 or admin-created without owner).';
