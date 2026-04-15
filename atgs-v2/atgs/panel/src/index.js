const fs = require('fs');
const path = require('path');
const express = require('express');
const cookieParser = require('cookie-parser');
const multer = require('multer');
const config = require('./config');
const db = require('./db');
const agent = require('./agent');
const service = require('./server-service');
const idleManager = require('./idle-manager');
const {
  createSession,
  destroySession,
  getSession,
  getLockState,
  registerFailedLogin,
  clearLoginFailures,
  requireAuth,
  requireRole,
  buildUserResponse
} = require('./auth');

fs.mkdirSync(config.controlDir, { recursive: true });
fs.mkdirSync(config.importsDir, { recursive: true });
fs.mkdirSync(config.serverFilesDir, { recursive: true });
fs.mkdirSync(config.backupsDir, { recursive: true });

const upload = multer({ dest: config.importsDir, limits: { fileSize: 8 * 1024 * 1024 * 1024 } });

const app = express();
app.set('trust proxy', config.trustProxy);
app.use(express.json({ limit: '25mb' }));
app.use(cookieParser());

function normalizeIp(ip) {
  if (!ip) return 'unknown';
  return ip.startsWith('::ffff:') ? ip.slice(7) : ip;
}

function ipv4ToInt(ip) {
  const parts = String(ip).split('.').map((value) => parseInt(value, 10));
  if (parts.length !== 4 || parts.some((value) => !Number.isInteger(value) || value < 0 || value > 255)) {
    return null;
  }
  return (((parts[0] * 256 + parts[1]) * 256 + parts[2]) * 256 + parts[3]) >>> 0;
}

function ipMatchesRule(ip, rule) {
  if (!rule) return false;
  const normalizedIp = normalizeIp(ip);
  if (rule === normalizedIp) return true;
  if (!rule.includes('/')) return false;
  const [base, maskText] = rule.split('/');
  const mask = parseInt(maskText, 10);
  const ipInt = ipv4ToInt(normalizedIp);
  const baseInt = ipv4ToInt(base);
  if (ipInt === null || baseInt === null || !Number.isInteger(mask) || mask < 0 || mask > 32) {
    return false;
  }
  const maskBits = mask === 0 ? 0 : (0xffffffff << (32 - mask)) >>> 0;
  return (ipInt & maskBits) === (baseInt & maskBits);
}

function isAllowedAdminIp(ip) {
  if (!config.adminAllowedCidrs.length) return true;
  const normalizedIp = normalizeIp(ip);
  if (normalizedIp === '127.0.0.1' || normalizedIp === '::1') return true;
  return config.adminAllowedCidrs.some((rule) => ipMatchesRule(normalizedIp, rule));
}

app.use((req, res, next) => {
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'same-origin');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  res.setHeader(
    'Content-Security-Policy',
    "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; script-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
  );
  next();
});

function isTrustedProxyRequest(req) {
  return req.headers['x-atgs-proxy-secret'] === config.proxySharedSecret;
}

function requireTrustedEdge(req, res, next) {
  if (req.path.startsWith('/internal/gateway') || req.path === '/healthz') {
    return next();
  }

  if (config.panelDirectPublish || isTrustedProxyRequest(req)) {
    return next();
  }

  if (req.path.startsWith('/api/')) {
    return res.status(403).json({ error: 'ATGS admin access is only available through the HTTPS auth proxy' });
  }

  return res.status(403).send('ATGS admin access is only available through the HTTPS auth proxy.');
}

function requireAllowedAdminIp(req, res, next) {
  if (req.path.startsWith('/internal/gateway') || req.path === '/healthz') {
    return next();
  }
  if (isAllowedAdminIp(req.ip)) {
    return next();
  }
  return res.status(403).send('ATGS admin access from this IP is not allowed.');
}

