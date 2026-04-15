// ==============================================================
//  ATGS Module: Audit Log
//  Records every significant action to an append-only log.
//  Intercepts API responses to detect what happened.
// ==============================================================
const fs = require('fs');
const path = require('path');

let ctx = null;

function logFile() {
  return path.join(ctx.config.dbDir, 'audit.jsonl');
}

function writeEntry(entry) {
  const line = JSON.stringify({
    ...entry,
    time: new Date().toISOString(),
  }) + '\n';

  try {
    fs.appendFileSync(logFile(), line);
  } catch (e) {
    console.error('[Audit] Write failed:', e.message);
  }

  // Rotation check (every 100 writes)
  if (Math.random() < 0.01) rotateIfNeeded();
}

function rotateIfNeeded() {
  const cfg = ctx.getConfig();
  const max = cfg.maxEntries || 10000;

  try {
    const content = fs.readFileSync(logFile(), 'utf8');
    const lines = content.trim().split('\n');
    if (lines.length > max) {
      const trimmed = lines.slice(lines.length - max).join('\n') + '\n';
      fs.writeFileSync(logFile(), trimmed);
    }
  } catch {}
}

function readEntries(opts = {}) {
  try {
    const content = fs.readFileSync(logFile(), 'utf8');
    let entries = content.trim().split('\n')
      .filter(l => l)
      .map(l => { try { return JSON.parse(l); } catch { return null; } })
      .filter(Boolean);

    // Filters
    if (opts.action) entries = entries.filter(e => e.action === opts.action);
    if (opts.user) entries = entries.filter(e => e.user === opts.user);
    if (opts.instanceId) entries = entries.filter(e => e.instanceId === opts.instanceId);
    if (opts.search) {
      const q = opts.search.toLowerCase();
      entries = entries.filter(e => JSON.stringify(e).toLowerCase().includes(q));
    }

    // Sort newest first
    entries.reverse();

    // Pagination
    const offset = parseInt(opts.offset) || 0;
    const limit = Math.min(parseInt(opts.limit) || 50, 200);
    const total = entries.length;

    return { entries: entries.slice(offset, offset + limit), total, offset, limit };
  } catch {
    return { entries: [], total: 0, offset: 0, limit: 50 };
  }
}

// Map API paths to action names
const ACTION_MAP = [
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/start$/, action: 'instance.start', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/stop$/, action: 'instance.stop', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/restart$/, action: 'instance.restart', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances$/, action: 'instance.create' },
  { method: 'DELETE', pattern: /\/api\/instances\/([^/]+)$/, action: 'instance.delete', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/rebuild$/, action: 'instance.rebuild', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/clone$/, action: 'instance.clone', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/command$/, action: 'instance.command', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/kick$/, action: 'moderation.kick', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/ban$/, action: 'moderation.ban', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/pardon$/, action: 'moderation.pardon', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/auth\/login$/, action: 'auth.login' },
  { method: 'POST', pattern: /\/api\/auth\/logout$/, action: 'auth.logout' },
  { method: 'POST', pattern: /\/api\/auth\/users$/, action: 'user.create' },
  { method: 'DELETE', pattern: /\/api\/auth\/users\//, action: 'user.delete' },
  { method: 'POST', pattern: /\/api\/mods\/install$/, action: 'mod.install' },
  { method: 'POST', pattern: /\/api\/backups\/([^/]+)$/, action: 'backup.create', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/backups\/([^/]+)\/restore$/, action: 'backup.restore', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/files\/([^/]+)\/write$/, action: 'file.edit', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/files\/([^/]+)\/upload$/, action: 'file.upload', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/files\/([^/]+)\/delete$/, action: 'file.delete', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/modules\/([^/]+)\/(enable|disable)$/, action: 'module.toggle', extract: (m) => ({ moduleId: m[1], state: m[2] }) },
  { method: 'POST', pattern: /\/api\/instances\/([^/]+)\/config-editor$/, action: 'config.edit', extract: (m) => ({ instanceId: m[1] }) },
  { method: 'POST', pattern: /\/api\/jar-update\/([^/]+)\/update$/, action: 'jar.update', extract: (m) => ({ instanceId: m[1] }) },
];

module.exports = {
  init(context) {
    ctx = context;

    // Ensure log file exists
    try { if (!fs.existsSync(logFile())) fs.writeFileSync(logFile(), ''); } catch {}

    // Express middleware that logs after response completes
    context.app.use((req, res, next) => {
      const origEnd = res.end;
      res.end = function(...args) {
        origEnd.apply(res, args);

        // Only log successful mutations
        if (res.statusCode >= 400) return;

        for (const mapping of ACTION_MAP) {
          if (req.method !== mapping.method) continue;
          const match = req.originalUrl.match(mapping.pattern);
          if (!match) continue;

          const extra = mapping.extract ? mapping.extract(match) : {};
          const entry = {
            action: mapping.action,
            user: req.user?.username || (req.body?.username) || 'anonymous',
            ...extra,
          };

          // Add relevant body data
          if (req.body?.player) entry.player = req.body.player;
          if (req.body?.command) entry.detail = req.body.command;
          if (req.body?.name) entry.detail = req.body.name;
          if (req.body?.slug) entry.detail = req.body.slug;
          if (req.body?.path) entry.detail = req.body.path;

          writeEntry(entry);
          break;
        }
      };
      next();
    });

    // API routes
    const router = context.createRouter();

    router.get('/', (req, res) => {
      res.json(readEntries({
        action: req.query.action,
        user: req.query.user,
        instanceId: req.query.instanceId,
        search: req.query.q,
        offset: req.query.offset,
        limit: req.query.limit,
      }));
    });

    // Get distinct action types for filter dropdown
    router.get('/actions', (req, res) => {
      const actions = new Set();
      try {
        const content = fs.readFileSync(logFile(), 'utf8');
        for (const line of content.trim().split('\n')) {
          try { const e = JSON.parse(line); if (e.action) actions.add(e.action); } catch {}
        }
      } catch {}
      res.json(Array.from(actions).sort());
    });

    context.app.use('/api/audit', router);
    console.log('[Audit] Logging active');
  },
};
