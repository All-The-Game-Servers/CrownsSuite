# Resource Pack

Current pack: `CrownsSuite-ResourcePack-1.7.1.zip`

SHA1: `d08f8fd54c47f83e9f33c80d981d24968e01a62e`

The pack keeps stable `lowlight/...` model paths so plugin items can evolve without constantly renaming model IDs.

## Delivery

CrownsAPI owns resource-pack delivery. The intended production flow is:

1. Publish the pack zip and `.sha1` file to GitHub Releases.
2. Configure CrownsAPI with the release URL and SHA1.
3. Use CrownsAPI pack commands to refresh/apply the pack.
4. Keep only the current pack zip in `downloads` to avoid accidental installs.

## Current Coverage

- Suite/API icons.
- Magic spellbook, focus, starter spell icons, and v0.2 unlockable spell icons.
- Swords skillbook, training blade, starter sword-art icons, and v0.2 unlockable sword-art icons.
- Compatibility assets for older `lowlight/...` paths while the relaunch continues.
