# Suite Architecture

The relaunch architecture is intentionally smaller than the old suite.

## Active Modules

- `CrownsAPI` is the platform core.
- `CrownsMagic` is a gameplay plugin built on API gesture casting.
- `CrownsSwords` is a gameplay plugin built on the same action-combat API.

## CrownsAPI Responsibilities

- Suite/module registration.
- Shared GUI shell.
- Resource-pack metadata and delivery.
- Gesture input service.
- Cooldowns and resource meters.
- Ability events and targeting helpers.
- Shared particle helpers.

## Gameplay Plugin Rules

- Gameplay plugins should depend on `CrownsAPI`.
- Plugins should not directly couple to each other.
- Magic and Swords use the same gesture language so future bosses/entities can react to one shared ability event model.

## Deprecated Work

The old economy/admin/events/drugs/MMO/terrain work is preserved on `deprecated/pre-relaunch-suite`. It is not part of the active `master` branch.
