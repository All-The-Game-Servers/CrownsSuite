# Resource Pack

Current pack: `CrownsSuite-ResourcePack-1.7.0.zip`

SHA1: `7829996977bbecc38204d3abe7e104af508e5e39`

The pack keeps stable `lowlight/...` model paths so plugin items can evolve without constantly renaming model IDs.

## Delivery

CrownsAPI owns resource-pack delivery. The intended production flow is:

1. Publish the pack zip and `.sha1` file to GitHub Releases.
2. Configure CrownsAPI with the release URL and SHA1.
3. Use CrownsAPI pack commands to refresh/apply the pack.
4. Keep only the current pack zip in `downloads` to avoid accidental installs.

## Current Coverage

- Suite/API icons.
- Magic spellbook, focus, and starter spell icons.
- Swords skillbook, training blade, and starter sword-art icons.
- Compatibility assets for older `lowlight/...` paths while the relaunch continues.
