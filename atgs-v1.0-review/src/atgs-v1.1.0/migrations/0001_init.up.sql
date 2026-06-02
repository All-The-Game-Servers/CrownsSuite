-- 0001_init.up.sql
-- Initial schema for ATGS Central.
--
-- Design notes:
-- * keeper_id is the stable identity for a host. It's a UUID minted at
--   enrollment and never reused. The mTLS cert's CommonName carries this value.
-- * enrollment_tokens are single-use and short-lived. used_at nails down the
--   exact consumption time for audit.
-- * keeper_sessions tracks control-channel sessions, not Keepers themselves.
--   A Keeper with long uptime will have many session rows across reconnects;
--   that's intentional, because it makes "when did this Keeper reconnect" a
--   simple query.

CREATE TABLE IF NOT EXISTS keepers (
    keeper_id              UUID PRIMARY KEY,
    display_name           TEXT NOT NULL,
    public_key_fingerprint TEXT NOT NULL UNIQUE,
    platform               TEXT NOT NULL,        -- linux / windows / darwin
    arch                   TEXT NOT NULL,        -- amd64 / arm64
    hostname               TEXT NOT NULL,        -- self-reported, untrusted
    agent_version          TEXT NOT NULL,        -- self-reported, untrusted
    enrolled_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cert_not_after         TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,
    last_seen_at           TIMESTAMPTZ           -- updated on each ws connect
);

CREATE INDEX idx_keepers_revoked ON keepers (revoked_at) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS enrollment_tokens (
    token_hash     TEXT PRIMARY KEY,             -- sha256(token), never the raw token
    created_by     TEXT NOT NULL,                -- progenitor identifier (stubbed in Phase 1)
    note           TEXT NOT NULL DEFAULT '',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ NOT NULL,
    used_at        TIMESTAMPTZ,                  -- null until consumed
    used_by_keeper UUID REFERENCES keepers(keeper_id)
);

CREATE INDEX idx_enrollment_tokens_expires ON enrollment_tokens (expires_at) WHERE used_at IS NULL;

CREATE TABLE IF NOT EXISTS keeper_sessions (
    session_id    UUID PRIMARY KEY,
    keeper_id     UUID NOT NULL REFERENCES keepers(keeper_id),
    connected_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    disconnected_at TIMESTAMPTZ,
    remote_addr   TEXT NOT NULL,
    disconnect_reason TEXT
);

CREATE INDEX idx_keeper_sessions_live ON keeper_sessions (keeper_id) WHERE disconnected_at IS NULL;

-- audit_log captures every event Central considers worth explaining later.
-- In Phase 1 we mostly log enrollment and session lifecycle; Phase 7
-- hardening extends this with full task audit.
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    kind        TEXT NOT NULL,      -- e.g. 'enrollment.minted', 'keeper.connected'
    actor       TEXT NOT NULL,      -- 'keeper:<uuid>' | 'progenitor:<id>' | 'system'
    keeper_id   UUID,
    details     JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_audit_at ON audit_log (at DESC);
CREATE INDEX idx_audit_keeper ON audit_log (keeper_id, at DESC);
