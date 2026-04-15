// ==============================================================
//  ATGS — Egg System (Directory-Based)
//
//  Each egg is a directory under config.eggsDir containing:
//    config.json  — metadata, variants, ports, env
//    install.sh   — called during instance creation
//    run.sh       — copied into instance as start.sh
//
//  The core system is game-agnostic. All application-specific
//  logic lives in the egg's scripts.
// ==============================================================
const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');
const config = require('./config');

// ── Load eggs from filesystem ────────────────────────────────
let cache = null;

function loadEggs() {
  const dir = config.eggsDir;
  if (!fs.existsSync(dir)) return {};

  const eggs = {};
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;

    const eggDir = path.join(dir, entry.name);
    const configPath = path.join(eggDir, 'config.json');
    const installPath = path.join(eggDir, 'install.sh');
    const runPath = path.join(eggDir, 'run.sh');

    if (!fs.existsSync(configPath)) {
      console.warn(`[Eggs] Skipping ${entry.name}: no config.json`);
      continue;
    }

    try {
      const eggConfig = JSON.parse(fs.readFileSync(configPath, 'utf8'));
      eggConfig._dir = eggDir;
      eggConfig._hasInstall = fs.existsSync(installPath);
      eggConfig._hasRun = fs.existsSync(runPath);

      if (!eggConfig._hasInstall) console.warn(`[Eggs] ${entry.name}: missing install.sh`);
      if (!eggConfig._hasRun) console.warn(`[Eggs] ${entry.name}: missing run.sh`);

      eggs[eggConfig.id] = eggConfig;
      console.log(`[Eggs] Loaded: ${eggConfig.name} (${eggConfig.variants?.length || 0} variants)`);
    } catch (e) {
      console.error(`[Eggs] Failed to load ${entry.name}:`, e.message);
    }
  }
  return eggs;
}

function getEggs() {
  if (!cache) cache = loadEggs();
  return cache;
}

function reloadEggs() {
  cache = null;
  return getEggs();
}

function getEgg(id) {
  return getEggs()[id] || null;
}

function listEggs() {
  return Object.values(getEggs()).map(e => ({
    id: e.id,
    name: e.name,
    category: e.category,
    description: e.description,
    icon: e.icon,
    variants: (e.variants || []).map(v => ({ id: v.id, name: v.name })),
  }));
}

// ── Fetch available versions for a variant ───────────────────
function getVersions(eggId, variantId) {
  const egg = getEgg(eggId);
  if (!egg) throw new Error(`Egg not found: ${eggId}`);

  const variant = egg.variants?.find(v => v.id === variantId);
  if (!variant) throw new Error(`Variant not found: ${variantId}`);

  const src = variant.versionSource;
  if (!src) return [{ id: 'latest' }];

  // Static version list
  if (src.type === 'static') {
    return (src.versions || ['latest']).map(v => ({ id: v }));
  }

  // Fetch from remote API
  try {
    const data = JSON.parse(
      execSync(`curl -sf "${src.url}"`, { timeout: 15000 }).toString()
    );

    switch (src.parser) {
      case 'mojang':
        return data.versions
          .filter(v => v.type === 'release')
          .slice(0, 25)
          .map(v => ({ id: v.id }));

      case 'paper':
        return (data.versions || [])
          .reverse()
          .slice(0, 20)
          .map(v => ({ id: v }));

      case 'fabric':
        return data
          .filter(v => v.stable)
          .slice(0, 20)
          .map(v => ({ id: v.version }));

      case 'forge': {
        const vers = new Set();
        for (const k of Object.keys(data.promos || {})) {
          vers.add(k.replace(/-.*/, ''));
        }
        return Array.from(vers)
          .reverse()
          .slice(0, 15)
          .map(v => ({ id: v }));
      }

      default:
        console.warn(`[Eggs] Unknown version parser: ${src.parser}`);
        return [{ id: 'latest' }];
    }
  } catch (e) {
    console.error(`[Eggs] Version fetch failed for ${eggId}/${variantId}:`, e.message);
    return [{ id: 'latest' }];
  }
}

// ── Run install.sh for an egg ────────────────────────────────
// Returns a promise that resolves when installation finishes.
// The progress callback receives parsed [PROGRESS] lines.
function installEgg(eggId, variantId, instanceDir, version, progress = () => {}) {
  return new Promise((resolve, reject) => {
    const egg = getEgg(eggId);
    if (!egg) return reject(new Error(`Egg not found: ${eggId}`));
    if (!egg._hasInstall) return reject(new Error(`Egg ${eggId} has no install.sh`));

    const installScript = path.join(egg._dir, 'install.sh');
    const runScript = path.join(egg._dir, 'run.sh');

    progress('install_start', `Running ${egg.name} installer...`);

    // Spawn install.sh as a child process so we can stream output
    const child = spawn('bash', [installScript, variantId, version, instanceDir], {
      cwd: instanceDir,
      env: {
        ...process.env,
        // Pass egg environment defaults
        ...(Object.fromEntries(
          Object.entries(egg.environment || {}).map(([k, v]) => [k, v.default || ''])
        )),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 600000, // 10 minute max
    });

    let lastStep = '';

    child.stdout.on('data', (data) => {
      const lines = data.toString().split('\n');
      for (const line of lines) {
        if (!line.trim()) continue;

        // Parse [PROGRESS] lines
        const match = line.match(/^\[PROGRESS\]\s+(\S+)\s+(.*)/);
        if (match) {
          lastStep = match[1];
          progress(match[1], match[2]);
        } else {
          // Regular stdout — forward as log
          console.log(`[Install ${egg.id}] ${line}`);
        }
      }
    });

    child.stderr.on('data', (data) => {
      // Stderr is informational during install (curl progress, etc.)
      const lines = data.toString().split('\n').filter(l => l.trim());
      for (const line of lines) {
        console.warn(`[Install ${egg.id}] ${line}`);
      }
    });

    child.on('close', (code) => {
      if (code !== 0) {
        const msg = `Install script exited with code ${code}`;
        progress('error', msg);
        return reject(new Error(msg));
      }

      // Copy run.sh → instance/start.sh
      if (egg._hasRun) {
        try {
          fs.copyFileSync(runScript, path.join(instanceDir, 'start.sh'));
          fs.chmodSync(path.join(instanceDir, 'start.sh'), 0o755);
          progress('startup', 'Start script installed.');
        } catch (e) {
          progress('error', `Failed to copy run.sh: ${e.message}`);
          return reject(e);
        }
      }

      // Write default config if defined in egg
      if (egg.configTemplate?.content) {
        const cfgPath = path.join(instanceDir, egg.configTemplate.filename);
        if (!fs.existsSync(cfgPath)) {
          // Template variables are replaced by the caller (instances route)
          // Just write the raw template for now
          fs.writeFileSync(cfgPath, egg.configTemplate.content);
          progress('config', `Created ${egg.configTemplate.filename}`);
        }
      }

      progress('complete', 'Installation complete!');
      resolve({ variant: variantId, version });
    });

    child.on('error', (err) => {
      progress('error', `Failed to execute install.sh: ${err.message}`);
      reject(err);
    });
  });
}

module.exports = {
  getEggs,
  getEgg,
  listEggs,
  getVersions,
  installEgg,
  reloadEggs,
};
