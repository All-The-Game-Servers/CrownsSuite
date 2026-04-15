const crypto = require('crypto');
const db = require('./db');
const config = require('./config');

const sessions = new Map();
const ROLE_LEVEL = { owner: 4, admin: 3, moderator: 2, viewer: 1 };
const loginFailuresByIp = new Map();
const loginFailuresByUser = new Map();

function now() {
  return Date.now();
}

function pruneFailures(store, windowMs) {
  const cutoff = now() - windowMs;
  for (const [key, entry] of store.entries()) {
    entry.attempts = entry.attempts.filter((value) => value >= cutoff);
    if (!entry.attempts.length && (!entry.lockedUntil || entry.lockedUntil <= now())) {
      store.delete(key);
    }
  }
}

function normalizeIp(ip) {
  if (!ip) return 'unknown';
  if (ip.startsWith('::ffff:')) return ip.slice(7);
  return ip;
}

function getEntry(store, key) {
  const normalizedKey = key || 'unknown';
  if (!store.has(normalizedKey)) {
    store.set(normalizedKey, { attempts: [], lockedUntil: 0 });
  }
  return store.get(normalizedKey);
}

function registerFailure(store, key, maxAttempts) {
  const entry = getEntry(store, key);
  const current = now();
  entry.attempts.push(current);
  entry.attempts = entry.attempts.filter((value) => value >= current - config.loginWindowMs);
  if (entry.attempts.length >= maxAttempts) {
    entry.lockedUntil = current + config.loginBlockMs;
    entry.attempts = [];
  }
  return entry;
}

function clearFailures(store, key) {
  if (key) store.delete(key);
}

function getLockState(ip, username) {
  pruneFailures(loginFailuresByIp, config.loginWindowMs);
  pruneFailures(loginFailuresByUser, config.loginWindowMs);
  const current = now();
  const ipEntry = loginFailuresByIp.get(normalizeIp(ip));
  const userEntry = loginFailuresByUser.get(String(username || '').toLowerCase());
  const lockedUntil = Math.max(ipEntry?.lockedUntil || 0, userEntry?.lockedUntil || 0);
  if (lockedUntil > current) {
    return {
      locked: true,
      retryAfterMs: lockedUntil - current
    };
  }
  return { locked: false, retryAfterMs: 0 };
}

function createSession(user) {
  const token = crypto.randomBytes(32).toString('hex');
  sessions.set(token, {
    id: user.id,
    username: user.username,
    role: user.role,
    expiresAt: now() + config.sessionMaxAge
  });
  return token;
}

function getSession(token) {
  if (!token) return null;
  const session = sessions.get(token) || null;
  if (!session) return null;
  if (session.expiresAt <= now()) {
    sessions.delete(token);
    return null;
  }
  session.expiresAt = now() + config.sessionMaxAge;
  return session;
}

function destroySession(token) {
  sessions.delete(token);
}

function requireAuth(req, res, next) {
  const session = getSession(req.cookies?.session);
  if (!session) return res.status(401).json({ error: 'Unauthorized' });
  req.user = session;
  next();
}

function requireRole(role) {
  return (req, res, next) => {
    const session = getSession(req.cookies?.session);
    if (!session) return res.status(401).json({ error: 'Unauthorized' });
    if ((ROLE_LEVEL[session.role] || 0) < (ROLE_LEVEL[role] || 0)) {
      return res.status(403).json({ error: 'Forbidden' });
    }
    req.user = session;
    next();
  };
}

function buildUserResponse(id) {
  const user = db.getUserById(id);
  return user ? { id: user.id, username: user.username, role: user.role } : null;
}

function registerFailedLogin(ip, username) {
  registerFailure(loginFailuresByIp, normalizeIp(ip), config.loginMaxAttemptsIp);
  if (username) registerFailure(loginFailuresByUser, String(username).toLowerCase(), config.loginMaxAttemptsUser);
}

function clearLoginFailures(ip, username) {
  clearFailures(loginFailuresByIp, normalizeIp(ip));
  if (username) clearFailures(loginFailuresByUser, String(username).toLowerCase());
}

module.exports = {
  createSession,
  getSession,
  destroySession,
  getLockState,
  registerFailedLogin,
  clearLoginFailures,
  requireAuth,
  requireRole,
  buildUserResponse
};
