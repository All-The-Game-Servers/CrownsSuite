const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

let ctx = null;
const timers = new Map();

function backupDir(id) {
  const d = path.join(ctx.config.instancesDir, id, '_backups');
  fs.mkdirSync(d, { recursive: true });
  return d;
}

function createBackup(id, label) {
  const instDir = path.join(ctx.config.instancesDir, id);
  if (!fs.existsSync(instDir)) throw new Error('Instance not found');
  const dir = backupDir(id);
  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const name = `backup-${ts}${label ? '-' + label : ''}.tar.gz`;
  const fp = path.join(dir, name);
  const cfg = ctx.getConfig();
  const excludes = (cfg.excludePatterns || 'logs,crash-reports,_backups').split(',').map(p => `--exclude="${p.trim()}"`).join(' ');
  execSync(`tar -czf "${fp}" ${excludes} --exclude="_backups" -C "${instDir}" .`, { timeout: 300000 });
  // Rotation
  const max = cfg.maxBackups || 24;
  const all = fs.readdirSync(dir).filter(f => f.endsWith('.tar.gz')).sort().reverse();
  for (const old of all.slice(max)) fs.unlinkSync(path.join(dir, old));
  const st = fs.statSync(fp);
  return { name, size: st.size, created: st.mtime.toISOString() };
}

function listBackups(id) {
  const dir = backupDir(id);
  return fs.readdirSync(dir).filter(f => f.endsWith('.tar.gz')).map(name => {
    const st = fs.statSync(path.join(dir, name));
    return { name, size: st.size, created: st.mtime.toISOString() };
  }).sort((a, b) => new Date(b.created) - new Date(a.created));
}

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    router.get('/:id', (req, res) => {
      try { res.json(listBackups(req.params.id)); } catch (e) { res.status(400).json({ error: e.message }); }
    });

    router.post('/:id', (req, res) => {
      try { res.json({ ok: true, backup: createBackup(req.params.id, req.body.label || 'manual') }); }
      catch (e) { res.status(500).json({ error: e.message }); }
    });

    router.post('/:id/restore', (req, res) => {
      try {
        const instDir = path.join(ctx.config.instancesDir, req.params.id);
        const bp = path.join(backupDir(req.params.id), req.body.name);
        if (!req.body.name?.endsWith('.tar.gz') || req.body.name.includes('..')) return res.status(400).json({ error: 'Invalid' });
        if (!fs.existsSync(bp)) return res.status(404).json({ error: 'Not found' });
        execSync(`tar -xzf "${bp}" -C "${instDir}"`, { timeout: 300000 });
        res.json({ ok: true, restored: req.body.name });
      } catch (e) { res.status(500).json({ error: e.message }); }
    });

    router.delete('/:id/:name', (req, res) => {
      try {
        if (!req.params.name?.endsWith('.tar.gz') || req.params.name.includes('..')) return res.status(400).json({ error: 'Invalid' });
        fs.unlinkSync(path.join(backupDir(req.params.id), req.params.name));
        res.json({ ok: true });
      } catch (e) { res.status(400).json({ error: e.message }); }
    });

    context.app.use('/api/backups', router);
  },

  onInstanceStart(id) {
    const cfg = ctx.getConfig();
    const mins = cfg.intervalMinutes || 60;
    if (mins > 0) {
      if (timers.has(id)) clearInterval(timers.get(id));
      timers.set(id, setInterval(() => {
        try { createBackup(id, 'auto'); } catch (e) { console.error('[Backup] Auto failed:', e.message); }
      }, mins * 60000));
    }
  },

  onInstanceStop(id) {
    if (timers.has(id)) { clearInterval(timers.get(id)); timers.delete(id); }
  },

  destroy() { for (const [id, t] of timers) { clearInterval(t); } timers.clear(); },
};
