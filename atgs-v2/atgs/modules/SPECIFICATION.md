# ATGS Module Specification v1.0

A Module is an optional feature that extends the ATGS core without bloating it.

## Directory Structure

```
modules/
└── <module-name>/
    ├── module.json         # REQUIRED — Metadata and configuration schema
    ├── index.js            # REQUIRED — Module logic, exports init()
    ├── sidecar.yml         # OPTIONAL — Docker Compose for sidecar containers
    └── README.md           # OPTIONAL — Documentation
```

## module.json

```json
{
  "id": "my-module",
  "name": "My Module",
  "description": "What this module does",
  "version": "1.0.0",
  "scope": "instance",        // "instance" | "global" | "both"
  "sidecar": false,            // true if module needs its own container
  "defaultEnabled": false,     // auto-enable for new instances?
  "config": {                  // Configuration schema
    "interval": { "type": "number", "default": 60, "label": "Interval (minutes)" }
  }
}
```

## index.js Interface

```javascript
module.exports = {
  // Called once when the module is loaded
  init(context) {
    // context.app       — Express app (register routes)
    // context.store     — JSON store instance
    // context.docker    — Docker API
    // context.instances — Instance store
    // context.broadcast — WebSocket broadcast function
    // context.modulesDir— This module's directory
  },

  // Called when module is enabled for an instance
  onEnable(instanceId, config) {},

  // Called when module is disabled for an instance
  onDisable(instanceId) {},

  // Called when an instance starts
  onInstanceStart(instanceId) {},

  // Called when an instance stops
  onInstanceStop(instanceId) {},

  // Cleanup on shutdown
  destroy() {}
};
```

All methods except `init` are optional.
