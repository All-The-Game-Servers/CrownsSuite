-- Phase 8: WireGuard identity for the relay-to-keeper UDP tunnel.
--
-- Keepers generate a Curve25519 keypair at enrollment (separate from mTLS
-- and Ed25519). Central stores the public key and the internal tunnel
-- endpoint. Relays fetch per-keeper WG pubkeys via the routing bus when
-- dispatching Bedrock tunnels.

ALTER TABLE keepers
    ADD COLUMN wg_public_key BYTEA,                     -- raw 32 bytes
    ADD COLUMN wg_endpoint   TEXT,                      -- "ip:port" keeper listens on
    ADD COLUMN wg_allowed_ip INET;                      -- keeper's internal tunnel IP

COMMENT ON COLUMN keepers.wg_public_key IS
    'WireGuard Curve25519 public key, 32 bytes. Used by relays to build '
    'per-keeper WG peers for Bedrock UDP tunneling. NULL for pre-Phase-8 '
    'keepers until they re-enroll.';

COMMENT ON COLUMN keepers.wg_endpoint IS
    'host:port the keeper listens on for WireGuard peer traffic.';

COMMENT ON COLUMN keepers.wg_allowed_ip IS
    'The internal tunnel IP assigned to this keeper inside the WG mesh. '
    'Central allocates from a 10.100.0.0/16 pool (one /32 per keeper).';
