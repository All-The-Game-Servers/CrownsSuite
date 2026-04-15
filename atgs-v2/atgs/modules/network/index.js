// ==============================================================
//  ATGS Module: Network Manager
//  Manages Velocity proxy ↔ backend server relationships.
//  Auto-generates velocity.toml with backend entries.
// ==============================================================
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

let ctx = null;
const STATE_FILE = () => path.join(ctx.config.dbDir, 'networks.json');

function readNetworks() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE(), 'utf8')); }
  catch { return []; }
}

function writeNetworks(nets) {
  fs.writeFileSync(STATE_FILE(), JSON.stringify(nets, null, 2));
}

// Build the [servers] section of velocity.toml from backend list
function buildServersBlock(backends) {
  const lines = ['[servers]'];
  const tryList = [];

  for (const b of backends) {
    // Inside Docker network, backends are reachable by container name
    const addr = `atgs-${b.instanceId}:${b.port || 25565}`;
    const name = b.alias || b.name.toLowerCase().replace(/[^a-z0-9]/g, '_');
    lines.push(`${name} = "${addr}"`);
    tryList.push(`"${name}"`);
  }

  lines.push(`try = [${tryList.join(', ')}]`);
  return lines.join('\n');
}

// Update the velocity.toml of a proxy instance
function syncProxyConfig(network) {
  const instDir = path.join(ctx.config.instancesDir, network.proxyId);
  const tomlPath = path.join(instDir, 'velocity.toml');
  if (!fs.existsSync(tomlPath)) return;

  let content = fs.readFileSync(tomlPath, 'utf8');

  // Replace [servers] block
  const serversBlock = buildServersBlock(network.backends);
  const serversRegex = /\[servers\][\s\S]*?(?=\n\[|$)/;
  if (serversRegex.test(content)) {
    content = content.replace(serversRegex, serversBlock + '\n');
  } else {
    content += '\n' + serversBlock + '\n';
  }

  // Ensure forwarding secret file exists
  const secretPath = path.join(instDir, 'forwarding.secret');
  if (!fs.existsSync(secretPath)) {
    fs.writeFileSync(secretPath, network.forwardingSecret || crypto.randomBytes(16).toString('hex'));
  }

  fs.writeFileSync(tomlPath, content);
}

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    // List all networks
    router.get('/', (req, res) => {
      const networks = readNetworks();
      // Enrich with instance names
      const enriched = networks.map(n => {
        const proxy = ctx.store.instances.find(n.proxyId);
        return {
          ...n,
          proxyName: proxy?.name || n.proxyId,
          proxyStatus: proxy?.status || 'unknown',
          backends: n.backends.map(b => {
            const inst = ctx.store.instances.find(b.instanceId);
            return { ...b, name: inst?.name || b.name, status: inst?.status || 'unknown' };
          }),
        };
      });
      res.json(enriched);
    });

    // Create a network (link a proxy to backends)
    router.post('/', (req, res) => {
      const { proxyId, name } = req.body;
      if (!proxyId) return res.status(400).json({ error: 'proxyId required' });

      const proxy = ctx.store.instances.find(proxyId);
      if (!proxy) return res.status(404).json({ error: 'Proxy instance not found' });

      const networks = readNetworks();
      if (networks.find(n => n.proxyId === proxyId)) {
        return res.status(400).json({ error: 'Network already exists for this proxy' });
      }

      const network = {
        id: crypto.randomBytes(4).toString('hex'),
        name: name || proxy.name + ' Network',
        proxyId,
        backends: [],
        forwardingSecret: crypto.randomBytes(16).toString('hex'),
        createdAt: new Date().toISOString(),
      };

      networks.push(network);
      writeNetworks(networks);

      // Write forwarding secret
      const secretPath = path.join(ctx.config.instancesDir, proxyId, 'forwarding.secret');
      fs.writeFileSync(secretPath, network.forwardingSecret);

      syncProxyConfig(network);
      res.json({ ok: true, network });
    });

    // Add a backend server to a network
    router.post('/:networkId/backend', (req, res) => {
      const { instanceId, alias } = req.body;
      if (!instanceId) return res.status(400).json({ error: 'instanceId required' });

      const inst = ctx.store.instances.find(instanceId);
      if (!inst) return res.status(404).json({ error: 'Instance not found' });

      const networks = readNetworks();
      const network = networks.find(n => n.id === req.params.networkId);
      if (!network) return res.status(404).json({ error: 'Network not found' });

      if (network.backends.find(b => b.instanceId === instanceId)) {
        return res.status(400).json({ error: 'Instance already in network' });
      }

      network.backends.push({
        instanceId,
        name: inst.name,
        alias: alias || inst.name.toLowerCase().replace(/[^a-z0-9]/g, '_'),
        port: inst.port || 25565,
        addedAt: new Date().toISOString(),
      });

      writeNetworks(networks);
      syncProxyConfig(network);
      res.json({ ok: true, backends: network.backends });
    });

    // Remove a backend from a network
    router.delete('/:networkId/backend/:instanceId', (req, res) => {
      const networks = readNetworks();
      const network = networks.find(n => n.id === req.params.networkId);
      if (!network) return res.status(404).json({ error: 'Network not found' });

      network.backends = network.backends.filter(b => b.instanceId !== req.params.instanceId);
      writeNetworks(networks);
      syncProxyConfig(network);
      res.json({ ok: true });
    });

    // Delete a network
    router.delete('/:networkId', (req, res) => {
      let networks = readNetworks();
      networks = networks.filter(n => n.id !== req.params.networkId);
      writeNetworks(networks);
      res.json({ ok: true });
    });

    // Get the forwarding secret (needed for backend server configs)
    router.get('/:networkId/secret', (req, res) => {
      const networks = readNetworks();
      const network = networks.find(n => n.id === req.params.networkId);
      if (!network) return res.status(404).json({ error: 'Network not found' });
      res.json({ secret: network.forwardingSecret });
    });

    // Get all non-proxy instances (candidates for backends)
    router.get('/available-backends', (req, res) => {
      const instances = ctx.store.instances.all();
      const backends = instances.filter(i => i.eggId !== 'velocity');
      res.json(backends.map(i => ({ id: i.id, name: i.name, port: i.port, status: i.status })));
    });

    context.app.use('/api/networks', router);
  },
};
