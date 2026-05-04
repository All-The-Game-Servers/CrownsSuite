# CrownsMMO

CrownsMMO is the Season 2 MMO progression plugin for Lowlight SMP.

It is designed as a Crowns Suite replacement for ValhallaMMO with a stronger `sandbox MMO` direction:

- SAO-inspired progression identity
- Per-skill perk trees
- Gathering, professions, combat, and world progression together
- Light active skills instead of overloaded MMO action bars
- Faster, more addictive progression without rewarding cheap repetitive spam

## First Pass

The first CrownsMMO implementation currently includes:

- Skill lines for gathering, professions, combat, and exploration
- Persistent levels and XP
- Perk unlocks every 5 levels
- World progression tracking for boss clears and biome discoveries
- Light active skills:
  - `Battle Surge`
  - `Ranger Focus`
  - `Bulwark`
  - `Pathfinder`

## 1.3.0 Floor Worlds

CrownsMMO 1.3.0 begins the true `Floor` structure inspired by Sword Art Online.

- Floor 1 is the normal livable spawn world, bordered at `16,000 x 16,000` blocks by default.
- Higher floors are separate CrownsMMO-owned worlds, such as `crowns_floor_2`.
- Players unlock the next floor by clearing the current Floor Boss.
- Unlocks are personal for now. Guild and Party credit will plug into this system later.
- Higher floors scale normal hostile mobs upward so each new floor feels meaningfully more dangerous.
- Boss arenas are chosen once, saved, and reused across restarts.

Useful commands:

- `/cmmo floors` opens the floor selector.
- `/cmmo floor <number>` teleports to an unlocked floor.
- `/cmmo boss` shows the current floor's boss arena.
- `/cmmo boss start` starts the encounter when you are at the arena.

Staff setup commands:

- `/cmmo admin floor setspawn <floor>`
- `/cmmo admin floor setboss <floor>`
- `/cmmo admin floor unlock <player> <floor>`

## 1.4.0 Floor Resources And Adventurer Gear

CrownsMMO 1.4.0 makes Floors 1-3 valuable to explore after they are unlocked.

- Floor-specific materials now drop from mining, farming, fishing, and combat.
- Floor Bosses award personal loot to credited players so rewards are not stolen by one pickup.
- MMO items are tagged internally and use stable `lowlight/mmo/...` model paths for the resource pack.
- Adventurer gear is utility-first rather than raw power creep.
- Crafting uses vanilla stations where practical:
  - crafting table for charms, compasses, satchels, and utility gear
  - smithing table for early gear upgrades like Pathfinder Boots and Wardenhide Cloak

Useful commands:

- `/cmmo resources` opens the floor resource guide.
- `/cmmo gear` opens the adventurer gear guide.
- `/cmmo recipes` opens recipe guidance.

Staff commands:

- `/cmmo admin item give <player> <item_key> [amount]`
- `/cmmo admin resources reload`

Current utility gear examples:

- `Pathfinder Boots`: floor-world speed and minor fall protection.
- `Deep Miner's Charm`: improves floor mining material rolls.
- `Gatebreaker Compass`: grants vision support in deeper floors.
- `Forager Satchel`: improves floor farming and fishing material rolls.
- `Wardenhide Cloak`: minor floor-world damage reduction.

## 1.5.0 Parties And Guild Foundations

CrownsMMO 1.5.0 adds the first social systems for floor climbing.

- Parties are temporary adventuring groups for Floor Bosses.
- If a party member starts a Floor Boss, only nearby party members receive boss credit, floor unlocks, XP, and personal loot.
- Solo players can still start bosses without creating a party.
- Guilds are persistent identity shells with name, tag, owner, officers, members, invites, and MOTD.
- Guild XP, banks, perks, territory, and raid progression are planned for later.

Party commands:

- `/cmmo party create`
- `/cmmo party invite <player>`
- `/cmmo party accept`
- `/cmmo party leave`
- `/cmmo party kick <player>`
- `/cmmo party disband`
- `/cmmo party info`

Guild commands:

- `/cmmo guild create <name> <tag>`
- `/cmmo guild invite <player>`
- `/cmmo guild accept`
- `/cmmo guild leave`
- `/cmmo guild kick <player>`
- `/cmmo guild promote <player>`
- `/cmmo guild demote <player>`
- `/cmmo guild motd <text>`
- `/cmmo guild info`

## 1.6.0 Quest Foundations

CrownsMMO 1.6.0 adds the first lightweight quest layer that ties floor progression to places and activities.

- Quests can track terrain exploration, mob kills, Floor Boss clears, gathering, and item turn-ins.
- CrownsTerrain points such as villages, landmarks, road markers, shrines, and arenas can become quest objectives when CrownsTerrain is installed.
- Quest rewards can pay Crowns through CrownsEconomy and award CrownsMMO XP.
- Progress is stored in additive tables, so existing skills, floors, parties, guilds, and boss history are not wiped.
- If CrownsTerrain or CrownsEconomy is missing, relevant objectives/rewards degrade safely instead of crashing.

Player commands:

- `/cmmo quests`
- `/cmmo quests active`
- `/cmmo quest <id>`

Staff commands:

- `/cmmo admin quest grant <player> <quest>`
- `/cmmo admin quest reset <player> <quest>`
- `/cmmo admin quest reload`
- `/cmmo admin quest debug <player>`

## 1.7.0 Playable Floor Quests

CrownsMMO 1.7.0 turns the quest foundation into a playable Floor 1 onboarding path.

Use `/cmmo start` to enter the configured dedicated Floor 1 world at First Haven. CrownsTerrain `1.5.2` uses the fresh world `crowns_floor_1_v2`; update the MMO Floor 1 world config to that name when adopting the terrain relaunch.

The first questline is `First Haven Path`, a board-driven story path for new adventurers:

- `First Haven Scout`: find a Floor 1 village or haven settlement.
- `Road Marker Run`: discover three road markers so travel routes start making sense.
- `Starter Stores`: gather Copperleaf from Floor 1 mining drops.
- `Camp Sweep`: find a camp outside the haven routes.
- `Undead Pressure`: defeat ten Floor 1 zombies.
- `Gatekeeper Preparations`: turn in three Floor 1 Gate Splinters.
- `The First Gate`: defeat the Floor 1 Gatekeeper.

Open `/cmmo quests` to use the quest board. The board is split into `Available Quests`, `Active Quests`, `Completed Quests`, and `Floor 1 Story Path`.

Quest detail pages show the objective, current progress, destination hints, reward preview, and whether optional providers are online:

- CrownsTerrain helps exploration quests point to villages, camps, road markers, and landmarks.
- CrownsEconomy pays Crowns rewards when installed.
- CrownsMMO XP rewards still work without CrownsEconomy.
- Exploration leveling is intentionally slower after the relaunch: walking through biomes no longer grants XP by default, and discovery XP now comes from meaningful quest/POI discoveries.

Player commands:

- `/cmmo start`
- `/cmmo quests`
- `/cmmo quests active`
- `/cmmo quests completed`
- `/cmmo quests floor <number>`
- `/cmmo quest <id>`

Staff commands:

- `/cmmo admin quest grant <player> <quest>`
- `/cmmo admin quest reset <player> <quest>`
- `/cmmo admin quest reload`
- `/cmmo admin quest debug <player>`
- `/cmmo admin quest inspect <player>`

## Season 2 Direction

CrownsMMO is intended for Season 2 rather than as a mid-season replacement.

- `CrownsMMO only` launch target
- No required Valhalla progression migration
- MMO profile integrated into the Crowns Suite GUI
