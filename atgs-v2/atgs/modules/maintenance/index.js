// ==============================================================
//  ATGS Module: Maintenance Windows
//  Schedules maintenance with countdown announcements via RCON,
//  optional backup, and automatic restart.
// ==============================================================
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

let ctx = null;
const activeTimers = new Map(); // maintenanceId -> { timers: [setTimeout ids] }
const STATE_FILE = () => path.join(ctx.config.dbDir, 'maintenance.json');

function readState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE(), 'utf8')); }
  catch { return []; }
}

function writeState(s) {
  fs.writeFileSync(STATE_FILE(), JSON.stringify(s, null, 2));
}

// Send an in-game announcement via RCON
async function announce(instanceId, message) {
  const inst = ctx.store.instances.find(instanceId);
  if (!inst?.rconPort || inst.status !== 'running') return;
  try {
    await ctx.rcon.sendCommand(`atgs-${inst.id}`, inst.rconPort, inst.rconPassword, `say ${message}`);
  } catch (e) {
    console.warn(`[Maintenance] Announce failed for ${instanceId}:`, e.message);
  }
}

// Execute the maintenance: backup → restart
async function executeMaintenance(maintenance) {
  const inst = ctx.store.instances.find(maintenance.instanceId);
  if (!inst) return;

  console.log(`[Maintenance] Executing for ${inst.name}`);
  const cfg = ctx.getConfig();

  // Final announcement
  await announce(maintenance.instanceId, 'Server restarting NOW for maintenance!');

  // Wait 3 seconds for players to read
  await new Promise(r => setTimeout(r, 3000));

  // Backup if configured
  if (cfg.backupBefore) {
    try {
      const { execSync } = require('child_process');
      const instDir = path.join(ctx.config.instancesDir, maintenance.instanceId);
      const backupDir = path.join(instDir, '_backups');
      fs.mkdirSync(backupDir, { recursive: true });
      const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      const bp = path.join(backupDir, `backup-${ts}-maintenance.tar.gz`);
      execSync(`tar -czf "${bp}" --exclude="_backups" --exclude="logs" -C "${instDir}" .`, { timeout: 300000 });
      console.log(`[Maintenance] Backup created: ${path.basename(bp)}`);
    } catch (e) {
      console.error('[Maintenance] Backup failed:', e.message);
    }
  }

  // Restart
  try {
    await ctx.docker.restartInstance(maintenance.instanceId);
    console.log(`[Maintenance] ${inst.name} restarted`);
  } catch (e) {
    console.error('[Maintenance] Restart failed:', e.message);
  }

  // Fire Discord hook
  try {
    const modules = require(path.join(__dirname, '..', '..', 'panel', 'src', 'modules'));
    modules.fireHook('onCrashDetected', maintenance.instanceId, 'Scheduled maintenance completed');
  } catch {}

  // Mark as completed if one-time
  if (!maintenance.recurring) {
    const state = readState();
    const idx = state.findIndex(m => m.id === maintenance.id);
    if (idx !== -1) {
      state[idx].status = 'completed';
      state[idx].lastRun = new Date().toISOString();
      writeState(state);
    }
  } else {
    // Update lastRun
    const state = readState();
    const idx = state.findIndex(m => m.id === maintenance.id);
    if (idx !== -1) {
      state[idx].lastRun = new Date().toISOString();
      writeState(state);
    }
  }
}

