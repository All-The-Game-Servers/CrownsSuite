# Crowns Suite

Crowns Suite is the Lowlight SMP management toolkit by XKStudios. It is split into focused plugins so the server can grow without one giant jar doing everything.

`1.4.0` focuses on platform cohesion, module diagnostics, cleaner owner/admin workflows, and tighter MMO/Terrain/Economy/Admin integration. CrownsEvents is currently `1.5.0` for the Season 1 vanilla Endfall Opening Week, CrownsMMO is `1.8.0`, and CrownsTerrain is `1.8.5`.

## Plugins

### CrownsAPI
The shared backbone for the suite.

- Shared player data and balance storage
- Shared database access
- Shared GUI shell and section registration
- Shared inbox, menu, and provider contracts
- Required resource-pack delivery, GitHub Release refresh, and install info
- Suite profile and recent alert surfaces
- Suite status page for module versions, provider health, database state, resource-pack state, and download metadata
- Typed module registry with `MISSING`, `LOADED`, `DEGRADED`, `READY`, and `FAILED` states

### CrownsEconomy
The player trade and money loop.

- Wallets and player payments
- Auction House for timed bidding
- Permanent Market Stalls with branding and GUI upgrades
- Jobs for reliable contract income
- Server Demand Board for rotating buy orders
- Server Trader for themed curated money sinks
- Player commissions and server contracts

### CrownsAdmin
The staff and moderation toolkit.

- Moderation actions and logs
- Vanish, freeze, reports, and staff tools
- Operations dashboard and case-file style player review
- Entity tools and analytics

### CrownsEvents
The seasonal content and server event framework.

- Event lifecycle management
- Relics, milestones, and rewards
- Opening-week style SMP content
- Season 1 Endfall Opening Week for the vanilla End dimension
- Admin-triggered live moments for ceremonies and future server events
- Reusable suite activity hooks for MMO floor clears, economy milestones, terrain discoveries, and future ceremonies

### CrownsDrugs
The arcade-style criminal business plugin.

- Solo cartel progression
- Buy seeds and equipment, then grow, process, use, and sell physical contraband items
- CrownsEconomy-backed payouts, costs, and upgrades
- Black-market style business gameplay

### CrownsMMO
The Season 2 SAO-inspired MMO layer.

- Per-skill perk trees instead of one giant atlas
- Gathering, professions, combat, and world progression together
- Light active skills like `Battle Surge`, `Ranger Focus`, `Bulwark`, and `Pathfinder`
- Boss journal and chapter-style world progression
- Floor quests connected to terrain POIs, kills, turn-ins, XP, and Crowns rewards

### CrownsTerrain
The terrain identity layer for CrownsMMO floor worlds.

- WorldPainter-backed macro terrain for the current Floor 1 vertical slice
- Hybrid route-first floor engine generation for livable but authored worlds
- Floor-themed custom villages, landmarks, and boss arena locations
- Safe/useful settlement design for future NPCs, shops, and quests
- Optional provider layer so CrownsMMO can still run without it
- Stable generated point metadata for CrownsMMO quest objectives
- `1.8.0` adds the paired Floor Runtime Platform with Terrain-owned runtime state, anchors, QA lines, repair steps, and MMO-safe start/respawn/reconnect routing.
- `CrownsTerrain 1.8.1` adds custom `.ctpl` structure loading plus MagicaVoxel/Blender converter scripts for authored map pieces.
- `CrownsTerrain 1.8.2` adds in-game Structure Studio capture, preview, confirm, and placement commands.
- `CrownsTerrain 1.8.3` adds the Blender Floor 1 kit pipeline and larger `fh_*` templates for the First Haven critical route.
- `CrownsTerrain 1.8.4` adds offline visual QA previews and layout reports so Floor 1 can be reviewed before server testing.
- `CrownsTerrain 1.8.5` adds a WorldPainter macro terrain pipeline for `crowns_floor_1_wp_slice`, while Blender/`.ctpl` remains the structure layer.

## CrownsEconomy Overview

CrownsEconomy was rebuilt around one simple goal: give players a reason to trade with each other instead of just hoarding money.

### How players earn money

- Mining, combat, and fishing rewards still give baseline income.
- Jobs provide reliable delivery-style contracts for players who want guaranteed payout.
- Auction House lets players sell high-value or competitive items.
- Market Stalls let players run permanent fixed-price storefronts.
- Server Demand Board buys rotating materials and goods for cash.

### How players spend money

- Unlock a permanent Market Stall with a one-time down payment.
- Upgrade stall listing capacity and spotlight level.
- Buy curated utility and prestige stock from the Server Trader.
- Pay auction listing fees and taxes on market activity.

### Auction House

The Auction House is the bid-based market for competitive items.

- List an item from your hand
- Set a starting price and duration
- Let players bid against each other
- Receive payout automatically when the listing ends

Use it for rare gear, special drops, and anything where bidding creates excitement.

### Market Stalls

Market Stalls are the long-term player storefront system.

- Buy in once
- Keep your stall permanently
- Add fixed-price listings through the GUI
- Upgrade how many listings you can carry
- Upgrade spotlight level so your stall appears higher in browse order
- Brand your stall with a name, emblem, and short description

Stalls are meant to be the everyday market layer, while auctions handle premium bidding.

### Jobs

Jobs are delivery contracts generated by the server.

- Claim a contract
- Gather the required items
- Turn the items in
- Collect the payout

Jobs keep the economy approachable for players who do not want to live inside the market all day.

### Server Demand Board

The Demand Board is where The Server posts rotating buy orders.

- Orders request specific materials or goods
- Each one has a fixed payout
- Players fulfill them from their inventory

This is one of the main systems that keeps trade moving, because it creates visible short-term demand for useful items.

### Server Trader

The Server Trader is a curated money sink.

- Rotating utility items
- Rotating prestige or decorative stock
- Themed rotations like builder, traveler, utility, and prestige
- Controlled prices so money leaves circulation without feeling wasted

The trader is designed to make money worth spending, not just collecting.

### Player Commissions and Server Contracts

- Player commissions let players post buy orders for specific items from their own wallet.
- Server contracts are larger curated deliveries with stronger payouts and limited slots.
- Together they keep the market moving even when players are not actively auctioning items.

## Upgrade Notes

Existing player balances and player data are intended to carry forward through the shared CrownsAPI database path.

- CrownsEconomy balance data remains in the shared suite database
- Retired systems like chest shops are intentionally left out of the live feature surface
- The economy rebuild is focused on stronger trading loops, not wiping player progress
