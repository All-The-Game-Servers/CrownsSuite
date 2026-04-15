const { Router } = require('express');
const { v4: uuid } = require('uuid');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const store = require('../store');
const config = require('../config');
const { requireRole } = require('../middleware/auth');
const router = Router();
router.use(requireRole('admin'));

router.get('/', (req, res) => res.json(store.templates.all()));
router.post('/', (req, res) => {
  const inst = store.instances.find(req.body.instanceId);
  if (!inst) return res.status(404).json({ error: 'Instance not found' });
  const id = uuid().slice(0, 8);
  const tDir = path.join(config.dataDir, 'templates', id);
  try {
    fs.mkdirSync(tDir, { recursive: true });
    execSync(`tar -czf "${tDir}/snapshot.tar.gz" --exclude="world*" --exclude="logs" -C "${config.instancesDir}/${inst.id}" .`, { timeout: 120000 });
    const t = { id, name: req.body.name || inst.name + ' Template', description: req.body.description || '', eggId: inst.eggId, variantId: inst.variantId, version: inst.version, createdAt: new Date().toISOString() };
    store.templates.create(t); res.json({ ok: true, template: t });
  } catch (e) { res.status(500).json({ error: e.message }); }
});
router.delete('/:id', (req, res) => { fs.rmSync(path.join(config.dataDir, 'templates', req.params.id), { recursive: true, force: true }); store.templates.delete(req.params.id); res.json({ ok: true }); });

module.exports = router;
