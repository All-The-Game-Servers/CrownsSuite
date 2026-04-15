const crypto = require('crypto');
const sessions = new Map();
const ROLE_LEVEL = { owner: 4, admin: 3, moderator: 2, viewer: 1 };
function createSession(user) { const t = crypto.randomBytes(32).toString('hex'); sessions.set(t, { userId: user.id, username: user.username, role: user.role, instances: user.instances }); return t; }
function destroySession(t) { sessions.delete(t); }
function getSession(t) { return sessions.get(t) || null; }
function requireRole(min) { return (req, res, next) => { const s = getSession(req.cookies?.session); if (!s) return res.status(401).json({ error: 'Unauthorized' }); if ((ROLE_LEVEL[s.role]||0) < (ROLE_LEVEL[min]||0)) return res.status(403).json({ error: 'Forbidden' }); req.user = s; next(); }; }
function requireAuth(req, res, next) { const s = getSession(req.cookies?.session); if (!s) return res.status(401).json({ error: 'Unauthorized' }); req.user = s; next(); }
function canAccess(req, id) { if (!req.user) return false; if (req.user.instances.includes('*')) return true; return req.user.instances.includes(id); }
module.exports = { createSession, destroySession, getSession, requireRole, requireAuth, canAccess, ROLE_LEVEL };
