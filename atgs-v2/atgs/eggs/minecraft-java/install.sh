#!/usr/bin/env bash
# ==============================================================
#  Minecraft Java — Install Script
#  Called by the ATGS panel during instance creation.
#
#  Arguments:
#    $1 — Variant: vanilla | paper | fabric | forge
#    $2 — Minecraft version: e.g., 1.21.11
#    $3 — Instance directory: absolute path
#
#  Outputs [PROGRESS] lines for the panel to parse.
#  Exit 0 on success, non-zero on failure.
# ==============================================================
set -euo pipefail

VARIANT="${1:?Usage: install.sh <variant> <version> <instance_dir>}"
VERSION="${2:?Missing version}"
INST_DIR="${3:?Missing instance directory}"

cd "$INST_DIR"

progress() { echo "[PROGRESS] $1 $2"; }
fail()     { echo "[PROGRESS] error $1"; exit 1; }

if [ "$VERSION" = "latest" ]; then
    VERSION="$(curl -sf "https://launchermeta.mojang.com/mc/game/version_manifest.json" | jq -r '.latest.release')"
    [ -z "$VERSION" ] && fail "Could not resolve latest Minecraft release"
    progress "resolve" "Resolved latest Minecraft release to ${VERSION}"
fi

# ── Shared setup ─────────────────────────────────────────────
progress "init" "Installing Minecraft Java (${VARIANT}) ${VERSION}..."
mkdir -p logs

# ── Vanilla ──────────────────────────────────────────────────
install_vanilla() {
    progress "manifest" "Fetching version manifest..."
    local manifest
    manifest=$(curl -sf "https://launchermeta.mojang.com/mc/game/version_manifest.json") \
        || fail "Could not fetch version manifest"

    local version_url
    version_url=$(echo "$manifest" | jq -r --arg v "$VERSION" '.versions[] | select(.id==$v) | .url')
    [ -z "$version_url" ] && fail "Version ${VERSION} not found in Mojang manifest"

    progress "resolve" "Resolving download URL..."
    local server_url
    server_url=$(curl -sf "$version_url" | jq -r '.downloads.server.url')
    [ -z "$server_url" ] && fail "No server download for ${VERSION}"

    progress "download" "Downloading server.jar..."
    curl -fSL -o server.jar "$server_url" || fail "Download failed"

    local size
    size=$(du -h server.jar | cut -f1)
    progress "verify" "Downloaded server.jar (${size})"
}

# ── Paper ────────────────────────────────────────────────────
install_paper_like() {
    local project="$1"
    progress "builds" "Fetching ${project^} builds for ${VERSION}..."
    local builds_json
    if [ "$project" = "paper" ]; then
        builds_json=$(curl -sf "https://api.papermc.io/v2/projects/paper/versions/${VERSION}/builds") \
            || fail "Could not fetch Paper builds for ${VERSION}"
    else
        builds_json=$(curl -sf "https://api.purpurmc.org/v2/purpur/${VERSION}") \
            || fail "Could not fetch Purpur builds for ${VERSION}"
    fi

    local build
    local url
    if [ "$project" = "paper" ]; then
        build=$(echo "$builds_json" | jq -r '.builds[-1].build')
        [ -z "$build" ] || [ "$build" = "null" ] && fail "No Paper builds found for ${VERSION}"
        local jar_name
        jar_name=$(echo "$builds_json" | jq -r '.builds[-1].downloads.application.name')
        url="https://api.papermc.io/v2/projects/paper/versions/${VERSION}/builds/${build}/downloads/${jar_name}"
    else
        build=$(echo "$builds_json" | jq -r '.builds.latest')
        [ -z "$build" ] || [ "$build" = "null" ] && fail "No Purpur builds found for ${VERSION}"
        url="https://api.purpurmc.org/v2/purpur/${VERSION}/${build}/download"
    fi

    progress "download" "Downloading ${project^} build ${build}..."
    curl -fSL -o server.jar "$url" || fail "Download failed"

    progress "plugins" "Creating plugins directory..."
    mkdir -p plugins

    local size
    size=$(du -h server.jar | cut -f1)
    progress "verify" "Downloaded ${project^} ${VERSION} build ${build} (${size})"
}

# ── Fabric ───────────────────────────────────────────────────
install_fabric() {
    progress "installer" "Downloading Fabric installer..."
    curl -fSL -o fabric-installer.jar \
        "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.1/fabric-installer-1.0.1.jar" \
        || fail "Could not download Fabric installer"

    progress "install" "Running Fabric installer for ${VERSION}..."
    progress "install" "(This downloads Minecraft + Fabric loader — may take a few minutes)"
    java -jar fabric-installer.jar server -mcversion "$VERSION" -downloadMinecraft \
        || fail "Fabric installer failed"

    progress "cleanup" "Cleaning up installer..."
    rm -f fabric-installer.jar

    # Verify the launch jar was created
    if [ ! -f fabric-server-launch.jar ]; then
        fail "fabric-server-launch.jar not found after installation"
    fi

    progress "mods" "Creating mods directory..."
    mkdir -p mods

    progress "verify" "Fabric ${VERSION} installed (fabric-server-launch.jar)"
}

# ── Forge ────────────────────────────────────────────────────
install_forge() {
    progress "lookup" "Looking up Forge version for ${VERSION}..."
    local promos
    promos=$(curl -sf "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json") \
        || fail "Could not fetch Forge version list"

    local forge_ver
    forge_ver=$(echo "$promos" | jq -r --arg v "$VERSION" '.promos[$v + "-recommended"] // .promos[$v + "-latest"]')
    [ -z "$forge_ver" ] || [ "$forge_ver" = "null" ] && fail "No Forge build for Minecraft ${VERSION}"

    local full_version="${VERSION}-${forge_ver}"
    progress "download" "Downloading Forge ${full_version} installer..."
    curl -fSL -o forge-installer.jar \
        "https://maven.minecraftforge.net/net/minecraftforge/forge/${full_version}/forge-${full_version}-installer.jar" \
        || fail "Could not download Forge installer"

    progress "install" "Running Forge installer..."
    progress "install" "(This extracts libraries and patches — may take several minutes)"
    java -jar forge-installer.jar --installServer \
        || fail "Forge installer failed"

    progress "cleanup" "Cleaning up..."
    rm -f forge-installer.jar forge-installer.jar.log

    progress "mods" "Creating mods directory..."
    mkdir -p mods

    progress "verify" "Forge ${full_version} installed"
}

# ── Route to variant ─────────────────────────────────────────
case "$VARIANT" in
    purpur)  install_paper_like purpur ;;
    vanilla) install_vanilla ;;
    paper)   install_paper_like paper ;;
    fabric)  install_fabric  ;;
    forge)   install_forge   ;;
    *)       fail "Unknown variant: ${VARIANT}" ;;
esac

# ── Report contents ──────────────────────────────────────────
progress "summary" "Instance directory contents:"
ls -lh "$INST_DIR"/*.jar 2>/dev/null | while read -r line; do
    progress "summary" "  $line"
done
for d in mods plugins config; do
    [ -d "$INST_DIR/$d" ] && progress "summary" "  ${d}/ directory created"
done

progress "done" "Minecraft Java (${VARIANT}) ${VERSION} — installation complete!"