function buildCookieOptions(req, includeMaxAge = true) {
  const secure = config.secureCookies && (req.secure || req.headers['x-forwarded-proto'] === 'https');
  const options = {
    httpOnly: true,
    sameSite: 'lax',
    secure
  };
  if (includeMaxAge) options.maxAge = config.sessionMaxAge;
  return options;
}

app.use(requireTrustedEdge);
app.use(requireAllowedAdminIp);
app.use(express.static(path.join(__dirname, '..', 'public'), { index: false }));

app.get('/healthz', (req, res) => {
  res.json({ ok: true });
});

app.post('/api/auth/login', (req, res) => {
  const lockState = getLockState(req.ip, req.body.username);
  if (lockState.locked) {
    return res.status(429).json({
      error: `Too many login attempts. Try again in ${Math.ceil(lockState.retryAfterMs / 60000)} minute(s).`
    });
  }
  const user = db.getUserByUsername(req.body.username);
  if (!user || !db.verifyPass(req.body.password, user.password)) {
    registerFailedLogin(req.ip, req.body.username);
    return res.status(401).json({ error: 'Invalid credentials' });
  }
  clearLoginFailures(req.ip, req.body.username);
  const token = createSession(user);
  res.cookie('session', token, buildCookieOptions(req));
  res.json({ ok: true, user: buildUserResponse(user.id) });
});

app.post('/api/auth/logout', (req, res) => {
  destroySession(req.cookies?.session);
  res.clearCookie('session', buildCookieOptions(req, false));
  res.json({ ok: true });
});

app.get('/api/auth/check', (req, res) => {
  const session = getSession(req.cookies?.session);
  if (!session) return res.json({ authenticated: false });
  res.json({ authenticated: true, user: session });
});

app.get('/api/auth/users', requireRole('admin'), (req, res) => {
  res.json(db.listUsers().map((user) => ({ id: user.id, username: user.username, role: user.role, createdAt: user.createdAt })));
});

app.get('/api/auth/owner', requireRole('admin'), (req, res) => {
  const owner = db.getUserById('owner');
  res.json(owner ? { id: owner.id, username: owner.username, role: owner.role, createdAt: owner.createdAt } : null);
});

app.post('/api/auth/owner/reset-secret', requireRole('admin'), (req, res) => {
  const owner = db.resetOwnerPasswordFromSecret();
  res.json({ ok: true, user: buildUserResponse(owner.id) });
});

app.post('/api/auth/users', requireRole('admin'), (req, res) => {
  if (!req.body.username || !req.body.password || !req.body.role) {
    return res.status(400).json({ error: 'username, password, role required' });
  }
  if (db.getUserByUsername(req.body.username)) {
    return res.status(400).json({ error: 'User already exists' });
  }
  if (!['admin', 'viewer'].includes(req.body.role)) {
    return res.status(400).json({ error: 'Role must be admin or viewer' });
  }
  const user = db.createUser({
    id: `user-${Date.now().toString(36)}`,
    username: req.body.username,
    password: db.hashPass(req.body.password),
    role: req.body.role
  });
  res.json({ ok: true, user: buildUserResponse(user.id) });
});

app.delete('/api/auth/users/:id', requireRole('admin'), (req, res) => {
  if (req.params.id === 'owner') return res.status(400).json({ error: 'Cannot delete owner' });
  db.deleteUser(req.params.id);
  res.json({ ok: true });
});

