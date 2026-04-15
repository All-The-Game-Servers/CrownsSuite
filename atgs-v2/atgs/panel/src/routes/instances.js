const { Router } = require('express');
const { v4: uuid } = require('uuid');
const fs = require('fs');
const path = require('path');
const store = require('../store');
const config = require('../config');
const docker = require('../docker');
const rcon = require('../rcon');
const eggs = require('../eggs');
const { requireAuth, requireRole, canAccess } = require('../middleware/auth');

let broadcastProgress = () => {};
function setBroadcast(fn) { broadcastProgress = fn; }

const router = Router();
router.use(requireAuth);

// List eggs
router.get('/eggs', (req, res) => res.json(eggs.listEggs()));

// Get full egg details including ports
router.get('/eggs/:eggId', (req, res) => {
  const egg = eggs.getEgg(req.params.eggId);
  if (!egg) return res.status(404).json({ error: 'Egg not found' });
  res.json({
    id: egg.id, name: egg.name, icon: egg.icon,
    ports: egg.ports,
    variants: (egg.variants || []).map(v => ({ id: v.id, name: v.name })),
  });
});

// Get versions for an egg variant
router.get('/eggs/:eggId/versions/:variantId', (req, res) => {
  try { res.json(eggs.getVersions(req.params.eggId, req.params.variantId)); }
  catch (e) { res.status(400).json({ error: e.message }); }
});

// List instances
router.get('/', (req, res) => {
  let all = store.instances.all();
  if (!req.user.instances.includes('*')) all = all.filter(i => req.user.instances.includes(i.id));
  res.json(all);
});

// Create instance
router.post('/', requireRole('admin'), async (req, res) => {
  const { name, eggId, variantId, version, port, minRam, maxRam, env, optionalPorts } = req.body;
  if (!name || !eggId || !variantId || !version) return res.status(400).json({ error: 'name, eggId, variantId, version required' });

  const egg = eggs.getEgg(eggId);
  if (!egg) return res.status(400).json({ error: 'Egg not found' });
  const variant = egg.variants?.find(v => v.id === variantId);
  if (!variant) return res.status(400).json({ error: 'Variant not found' });

  const id = uuid().slice(0, 8);
  const instDir = path.join(config.instancesDir, id);
  fs.mkdirSync(instDir, { recursive: true });

  // Build ports list from egg definition
  const ports = [];
  const basePort = port || egg.ports?.primary?.port || config.defaultPort;
  if (egg.ports?.primary) ports.push({ ...egg.ports.primary, port: basePort, hostPort: basePort });

  // Add optional ports (bedrock, voicechat, etc.)
  // If the frontend sends optionalPorts array, use that. Otherwise include ALL optional ports.
  const enabledOptional = optionalPorts || (egg.ports?.optional || []).map(p => p.id);
  for (const op of (egg.ports?.optional || [])) {
    if (enabledOptional.includes(op.id)) {
      ports.push({ ...op, hostPort: op.port });
    }
  }

  // RCON
  const rconPort = egg.rcon?.enabled ? basePort + (egg.rcon.portOffset || 10000) : null;
  const rconPassword = 'atgs-' + id;

  // Build env from egg defaults + user overrides
  const instanceEnv = {};
  for (const [k, v] of Object.entries(egg.environment || {})) instanceEnv[k] = v.default || '';
  for (const [k, v] of Object.entries(env || {})) instanceEnv[k] = v;

  const instance = {
    id, name, eggId, variantId, version,
    port: basePort, ports,
    minRam: minRam || config.defaultMinRam,
    maxRam: maxRam || config.defaultMaxRam,
    rconPort, rconPassword,
    addonDir: variant.addons?.directory || null,
    env: instanceEnv,
    status: 'installing',
    installProgress: [],
    createdAt: new Date().toISOString(),
  };

  store.instances.create(instance);
  res.json({ ok: true, instance });

  // Install async — runs the egg's install.sh script
  (async () => {
    const progress = (step, message) => {
      const entry = { step, message, time: new Date().toISOString() };
      instance.installProgress.push(entry);
      store.instances.update(id, { installProgress: instance.installProgress });
      broadcastProgress(id, entry);
    };

    try {
      progress('start', `Installing ${egg.name} (${variant.name}) ${version}...`);

      // Run the egg's install.sh script
      const result = await eggs.installEgg(eggId, variantId, instDir, version, progress);

      // Replace template variables in config file if egg wrote one
      if (egg.configTemplate?.filename) {
        const cfgPath = path.join(instDir, egg.configTemplate.filename);
        if (fs.existsSync(cfgPath)) {
          let content = fs.readFileSync(cfgPath, 'utf8');
          content = content
            .replace(/\{\{NAME\}\}/g, name)
            .replace(/\{\{PORT\}\}/g, String(basePort))
            .replace(/\{\{RCON_PORT\}\}/g, String(rconPort || ''))
            .replace(/\{\{RCON_PASSWORD\}\}/g, rconPassword);
          fs.writeFileSync(cfgPath, content);
        }
      }

      // Create Docker container
      progress('container', 'Creating container...');
      await docker.createInstance(instance);

      store.instances.update(id, { status: 'stopped', installResult: result });
      progress('complete', 'Installation complete! Ready to start.');
    } catch (e) {
      console.error(`[Install ${name}] FAILED:`, e.message);
      broadcastProgress(id, { step: 'error', message: `Failed: ${e.message}`, time: new Date().toISOString() });
      store.instances.update(id, { status: 'error', error: e.message });
    }
  })();
});

