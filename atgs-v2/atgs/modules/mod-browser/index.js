const path = require('path');
const fs = require('fs');

const KNOWN_CONFLICTS = [
  { a: 'optifine', b: 'sodium', reason: 'OptiFine and Sodium both replace the renderer' },
  { a: 'optifine', b: 'lithium', reason: 'OptiFine conflicts with Fabric optimization mods' },
  { a: 'optifine', b: 'iris-shaders', reason: 'Use Iris instead of OptiFine on Fabric' },
  { a: 'fabric-api', b: 'quilted-fabric-api', reason: 'Use one API, not both' },
];

function checkConflicts(slug, installedFiles) {
  const warnings = [];
  for (const c of KNOWN_CONFLICTS) {
    let other = null;
    if (c.a === slug.toLowerCase()) other = c.b;
    if (c.b === slug.toLowerCase()) other = c.a;
    if (!other) continue;
    if (installedFiles.some(f => f.toLowerCase().includes(other.replace(/-/g, '')))) {
      warnings.push({ conflictsWith: other, reason: c.reason });
    }
  }
  return warnings;
}

module.exports = {
  init(context) {
    const router = context.createRouter();

    router.get('/search', (req, res) => {
      try {
        res.json(context.modrinth.search(req.query.q || '', { loader: req.query.loader, gameVersion: req.query.version }));
      } catch (e) { res.status(500).json({ error: e.message }); }
    });

    router.get('/project/:slug', (req, res) => {
      try { res.json(context.modrinth.getProject(req.params.slug)); }
      catch (e) { res.status(500).json({ error: e.message }); }
    });

    router.get('/deps/:slug', (req, res) => {
      try {
        const inst = context.store.instances.find(req.query.instanceId);
        if (!inst) return res.status(404).json({ error: 'Instance not found' });
        const versions = context.modrinth.getVersions(req.params.slug, {
          loader: req.query.loader || inst.variantId || 'fabric',
          gameVersion: req.query.version || inst.version,
        });
        if (!versions?.length) return res.json({ dependencies: [] });
        const deps = (versions[0].dependencies || [])
          .filter(d => d.dependency_type === 'required' && d.project_id)
          .map(d => { try { const p = context.modrinth.getProject(d.project_id); return { slug: p?.slug, name: p?.title, required: true }; } catch { return { slug: null, name: 'Unknown', required: true }; } });
        res.json({ dependencies: deps });
      } catch (e) { res.status(500).json({ error: e.message }); }
    });

    router.post('/install', (req, res) => {
      const inst = context.store.instances.find(req.body.instanceId);
      if (!inst) return res.status(404).json({ error: 'Instance not found' });
      const instDir = path.join(context.config.instancesDir, inst.id);
      const subDir = inst.addonDir || 'mods';
      const loader = req.body.loader || inst.variantId || 'fabric';
      const gameVersion = req.body.gameVersion || inst.version;

      const installed = context.modrinth.listInstalled(instDir, subDir).map(f => f.filename);
      const conflicts = checkConflicts(req.body.slug, installed);
      if (conflicts.length && !req.body.forceInstall) {
        return res.json({ ok: false, conflicts, message: 'Conflicts detected. Send forceInstall:true to override.' });
      }

      try {
        const withDeps = req.body.withDeps !== false;
        const results = withDeps
          ? context.modrinth.installModWithDeps(req.body.slug, instDir, { loader, gameVersion, subDir })
          : [context.modrinth.installMod(req.body.slug, instDir, { loader, gameVersion, subDir })];
        res.json({ ok: true, installed: results.filter(r => !r.error && !r.skipped), skipped: results.filter(r => r.skipped), errors: results.filter(r => r.error) });
      } catch (e) { res.status(500).json({ error: e.message }); }
    });

    router.get('/installed/:instanceId', (req, res) => {
      const inst = context.store.instances.find(req.params.instanceId);
      if (!inst) return res.status(404).json({ error: 'Instance not found' });
      res.json(context.modrinth.listInstalled(path.join(context.config.instancesDir, inst.id), inst.addonDir || 'mods'));
    });

    router.delete('/installed/:instanceId/:filename', (req, res) => {
      const inst = context.store.instances.find(req.params.instanceId);
      if (!inst) return res.status(404).json({ error: 'Instance not found' });
      if (req.params.filename.includes('..') || !req.params.filename.endsWith('.jar')) return res.status(400).json({ error: 'Invalid' });
      try { fs.unlinkSync(path.join(context.config.instancesDir, inst.id, inst.addonDir || 'mods', req.params.filename)); res.json({ ok: true }); }
      catch (e) { res.status(400).json({ error: e.message }); }
    });

    context.app.use('/api/mods', router);
  },
};