// Schedule a maintenance window
function scheduleMaintenance(maintenance) {
  cancelMaintenance(maintenance.id);

  const cfg = ctx.getConfig();
  const announceAt = (cfg.announceMinutes || '10,5,2,1').split(',').map(s => parseInt(s.trim())).filter(n => n > 0).sort((a, b) => b - a);
  const msgTemplate = cfg.announceMessage || 'Server restarting for maintenance in {minutes} minute(s)!';

  const now = Date.now();
  const execTime = new Date(maintenance.scheduledAt).getTime();
  const delay = execTime - now;

  if (delay <= 0 && !maintenance.recurring) return; // Already passed

  const timers = [];

  // Schedule announcements
  for (const mins of announceAt) {
    const announceDelay = delay - (mins * 60000);
    if (announceDelay > 0) {
      const t = setTimeout(() => {
        const msg = msgTemplate.replace('{minutes}', String(mins));
        announce(maintenance.instanceId, msg);
        console.log(`[Maintenance] Announced: ${mins}m warning for ${maintenance.instanceId}`);
      }, announceDelay);
      timers.push(t);
    }
  }

  // Schedule execution
  if (delay > 0) {
    const t = setTimeout(() => executeMaintenance(maintenance), delay);
    timers.push(t);
  }

  activeTimers.set(maintenance.id, { timers });
}

function cancelMaintenance(id) {
  const entry = activeTimers.get(id);
  if (entry) {
    for (const t of entry.timers) clearTimeout(t);
    activeTimers.delete(id);
  }
}

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    // List maintenance windows
    router.get('/', (req, res) => {
      const state = readState();
      const enriched = state.map(m => {
        const inst = ctx.store.instances.find(m.instanceId);
        return { ...m, instanceName: inst?.name || m.instanceId, active: activeTimers.has(m.id) };
      });
      res.json(enriched);
    });

    // Schedule new maintenance
    router.post('/', (req, res) => {
      const { instanceId, scheduledAt, reason } = req.body;
      if (!instanceId || !scheduledAt) return res.status(400).json({ error: 'instanceId and scheduledAt required' });

      const inst = ctx.store.instances.find(instanceId);
      if (!inst) return res.status(404).json({ error: 'Instance not found' });

      const scheduled = new Date(scheduledAt);
      if (scheduled.getTime() <= Date.now()) return res.status(400).json({ error: 'scheduledAt must be in the future' });

      const maintenance = {
        id: crypto.randomBytes(4).toString('hex'),
        instanceId,
        reason: reason || 'Scheduled maintenance',
        scheduledAt: scheduled.toISOString(),
        status: 'scheduled',
        recurring: false,
        createdAt: new Date().toISOString(),
      };

      const state = readState();
      state.push(maintenance);
      writeState(state);

      scheduleMaintenance(maintenance);
      res.json({ ok: true, maintenance });
    });

    // Quick maintenance — restart in N minutes with announcements
    router.post('/quick', (req, res) => {
      const { instanceId, minutes } = req.body;
      if (!instanceId || !minutes) return res.status(400).json({ error: 'instanceId and minutes required' });

      const inst = ctx.store.instances.find(instanceId);
      if (!inst) return res.status(404).json({ error: 'Instance not found' });

      const scheduled = new Date(Date.now() + minutes * 60000);
      const maintenance = {
        id: crypto.randomBytes(4).toString('hex'),
        instanceId,
        reason: `Quick maintenance (${minutes}m)`,
        scheduledAt: scheduled.toISOString(),
        status: 'scheduled',
        recurring: false,
        createdAt: new Date().toISOString(),
      };

      const state = readState();
      state.push(maintenance);
      writeState(state);

      scheduleMaintenance(maintenance);
      res.json({ ok: true, maintenance, executesAt: scheduled.toISOString() });
    });

    // Cancel maintenance
    router.delete('/:id', (req, res) => {
      cancelMaintenance(req.params.id);
      let state = readState();
      state = state.filter(m => m.id !== req.params.id);
      writeState(state);
      res.json({ ok: true });
    });

    context.app.use('/api/maintenance', router);

    // Schedule any pending maintenance on startup
    const state = readState();
    for (const m of state) {
      if (m.status === 'scheduled' && new Date(m.scheduledAt).getTime() > Date.now()) {
        scheduleMaintenance(m);
      }
    }
    console.log(`[Maintenance] ${state.filter(m => m.status === 'scheduled').length} window(s) scheduled`);
  },

  destroy() {
    for (const [id] of activeTimers) cancelMaintenance(id);
  },
};