// Get single instance with live data
router.get('/:id', async (req, res) => {
  const inst = store.instances.find(req.params.id);
  if (!inst) return res.status(404).json({ error: 'Not found' });
  if (!canAccess(req, inst.id)) return res.status(403).json({ error: 'No access' });

  const container = await docker.getInstanceStatus(inst.id);
  const stats = container.running ? await docker.getInstanceStats(inst.id) : null;
  let playerInfo = { online: false, players: 0, maxPlayers: 0, playerNames: [] };
  let tps = 'N/A';
  if (container.running && container.health === 'healthy' && inst.rconPort) {
    const h = `atgs-${inst.id}`;
    playerInfo = await rcon.getPlayerInfo(h, inst.rconPort, inst.rconPassword);
    if (playerInfo.online) tps = await rcon.getTps(h, inst.rconPort, inst.rconPassword);
  }
  res.json({ ...inst, container, stats, ...playerInfo, tps });
});

// Controls
router.post('/:id/start', requireRole('moderator'), async (req, res) => {
  if (!canAccess(req, req.params.id)) return res.status(403).json({ error: 'No access' });
  try { await docker.startInstance(req.params.id); store.instances.update(req.params.id, { status: 'running' }); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
router.post('/:id/stop', requireRole('moderator'), async (req, res) => {
  if (!canAccess(req, req.params.id)) return res.status(403).json({ error: 'No access' });
  try { await docker.stopInstance(req.params.id); store.instances.update(req.params.id, { status: 'stopped' }); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
router.post('/:id/restart', requireRole('moderator'), async (req, res) => {
  if (!canAccess(req, req.params.id)) return res.status(403).json({ error: 'No access' });
  try { await docker.restartInstance(req.params.id); res.json({ ok: true }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});
router.post('/:id/command', requireRole('moderator'), async (req, res) => {
  const inst = store.instances.find(req.params.id);
  if (!inst?.rconPort) return res.status(400).json({ error: 'RCON not available' });
  try { res.json({ response: await rcon.sendCommand(`atgs-${inst.id}`, inst.rconPort, inst.rconPassword, req.body.command) }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// Moderation shortcuts
for (const action of ['kick', 'ban', 'pardon']) {
  router.post(`/:id/${action}`, requireRole('moderator'), async (req, res) => {
    const inst = store.instances.find(req.params.id);
    if (!inst?.rconPort) return res.status(400).json({ error: 'RCON not available' });
    const egg = eggs.getEgg(inst.eggId);
    const tmpl = egg?.rcon?.commands?.[action] || `${action} {{player}} {{reason}}`;
    const cmd = tmpl.replace('{{player}}', req.body.player || '').replace('{{reason}}', req.body.reason || '').trim();
    try { res.json({ response: await rcon.sendCommand(`atgs-${inst.id}`, inst.rconPort, inst.rconPassword, cmd) }); }
    catch (e) { res.status(500).json({ error: e.message }); }
  });
}
router.post('/:id/whitelist', requireRole('moderator'), async (req, res) => {
  const inst = store.instances.find(req.params.id);
  if (!inst?.rconPort) return res.status(400).json({ error: 'RCON not available' });
  const egg = eggs.getEgg(inst.eggId);
  const key = `whitelist_${req.body.action}`;
  const tmpl = egg?.rcon?.commands?.[key] || `whitelist ${req.body.action} {{player}}`;
  const cmd = tmpl.replace('{{player}}', req.body.player || '').trim();
  try { res.json({ response: await rcon.sendCommand(`atgs-${inst.id}`, inst.rconPort, inst.rconPassword, cmd) }); }
  catch (e) { res.status(500).json({ error: e.message }); }
});

// Update + Delete
router.put('/:id', requireRole('admin'), (req, res) => {
  const u = {};
  for (const k of ['minRam', 'maxRam', 'port', 'name']) if (req.body[k]) u[k] = req.body[k];
  if (req.body.ports) u.ports = req.body.ports;
  const r = store.instances.update(req.params.id, u);
  if (!r) return res.status(404).json({ error: 'Not found' }); res.json(r);
});

// Rebuild container — stops, removes, and recreates the Docker container
// with current settings. Instance files are preserved.
router.post('/:id/rebuild', requireRole('admin'), async (req, res) => {
  const inst = store.instances.find(req.params.id);
  if (!inst) return res.status(404).json({ error: 'Not found' });

  try {
    // Stop and remove old container
    await docker.removeInstance(inst.id);

    // If the user sent updated ports, apply them
    if (req.body.ports) {
      store.instances.update(inst.id, { ports: req.body.ports });
      inst.ports = req.body.ports;
    }

    // Recreate from current config
    await docker.createInstance(inst);
    store.instances.update(inst.id, { status: 'stopped' });
    res.json({ ok: true, message: 'Container rebuilt. Ready to start.' });
  } catch (e) {
    console.error('[Rebuild]', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── Clone Instance ───────────────────────────────────────────
router.post('/:id/clone', requireRole('admin'), async (req, res) => {
  const source = store.instances.find(req.params.id);
  if (!source) return res.status(404).json({ error: 'Not found' });

  const newId = require('uuid').v4().slice(0, 8);
  const newName = req.body.name || source.name + ' (Copy)';
  const newPort = req.body.port || source.port + 1;
  const includeWorld = req.body.includeWorld !== false;

  const srcDir = path.join(config.instancesDir, source.id);
  const destDir = path.join(config.instancesDir, newId);

  try {
    fs.mkdirSync(destDir, { recursive: true });

    // Copy files
    const excludes = includeWorld ? '' : '--exclude="world*"';
    const { execSync } = require('child_process');
    execSync(`tar -cf - ${excludes} --exclude="_backups" --exclude="logs" -C "${srcDir}" . | tar -xf - -C "${destDir}"`, { timeout: 300000 });

    // Create new instance record
    const newInst = {
      ...source,
      id: newId,
      name: newName,
      port: newPort,
      rconPort: newPort + (source.rconPort - source.port),
      rconPassword: 'atgs-' + newId,
      ports: source.ports.map(p => p.label === 'Game' ? { ...p, port: newPort, hostPort: newPort } : p),
      status: 'stopped',
      installProgress: [],
      createdAt: new Date().toISOString(),
    };
    delete newInst.container;
    delete newInst.stats;
    delete newInst.installResult;

    store.instances.create(newInst);

    // Update server.properties with new port/rcon
    const propsPath = path.join(destDir, 'server.properties');
    if (fs.existsSync(propsPath)) {
      let props = fs.readFileSync(propsPath, 'utf8');
      props = props.replace(/server-port=\d+/, `server-port=${newPort}`);
      props = props.replace(/rcon\.port=\d+/, `rcon.port=${newInst.rconPort}`);
      props = props.replace(/rcon\.password=.+/, `rcon.password=${newInst.rconPassword}`);
      fs.writeFileSync(propsPath, props);
    }

    // Create Docker container
    await docker.createInstance(newInst);

    res.json({ ok: true, instance: newInst });
  } catch (e) {
    // Cleanup on failure
    fs.rmSync(destDir, { recursive: true, force: true });
    store.instances.delete(newId);
    res.status(500).json({ error: e.message });
  }
});

// ── Export Instance ──────────────────────────────────────────
router.get('/:id/export', requireRole('admin'), (req, res) => {
  const inst = store.instances.find(req.params.id);
  if (!inst) return res.status(404).json({ error: 'Not found' });

  const srcDir = path.join(config.instancesDir, inst.id);
  const exportDir = path.join(config.dataDir, 'exports');
  fs.mkdirSync(exportDir, { recursive: true });

  const filename = `${inst.name.replace(/[^a-zA-Z0-9-_]/g, '_')}-${inst.id}.tar.gz`;
  const exportPath = path.join(exportDir, filename);

  try {
    const { execSync } = require('child_process');
    // Include instance metadata
    const metaPath = path.join(srcDir, '.atgs-export-meta.json');
    fs.writeFileSync(metaPath, JSON.stringify({
      name: inst.name, eggId: inst.eggId, variantId: inst.variantId, version: inst.version,
      port: inst.port, minRam: inst.minRam, maxRam: inst.maxRam,
      addonDir: inst.addonDir, env: inst.env, ports: inst.ports,
      exportedAt: new Date().toISOString(),
    }, null, 2));

    execSync(`tar -czf "${exportPath}" --exclude="_backups" --exclude="logs" -C "${srcDir}" .`, { timeout: 600000 });
    try { fs.unlinkSync(metaPath); } catch {}

    res.setHeader('Content-Type', 'application/gzip');
    res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);
    const stream = fs.createReadStream(exportPath);
    stream.pipe(res);
    stream.on('end', () => { try { fs.unlinkSync(exportPath); } catch {} });
  } catch (e) {
    try { fs.unlinkSync(exportPath); } catch {}
    res.status(500).json({ error: e.message });
  }
});

// ── Import Instance ──────────────────────────────────────────
const multer = require('multer');
const importUpload = multer({ dest: '/tmp/atgs-imports/', limits: { fileSize: 2 * 1024 * 1024 * 1024 } });

router.post('/import', requireRole('admin'), importUpload.single('file'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No file uploaded' });

  const newId = require('uuid').v4().slice(0, 8);
  const destDir = path.join(config.instancesDir, newId);

  try {
    fs.mkdirSync(destDir, { recursive: true });
    const { execSync } = require('child_process');
    execSync(`tar -xzf "${req.file.path}" -C "${destDir}"`, { timeout: 600000 });

    // Read export metadata
    const metaPath = path.join(destDir, '.atgs-export-meta.json');
    let meta = {};
    if (fs.existsSync(metaPath)) {
      meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
      fs.unlinkSync(metaPath);
    }

    const port = req.body.port ? parseInt(req.body.port) : (meta.port || config.defaultPort);
    const rconPort = port + 10000;
    const rconPassword = 'atgs-' + newId;

    const newInst = {
      id: newId,
      name: req.body.name || meta.name || 'Imported Instance',
      eggId: meta.eggId || 'minecraft-java',
      variantId: meta.variantId || 'paper',
      version: meta.version || 'unknown',
      port,
      ports: meta.ports || [{ port, hostPort: port, protocol: 'tcp', label: 'Game' }],
      minRam: meta.minRam || config.defaultMinRam,
      maxRam: meta.maxRam || config.defaultMaxRam,
      rconPort, rconPassword,
      addonDir: meta.addonDir || null,
      env: meta.env || {},
      status: 'stopped',
      importedAt: new Date().toISOString(),
      createdAt: new Date().toISOString(),
    };

    // Update server.properties with new port/rcon
    const propsPath = path.join(destDir, 'server.properties');
    if (fs.existsSync(propsPath)) {
      let props = fs.readFileSync(propsPath, 'utf8');
      props = props.replace(/server-port=\d+/, `server-port=${port}`);
      props = props.replace(/rcon\.port=\d+/, `rcon.port=${rconPort}`);
      props = props.replace(/rcon\.password=.+/, `rcon.password=${rconPassword}`);
      fs.writeFileSync(propsPath, props);
    }

    store.instances.create(newInst);
    await docker.createInstance(newInst);

    res.json({ ok: true, instance: newInst });
  } catch (e) {
    fs.rmSync(destDir, { recursive: true, force: true });
    store.instances.delete(newId);
    res.status(500).json({ error: e.message });
  } finally {
    try { fs.unlinkSync(req.file.path); } catch {}
  }
});

// ── Config Editor ────────────────────────────────────────────
// Returns structured config schema from the egg + current values
router.get('/:id/config-editor', requireAuth, (req, res) => {
  const inst = store.instances.find(req.params.id);
  if (!inst) return res.status(404).json({ error: 'Not found' });

  const egg = eggs.getEgg(inst.eggId);
  const configFile = egg?.configTemplate?.filename || 'server.properties';
  const filePath = path.join(config.instancesDir, inst.id, configFile);

  if (!fs.existsSync(filePath)) {
    return res.json({ filename: configFile, exists: false, fields: [], raw: '' });
  }

  const raw = fs.readFileSync(filePath, 'utf8');

  // Parse server.properties format (key=value)
  const parsed = {};
  for (const line of raw.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq === -1) continue;
    parsed[trimmed.slice(0, eq)] = trimmed.slice(eq + 1);
  }

  // Build structured fields with types
  const FIELD_SCHEMAS = {
    'server-port': { type: 'number', label: 'Server Port', group: 'Network' },
    'max-players': { type: 'number', label: 'Max Players', group: 'Server' },
    'difficulty': { type: 'select', label: 'Difficulty', options: ['peaceful', 'easy', 'normal', 'hard'], group: 'Gameplay' },
    'gamemode': { type: 'select', label: 'Game Mode', options: ['survival', 'creative', 'adventure', 'spectator'], group: 'Gameplay' },
    'online-mode': { type: 'boolean', label: 'Online Mode (Mojang Auth)', group: 'Network' },
    'pvp': { type: 'boolean', label: 'PVP', group: 'Gameplay' },
    'allow-flight': { type: 'boolean', label: 'Allow Flight', group: 'Gameplay' },
    'spawn-protection': { type: 'number', label: 'Spawn Protection Radius', group: 'Server' },
    'view-distance': { type: 'number', label: 'View Distance', group: 'Performance' },
    'simulation-distance': { type: 'number', label: 'Simulation Distance', group: 'Performance' },
    'motd': { type: 'string', label: 'Server Description (MOTD)', group: 'Server' },
    'level-name': { type: 'string', label: 'World Name', group: 'World' },
    'level-seed': { type: 'string', label: 'World Seed', group: 'World' },
    'level-type': { type: 'select', label: 'World Type', options: ['minecraft\\:normal', 'minecraft\\:flat', 'minecraft\\:large_biomes', 'minecraft\\:amplified'], group: 'World' },
    'generate-structures': { type: 'boolean', label: 'Generate Structures', group: 'World' },
    'allow-nether': { type: 'boolean', label: 'Allow Nether', group: 'World' },
    'enable-command-block': { type: 'boolean', label: 'Command Blocks', group: 'Server' },
    'white-list': { type: 'boolean', label: 'Whitelist', group: 'Network' },
    'server-name': { type: 'string', label: 'Server Name', group: 'Server' },
    'enable-rcon': { type: 'boolean', label: 'RCON Enabled', group: 'Network' },
    'rcon.port': { type: 'number', label: 'RCON Port', group: 'Network' },
    'rcon.password': { type: 'string', label: 'RCON Password', group: 'Network' },
  };

  const fields = Object.entries(parsed).map(([key, value]) => {
    const schema = FIELD_SCHEMAS[key] || { type: 'string', label: key, group: 'Other' };
    return { key, value, ...schema };
  });

  res.json({ filename: configFile, exists: true, fields, raw });
});

// Save config editor changes
router.post('/:id/config-editor', requireRole('moderator'), (req, res) => {
  const inst = store.instances.find(req.params.id);
  if (!inst) return res.status(404).json({ error: 'Not found' });

  const egg = eggs.getEgg(inst.eggId);
  const configFile = egg?.configTemplate?.filename || 'server.properties';
  const filePath = path.join(config.instancesDir, inst.id, configFile);

  if (!fs.existsSync(filePath)) return res.status(404).json({ error: 'Config file not found' });

  const changes = req.body.changes; // { key: value, key: value }
  if (!changes || typeof changes !== 'object') return res.status(400).json({ error: 'changes object required' });

  let content = fs.readFileSync(filePath, 'utf8');

  for (const [key, value] of Object.entries(changes)) {
    const regex = new RegExp(`^${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}=.*$`, 'm');
    if (regex.test(content)) {
      content = content.replace(regex, `${key}=${value}`);
    } else {
      content += `\n${key}=${value}`;
    }
  }

  fs.writeFileSync(filePath, content);
  res.json({ ok: true, message: 'Config updated. Restart to apply.' });
});

router.delete('/:id', requireRole('owner'), async (req, res) => {
  try { await docker.removeInstance(req.params.id); } catch {}
  fs.rmSync(path.join(config.instancesDir, req.params.id), { recursive: true, force: true });
  store.instances.delete(req.params.id); res.json({ ok: true });
});

module.exports = router;
module.exports.setBroadcast = setBroadcast;
