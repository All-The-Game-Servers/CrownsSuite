# ATGS Egg Specification v1.0

An Egg defines how to install and run an application inside an ATGS instance container.

## Directory Structure

```
eggs/
└── <egg-id>/
    ├── config.json     # REQUIRED — Metadata, variants, ports, env
    ├── install.sh      # REQUIRED — Downloads and sets up files
    ├── run.sh          # REQUIRED — Template for instance startup
    └── README.md       # OPTIONAL — Human-readable docs
```

## config.json

```json
{
  "id": "my-app",                          // Unique slug, matches directory name
  "name": "My Application",               // Display name
  "category": "Game Servers",             // Grouping label
  "description": "One-line description",
  "icon": "🎮",                           // Emoji for sidebar
  "image": "atgs-runner:latest",          // Docker image (usually the default runner)

  "variants": [
    {
      "id": "default",                    // Variant slug
      "name": "Default",                  // Display name
      "versionSource": {                  // How to fetch available versions
        "url": "https://api.example.com/versions",
        "type": "json",                   // json | static
        "parser": "custom"                // Parser ID (see eggs.js)
      },
      "addons": {                         // Optional: where user-installed addons go
        "directory": "mods",
        "sources": ["modrinth:fabric"]
      }
    }
  ],

  "ports": {
    "primary": { "port": 25565, "protocol": "tcp", "label": "Game" },
    "optional": [
      { "id": "query", "port": 25566, "protocol": "udp", "label": "Query" }
    ]
  },

  "environment": {
    "EULA": { "default": "true", "description": "Accept EULA", "required": true },
    "CUSTOM_VAR": { "default": "", "description": "Optional setting" }
  },

  "rcon": {                                // null if app doesn't support RCON
    "enabled": true,
    "portOffset": 10000,                   // rconPort = instancePort + offset
    "commands": {
      "list": "list",
      "kick": "kick {{player}} {{reason}}",
      "ban": "ban {{player}} {{reason}}",
      "pardon": "pardon {{player}}",
      "whitelist_add": "whitelist add {{player}}",
      "whitelist_remove": "whitelist remove {{player}}",
      "whitelist_list": "whitelist list",
      "stop": "stop"
    }
  },

  "configTemplate": {                      // Default config file written on install
    "filename": "server.properties",
    "content": "server-port={{PORT}}\n..."  // {{VAR}} gets replaced
  }
}
```

## install.sh

Called by the panel during instance creation. Receives arguments and outputs progress.

### Interface

```bash
# Arguments:
#   $1 — Variant ID (e.g., "paper", "fabric")
#   $2 — Version (e.g., "1.21.11")
#   $3 — Instance directory (absolute path, e.g., /data/instances/a1b2c3d4)

# Environment:
#   All vars from config.json "environment" section are set.

# Output protocol:
#   Lines starting with [PROGRESS] are parsed by the panel.
#   Format: [PROGRESS] <step_id> <message>
#   Special steps: "done" (success) and "error" (failure)

# Exit code:
#   0 = success
#   non-zero = failure
```

### Example

```bash
#!/usr/bin/env bash
set -e
VARIANT="$1"
VERSION="$2"
INST_DIR="$3"
cd "$INST_DIR"

echo "[PROGRESS] download Downloading server files..."
curl -fSL -o server.jar "https://example.com/server-${VERSION}.jar"

echo "[PROGRESS] setup Creating directories..."
mkdir -p logs config

echo "[PROGRESS] done Installation complete!"
```

## run.sh

A template that gets **copied** into each instance as `start.sh`. It uses environment variables provided by the runner container at runtime.

### Available Environment Variables

| Variable | Source | Example |
|----------|--------|---------|
| `MIN_RAM` | Instance config | `2G` |
| `MAX_RAM` | Instance config | `4G` |
| `EULA` | Egg environment | `true` |
| Any custom env | config.json `environment` | — |

### Example

```bash
#!/usr/bin/env bash
cd /instance
exec java -Xms${MIN_RAM:-1G} -Xmx${MAX_RAM:-2G} -jar server.jar --nogui
```

## Isolation Guarantees

- Each instance gets its own container and filesystem.
- Eggs share nothing at runtime — the egg directory is read-only.
- install.sh writes into the instance directory, never the egg directory.
- run.sh is copied (not symlinked) so the egg can be updated without breaking running instances.

## Adding a New Egg

1. Create `eggs/<your-id>/` directory
2. Write `config.json`, `install.sh`, `run.sh`
3. Restart the panel (or it picks up new eggs on next API call)
4. The egg appears in the "New Instance" wizard automatically

No core code changes needed.