app.get('/api/server', requireAuth, async (req, res) => {
  try {
    res.json(await service.getServerSummary());
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get('/api/server/logs', requireAuth, async (req, res) => {
  try {
    res.json(await agent.runtimeLogs(parseInt(req.query.tail || '200', 10)));
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/server/bootstrap', requireRole('admin'), async (req, res) => {
  try {
    res.json({ ok: true, server: await service.bootstrapServer(req.body || {}) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.patch('/api/server/settings', requireRole('admin'), async (req, res) => {
  try {
    res.json({ ok: true, server: await service.updateServerSettings(req.body || {}) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/server/actions/:action', requireRole('admin'), async (req, res) => {
  try {
    const action = req.params.action;
    let server;
    if (action === 'wake' || action === 'start') server = await service.wakeServer(action);
    else if (action === 'sleep' || action === 'stop') server = await service.sleepServer(action);
    else if (action === 'restart') server = await service.restartServer();
    else return res.status(400).json({ error: 'Unsupported action' });
    res.json({ ok: true, server });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/server/command', requireRole('admin'), async (req, res) => {
  try {
    res.json({ ok: true, response: await service.sendConsoleCommand(req.body.command) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get('/api/backups', requireAuth, async (req, res) => {
  try {
    res.json(await agent.listBackups());
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/backups', requireRole('admin'), async (req, res) => {
  try {
    res.json({ ok: true, backup: await service.createBackup(req.body?.label) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/backups/restore', requireRole('admin'), async (req, res) => {
  try {
    res.json({ ok: true, server: await service.restoreBackup(req.body.name) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/backups/import', requireRole('admin'), upload.single('archive'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'Archive file required' });
  try {
    const name = `${path.basename(req.file.filename)}-${path.basename(req.file.originalname)}`;
    const target = path.join(config.importsDir, name);
    fs.renameSync(req.file.path, target);
    res.json({ ok: true, server: await service.importLegacyBackup(path.basename(target)) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get('/api/addons', requireAuth, (req, res) => {
  try {
    res.json(service.listAddons());
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/addons/:id/install', requireRole('admin'), async (req, res) => {
  try {
    res.json({ ok: true, installed: service.installAddon(req.params.id) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

function requireGateway(req, res, next) {
  if (req.headers['x-gateway-secret'] !== config.gatewaySecret) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
}

app.get('/internal/gateway/status', requireGateway, async (req, res) => {
  const summary = await service.getServerSummary();
  res.json({
    state: summary.state,
    ready: summary.ready,
    players: summary.players.players,
    maxPlayers: summary.maxPlayers
  });
});

app.get('/internal/gateway/ping', requireGateway, async (req, res) => {
  const summary = await service.getServerSummary();
  const statusText = summary.state === 'sleeping'
    ? 'Sleeping - join to wake'
    : summary.state === 'waking'
      ? 'Waking up'
      : summary.state === 'idle'
        ? 'Idle mode'
        : 'Online';
  res.json({
    motd: `${summary.motd} | ${statusText}`,
    players: summary.players.players,
    maxPlayers: summary.maxPlayers,
    state: summary.state,
    ready: summary.ready
  });
});

app.post('/internal/gateway/wake', requireGateway, async (req, res) => {
  const summary = await service.wakeServer('gateway');
  res.json({ ok: true, ready: summary.ready, state: summary.state });
});

app.get('/status', async (req, res) => {
  const summary = await service.getServerSummary().catch(() => db.getServer());
  const state = summary.state || 'unknown';
  res.send(`<!doctype html><html><head><meta charset="utf-8"><title>ATGS Status</title><style>body{font-family:sans-serif;background:#071019;color:#e8f0ff;padding:40px}.card{max-width:520px;margin:0 auto;background:#10192a;border:1px solid #1f3555;border-radius:18px;padding:24px}.state{font-size:32px;font-weight:700;margin:10px 0}</style></head><body><div class="card"><h1>${summary.name || 'ATGS Server'}</h1><div class="state">${state}</div><p>Players: ${summary.players?.players || 0}/${summary.maxPlayers || '-'}</p><p>MOTD: ${summary.motd || ''}</p></div></body></html>`);
});

app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'public', 'app.html'));
});

async function start() {
  await agent.health();
  idleManager.start();
  app.listen(config.port, '0.0.0.0', () => {
    console.log(`[ATGS] Panel listening on ${config.port}`);
  });
}

start().catch((error) => {
  console.error('[ATGS] Startup failed:', error.message);
  process.exit(1);
});
