const { Router } = require('express');
const { v4: uuid } = require('uuid');
const store = require('../store');
const { createSession, destroySession, getSession, requireAuth, requireRole } = require('../middleware/auth');
const config = require('../config');
const router = Router();

router.post('/login', (req, res) => {
  const user = store.users.findBy('username', req.body.username);
  if (!user || !store.verifyPass(req.body.password, user.password)) return res.status(401).json({ error: 'Invalid credentials' });
  const token = createSession(user);
  res.cookie('session', token, { httpOnly: true, maxAge: config.sessionMaxAge });
  res.json({ ok: true, user: { id: user.id, username: user.username, role: user.role, instances: user.instances } });
});
router.post('/logout', (req, res) => { destroySession(req.cookies?.session); res.clearCookie('session').json({ ok: true }); });
router.get('/check', (req, res) => { const s = getSession(req.cookies?.session); res.json(s ? { authenticated: true, user: s } : { authenticated: false }); });

router.get('/users', requireRole('admin'), (req, res) => {
  res.json(store.users.all().map(u => ({ id: u.id, username: u.username, role: u.role, instances: u.instances, createdAt: u.createdAt })));
});
router.post('/users', requireRole('admin'), (req, res) => {
  const { username, password, role, instances } = req.body;
  if (!username || !password) return res.status(400).json({ error: 'Username and password required' });
  if (store.users.findBy('username', username)) return res.status(400).json({ error: 'Username exists' });
  const allowed = req.user.role === 'owner' ? ['admin', 'moderator', 'viewer'] : ['moderator', 'viewer'];
  if (!allowed.includes(role)) return res.status(400).json({ error: 'Cannot assign role' });
  const user = { id: uuid().slice(0, 8), username, password: store.hashPass(password), role: role || 'viewer', instances: instances || [], createdAt: new Date().toISOString() };
  store.users.create(user);
  res.json({ ok: true, user: { id: user.id, username: user.username, role: user.role } });
});
router.put('/users/:id', requireRole('admin'), (req, res) => {
  const u = {}; if (req.body.role) u.role = req.body.role; if (req.body.instances) u.instances = req.body.instances; if (req.body.password) u.password = store.hashPass(req.body.password);
  const r = store.users.update(req.params.id, u); if (!r) return res.status(404).json({ error: 'Not found' }); res.json({ ok: true });
});
router.delete('/users/:id', requireRole('owner'), (req, res) => {
  if (req.params.id === 'owner') return res.status(400).json({ error: 'Cannot delete owner' });
  store.users.delete(req.params.id); res.json({ ok: true });
});
module.exports = router;
