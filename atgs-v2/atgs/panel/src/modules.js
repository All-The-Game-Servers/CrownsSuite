const fs = require('fs');
const path = require('path');
const config = require('./config');

const loaded = new Map();

function discoverModules() {
  const dir = config.modulesDir;
  if (!fs.existsSync(dir)) return [];
  const mods = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!e.isDirectory()) continue;
    const d = path.join(dir, e.name);
    const mp = path.join(d, 'module.json'), ip = path.join(d, 'index.js');
    if (!fs.existsSync(mp) || !fs.existsSync(ip)) continue;
    try { const m = JSON.parse(fs.readFileSync(mp, 'utf8')); m._dir = d; m._indexPath = ip; mods.push(m); }
    catch (err) { console.error(`[Modules] Bad ${e.name}:`, err.message); }
  }
  return mods;
}

function loadModule(meta, context) {
  if (loaded.has(meta.id)) return;
  try {
    delete require.cache[require.resolve(meta._indexPath)];
    const mod = require(meta._indexPath);
    mod._meta = meta;
    if (typeof mod.init === 'function') {
      mod.init({ ...context, modulesDir: meta._dir, getConfig: () => meta.config || {}, setConfig: () => {} });
    }
    loaded.set(meta.id, mod);
    console.log(`[Modules] ✓ ${meta.name}`);
  } catch (e) { console.error(`[Modules] ✗ ${meta.name}:`, e.message); }
}

// Load ALL modules — no toggle, no state file
function initAll(ctx) {
  const disc = discoverModules();
  console.log(`[Modules] Loading ${disc.length} module(s)...`);
  for (const m of disc) loadModule(m, ctx);
}

function fireHook(hook, ...args) {
  for (const [id, mod] of loaded) {
    if (typeof mod[hook] === 'function') try { mod[hook](...args); } catch (e) { console.error(`[Modules] ${hook} failed in ${id}:`, e.message); }
  }
}

module.exports = { initAll, fireHook, getLoaded: () => loaded };
