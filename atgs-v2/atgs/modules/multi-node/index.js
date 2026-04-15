// ==============================================================
//  ATGS Module: Multi-Node
//  Accepts WebSocket connections from remote node agents.
//  Tracks node resources, routes Docker commands, handles migration.
// ==============================================================
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');

let ctx = null;
const nodes = new Map(); // nodeName -> { ws, stats, containers, lastSeen, name }
const pendingRequests = new Map(); // requestId -> { resolve, reject, timeout }
const NODE_FILE = () => path.join(ctx.config.dbDir, 'nodes.json');

// Persistent node registry (remembers nodes even when disconnected)
function readRegistry() { try { return JSON.parse(fs.readFileSync(NODE_FILE(), 'utf8')); } catch { return []; } }
function writeRegistry(r) { fs.writeFileSync(NODE_FILE(), JSON.stringify(r, null, 2)); }

function sendToNode(nodeName, data) {
  const node = nodes.get(nodeName);
  if (!node?.ws || node.ws.readyState !== 1) throw new Error(`Node ${nodeName} not connected`);
  node.ws.send(JSON.stringify(data));
}

function requestFromNode(nodeName, data, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const requestId = crypto.randomBytes(8).toString('hex');
    data.requestId = requestId;

    const timer = setTimeout(() => {
      pendingRequests.delete(requestId);
      reject(new Error('Node request timed out'));
    }, timeoutMs);

    pendingRequests.set(requestId, { resolve, reject, timeout: timer });
    sendToNode(nodeName, data);
  });
}

