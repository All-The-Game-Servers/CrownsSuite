-- Phase 7: Each keeper now has an Ed25519 public key for verifying
-- envelopes it sends to Central. The column is nullable for backwards
-- compatibility during the transition window; policy on requiring a key
-- is enforced in code, gated by ATGS_CENTRAL_REQUIRE_SIGNED_TASKS.

ALTER TABLE keepers
    ADD COLUMN ed25519_public_key BYTEA;

COMMENT ON COLUMN keepers.ed25519_public_key IS
    'Raw 32-byte Ed25519 public key the Keeper generated at enrollment. '
    'Used to verify envelopes the Keeper sends on its control channel. '
    'NULL for pre-Phase-7 keepers until they re-enroll.';

-- Phase 7 kill switch: record the cert serial at enrollment so the CRL
-- can reference it when the keeper is revoked. Nullable for transition.
ALTER TABLE keepers
    ADD COLUMN cert_serial_hex TEXT;

COMMENT ON COLUMN keepers.cert_serial_hex IS
    'Lowercase hex of the issued leaf certificate serial number. Used '
    'by Central''s CRL to revoke TLS connections after keeper.revoke. '
    'NULL for pre-Phase-7 keepers.';
