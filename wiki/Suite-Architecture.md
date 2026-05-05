# Suite Architecture

Crowns Suite is a modular server framework. Each plugin owns its feature area, while `CrownsAPI` owns shared contracts, navigation, diagnostics, data access, and resource-pack metadata.

BetterPvP is being used as architecture inspiration only: modular boundaries, dependency hygiene, and platform polish. No BetterPvP code, assets, schemas, or implementation details are copied into Crowns Suite.

## Module Boundaries

- `CrownsAPI`: service registry, shared database access, suite GUI shell, module health, resource-pack metadata, inbox, alerts, and activity bus.
- `CrownsEconomy`: balances, payments, auctions, stalls, jobs, demand, trader, gambling, commissions, server contracts, and market activity summaries.
- `CrownsAdmin`: moderation, reports, vanish/staff state, inspection, analytics, staff operations, and suite diagnostics via `/ca suite`.
- `CrownsEvents`: event lifecycle, archives, live moments, rewards, and suite activity hooks.
- `CrownsDrugs`: grow, process, use, sell, equipment, and Crowns-backed economy actions.
- `CrownsTerrain`: floor terrain shape, blueprint readiness, terrain points, debug maps, pregeneration, and physical floor health.
- `CrownsMMO`: floors, skills, quests, boss gates, parties, guilds, and progression. It reads Terrain/Economy/Event/Admin signals through API providers.

## Load Order

1. `CrownsAPI`
2. `CrownsEconomy`
3. `CrownsAdmin`
4. `CrownsEvents`
5. `CrownsDrugs`
6. `CrownsTerrain`
7. `CrownsMMO`

Only `CrownsAPI` is required by every plugin. Other dependencies are optional unless noted in each plugin's `plugin.yml`.

## Diagnostics

- `/capi status` opens or prints suite health.
- `/capi modules` lists installed/missing/degraded modules.
- `/capi downloads` shows current jar and resource-pack download metadata.
- `/ca suite` opens the staff-facing suite operations page.
- `/cmmo status` shows MMO readiness plus Terrain/Economy/Event/Admin/Drugs support state.

Module states:

- `MISSING`: plugin is expected but not installed or not registered.
- `LOADED`: plugin registered but has not reported full readiness.
- `DEGRADED`: plugin is usable, but one or more optional services or setup steps are missing.
- `READY`: plugin reports healthy runtime state.
- `FAILED`: plugin health check failed or a required service is unavailable.

## Terrain And MMO

CrownsMMO treats CrownsTerrain as optional but preferred. If Terrain is installed and Floor 1 is not ready, normal `/cmmo start` and `/cmmo floor 1` entry are blocked with admin fix steps. Staff should run:

1. `/cterrain admin blueprint 1`
2. `/cterrain admin debugmaps 1`
3. `/cterrain admin generate 1`
4. `/cterrain verify floor 1`

This keeps players from arriving in an ungenerated or half-built floor world.

## Downloads

The `downloads` folder should only contain current supported artifacts. Run `scripts/verify-suite.ps1` before publishing to compile/package current jars, verify `plugin.yml` versions, remove stale plugin jars, and refresh `CHECKSUMS.txt`.
