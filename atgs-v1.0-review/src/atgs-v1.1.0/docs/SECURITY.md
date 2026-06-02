# ATGS Security Model

This document describes the security model for the current source tree, not an aspirational future one.

## Identity layers

ATGS has two distinct identity planes:

1. Machine identity
   - Keepers, Relays, and Progenitor installs authenticate with Central-issued mTLS certificates.
2. Human identity
   - Admin users authenticate to Central with email and password.

Machine identity is what protects keeper enrollment, relay sync, and the control/data channels. Human identity is what protects the admin API and Progenitor workflows.

## Internal CA

Central owns the CA.

`central bootstrap-ca` creates the CA material under `ATGS_CENTRAL_CA_DIR`.

That CA signs:

- Keeper certificates
- Relay certificates
- Progenitor certificates
- the dev-mode Central server leaf

Protect `ATGS_CENTRAL_CA_DIR` as a critical secret. If it is compromised, every machine identity in the deployment must be reissued.

## Signed control-channel envelopes

The control channel supports Ed25519 envelope signing. Central loads its signing key from the CA directory. Keepers store their Ed25519 identity in the keeper state directory.

Strict enforcement is controlled with:

- `ATGS_CENTRAL_REQUIRE_SIGNED_TASKS=true`

Leave this off during mixed rollouts where older keepers have not re-enrolled yet. Turn it on only when every active keeper has an Ed25519 identity on disk.

## Revocation

ATGS uses a two-part revocation model:

1. Active revoke task
   - Central sends `keeper.revoke` to wipe local keeper identity if the keeper is online.
2. Certificate revocation list
   - Central stores revoked cert serials and rejects them at handshake time.

The active revoke path is convenience. The CRL is the durable control.

## Relay trust model

Relay is trusted infrastructure, not an untrusted edge client.

- Relay authenticates to Central with a Central-issued cert whose OU marks it as a relay.
- Keeper data-channel connections authenticate to Relay with keeper mTLS identities.
- Central stays out of the Java player byte stream and only distributes routing state.

That split is part of the current v1 contract and should not be blurred by operator docs or deployment shortcuts.

## Passwords and sessions

- Admin passwords are hashed server-side.
- Human auth endpoints live under `/api/v1/auth/*`.
- Progenitor's current mTLS admin-path behavior is still part of the deployed contract and should not be changed casually during v1 stabilization.

## What this source tree does not claim yet

- production-proven Bedrock relay security properties under real hostile internet conditions
- horizontally scaled relay hardening under hostile internet load
- full protection from a compromised Keeper host lying about its own state

Keepers are still trusted to execute tasks faithfully. A rooted Keeper can always harm the servers it already hosts.

## Operator guidance

- Keep the CA directory private and backed up.
- Do not expose the admin API wider than needed.
- Keep `ATGS_KEEPER_INSECURE_TLS=false` outside local dev.
- Treat relay bundles and progenitor bundles like credentials.
- Revoke and re-enroll a keeper immediately if you suspect compromise.