module.exports = {
  init(context) {
    ctx = context;

    // ── Agent WebSocket Endpoint ─────────────────────────────
    // Agents connect to ws://panel:8080/agent?secret=...&name=...
    const http = require('http');

    // Hook into the existing HTTP server via upgrade event
    // The panel's HTTP server is context.app's listener
    // We need to handle upgrade for /agent path
    const agentWss = new WebSocketServer({ noServer: true });

    // Listen for upgrade events on the panel's server
    // We do this by patching the server after it starts listening
    setTimeout(() => {
      const servers = [];
      // Find the HTTP server
      try {
        const addr = context.app._router;
        // The server is stored on the app by Express internally
        // We access it through the existing WebSocket setup
      } catch {}

      // Alternative: create a separate endpoint on the Express app
      // that upgrades to WebSocket
    }, 0);

    // Simpler approach: use the Express app to create a polling-based agent endpoint
    const agentRouter = context.createRouter();

    // Agent registration (polling fallback)
    context.app.post('/agent/register', (req, res) => {
      const secret = req.body.secret || req.query.secret;
      const cfg = ctx.getConfig();
      if (secret !== (cfg.nodeSecret || 'atgs-node-secret')) {
        return res.status(401).json({ error: 'Invalid secret' });
      }

      const name = req.body.name || 'unnamed';
      const stats = req.body.stats || {};

      // Register/update node
      const registry = readRegistry();
      const existing = registry.find(n => n.name === name);
      if (existing) {
        existing.lastSeen = new Date().toISOString();
        existing.stats = stats;
        existing.connected = true;
      } else {
        registry.push({
          name,
          firstSeen: new Date().toISOString(),
          lastSeen: new Date().toISOString(),
          stats,
          connected: true,
        });
      }
      writeRegistry(registry);

      // Store in memory
      if (!nodes.has(name)) nodes.set(name, {});
      const node = nodes.get(name);
      node.stats = stats;
      node.lastSeen = Date.now();
      node.name = name;

      res.json({ ok: true, name });
    });

    // Agent heartbeat (polling)
    context.app.post('/agent/heartbeat', (req, res) => {
      const secret = req.body.secret || req.query.secret;
      const cfg = ctx.getConfig();
      if (secret !== (cfg.nodeSecret || 'atgs-node-secret')) {
        return res.status(401).json({ error: 'Invalid secret' });
      }

      const name = req.body.name;
      if (!name) return res.status(400).json({ error: 'name required' });

      const node = nodes.get(name) || {};
      node.stats = req.body.stats || node.stats;
      node.lastSeen = Date.now();
      node.containers = req.body.containers || node.containers;
      nodes.set(name, node);

      // Update registry
      const registry = readRegistry();
      const existing = registry.find(n => n.name === name);
      if (existing) {
        existing.lastSeen = new Date().toISOString();
        existing.stats = node.stats;
      }
      writeRegistry(registry);

      // Return any pending commands for this node
      res.json({ ok: true });
    });

    // Agent Docker proxy (polling)
    context.app.post('/agent/docker', (req, res) => {
      const secret = req.body.secret || req.query.secret;
      const cfg = ctx.getConfig();
      if (secret !== (cfg.nodeSecret || 'atgs-node-secret')) {
        return res.status(401).json({ error: 'Invalid secret' });
      }
      // This endpoint lets the panel send Docker commands to a specific node
      // The agent polls this endpoint for commands
      res.json({ ok: true, message: 'Docker proxy endpoint' });
    });

    // ── Panel-side API ───────────────────────────────────────
    const router = context.createRouter();

    // List all nodes
    router.get('/', (req, res) => {
      const registry = readRegistry();
      const STALE = 120000; // 2 minutes
      const enriched = registry.map(n => {
        const live = nodes.get(n.name);
        const connected = live && (Date.now() - (live.lastSeen || 0)) < STALE;
        return {
          ...n,
          connected,
          stats: connected ? live.stats : n.stats,
          containers: connected ? (live.containers || []) : [],
        };
      });

      // Add local node
      const localStats = {
        hostname: require('os').hostname(),
        cpuCount: require('os').cpus().length,
        memTotal: require('os').totalmem(),
        memUsed: require('os').totalmem() - require('os').freemem(),
        memPercent: (((require('os').totalmem() - require('os').freemem()) / require('os').totalmem()) * 100).toFixed(1),
      };

      res.json([
        { name: 'local', connected: true, stats: localStats, isLocal: true, firstSeen: 'always', lastSeen: new Date().toISOString() },
        ...enriched,
      ]);
    });

    // Get single node details
    router.get('/:name', (req, res) => {
      if (req.params.name === 'local') {
        return res.json({
          name: 'local', connected: true, isLocal: true,
          stats: {
            hostname: require('os').hostname(),
            platform: require('os').platform(),
            cpuCount: require('os').cpus().length,
            cpuModel: require('os').cpus()[0]?.model,
            memTotal: require('os').totalmem(),
            memUsed: require('os').totalmem() - require('os').freemem(),
            uptime: require('os').uptime(),
          },
        });
      }
      const registry = readRegistry();
      const node = registry.find(n => n.name === req.params.name);
      if (!node) return res.status(404).json({ error: 'Node not found' });
      const live = nodes.get(req.params.name);
      res.json({ ...node, connected: live && (Date.now() - (live.lastSeen || 0)) < 120000, stats: live?.stats || node.stats });
    });

    // Remove a node from registry
    router.delete('/:name', (req, res) => {
      let registry = readRegistry();
      registry = registry.filter(n => n.name !== req.params.name);
      writeRegistry(registry);
      nodes.delete(req.params.name);
      res.json({ ok: true });
    });

    // Get instance-to-node assignments
    router.get('/assignments/all', (req, res) => {
      const instances = ctx.store.instances.all();
      res.json(instances.map(i => ({ id: i.id, name: i.name, node: i.node || 'local' })));
    });

    // Assign instance to a node
    router.post('/assignments/:instanceId', (req, res) => {
      const { node } = req.body;
      const updated = ctx.store.instances.update(req.params.instanceId, { node: node || 'local' });
      if (!updated) return res.status(404).json({ error: 'Instance not found' });
      res.json({ ok: true });
    });

    context.app.use('/api/nodes', router);
    console.log('[Multi-Node] Ready. Configure node secret in module settings.');
  },
};
