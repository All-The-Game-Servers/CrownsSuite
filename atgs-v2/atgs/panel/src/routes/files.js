const { Router } = require('express');
const fs = require('fs');
const path = require('path');
const multer = require('multer');
const config = require('../config');
const { requireAuth } = require('../middleware/auth');
const router = Router();
router.use(requireAuth);
const upload = multer({ dest: '/tmp/uploads/' });

function safe(instId, rel) {
  const base = path.join(config.instancesDir, instId);
  const r = path.resolve(base, rel || '');
  if (!r.startsWith(base)) throw new Error('Access denied');
  return r;
}

router.get('/:id', (req, res) => {
  try { const dir = safe(req.params.id, req.query.path || '');
    if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return res.status(400).json({ error: 'Not a directory' });
    const base = path.join(config.instancesDir, req.params.id);
    const entries = fs.readdirSync(dir, { withFileTypes: true }).map(e => {
      let size = null, modified = null; try { const s = fs.statSync(path.join(dir, e.name)); size = e.isFile() ? s.size : null; modified = s.mtime.toISOString(); } catch {}
      return { name: e.name, type: e.isDirectory() ? 'dir' : 'file', size, modified };
    }).sort((a, b) => { if (a.type !== b.type) return a.type === 'dir' ? -1 : 1; return a.name.localeCompare(b.name); });
    res.json({ path: path.relative(base, dir) || '/', entries });
  } catch (e) { res.status(400).json({ error: e.message }); }
});

router.get('/:id/read', (req, res) => {
  try { const fp = safe(req.params.id, req.query.path || ''); const stat = fs.statSync(fp);
    if (stat.isDirectory()) return res.status(400).json({ error: 'Is a directory' });
    if (stat.size > 2 * 1024 * 1024) return res.json({ binary: true, size: stat.size });
    const textExts = ['.yml','.yaml','.properties','.json','.txt','.log','.cfg','.conf','.toml','.md','.sh','.bat','.csv','.xml','.html','.css','.js','.java','.sk',''];
    if (!textExts.includes(path.extname(fp).toLowerCase())) return res.json({ binary: true, size: stat.size });
    res.json({ content: fs.readFileSync(fp, 'utf8'), size: stat.size });
  } catch (e) { res.status(400).json({ error: e.message }); }
});

router.post('/:id/write', (req, res) => { try { fs.writeFileSync(safe(req.params.id, req.body.path), req.body.content, 'utf8'); res.json({ ok: true }); } catch (e) { res.status(400).json({ error: e.message }); } });
router.post('/:id/delete', (req, res) => { try { const fp = safe(req.params.id, req.body.path); if (fs.statSync(fp).isDirectory()) fs.rmSync(fp, { recursive: true }); else fs.unlinkSync(fp); res.json({ ok: true }); } catch (e) { res.status(400).json({ error: e.message }); } });
router.post('/:id/mkdir', (req, res) => { try { fs.mkdirSync(safe(req.params.id, req.body.path), { recursive: true }); res.json({ ok: true }); } catch (e) { res.status(400).json({ error: e.message }); } });
router.post('/:id/rename', (req, res) => { try { fs.renameSync(safe(req.params.id, req.body.from), safe(req.params.id, req.body.to)); res.json({ ok: true }); } catch (e) { res.status(400).json({ error: e.message }); } });
router.post('/:id/upload', upload.array('files', 20), (req, res) => {
  try { const dest = safe(req.params.id, req.body.path || '');
    for (const f of req.files) { const d = path.join(dest, f.originalname); fs.copyFileSync(f.path, d); fs.unlinkSync(f.path); }
    res.json({ ok: true, count: req.files.length });
  } catch (e) { for (const f of (req.files || [])) try { fs.unlinkSync(f.path); } catch {} res.status(400).json({ error: e.message }); }
});

module.exports = router;
