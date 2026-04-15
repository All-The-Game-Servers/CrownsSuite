// ==============================================================
//  ATGS Module: Server Jar Updater
//  Checks for newer server jar builds and handles one-click updates.
//  Supports Paper (build numbers), Fabric (loader versions).
// ==============================================================
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

let ctx = null;

function fetch(url) {
  return JSON.parse(execSync(`curl -sf "${url}"`, { timeout: 15000 }).toString());
}

// ── Check for updates per variant ────────────────────────────
function checkPaper(version) {
  try {
    const data = fetch(`https://api.papermc.io/v2/projects/paper/versions/${version}/builds`);
    const builds = data.builds || [];
    if (!builds.length) return null;
    const latest = builds[builds.length - 1];
    return { latestBuild: latest.build, jarName: latest.downloads?.application?.name, channel: latest.channel };
  } catch { return null; }
}

function checkFabric(version) {
  try {
    const loaders = fetch('https://meta.fabricmc.net/v2/versions/loader');
    const latest = loaders.find(l => l.stable) || loaders[0];
    const installers = fetch('https://meta.fabricmc.net/v2/versions/installer');
    const latestInstaller = installers[0];
    return { latestLoader: latest?.version, latestInstaller: latestInstaller?.version };
  } catch { return null; }
}

function checkForge(version) {
  try {
    const data = fetch('https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json');
    const recommended = data.promos?.[`${version}-recommended`];
    const latest = data.promos?.[`${version}-latest`];
    return { recommended, latest: latest || recommended };
  } catch { return null; }
}

// ── Get current build info from instance ─────────────────────
function getCurrentBuild(instDir, variantId) {
  // Try to read from a marker file we write on install/update
  const markerPath = path.join(instDir, '.atgs-jar-info.json');
  try { return JSON.parse(fs.readFileSync(markerPath, 'utf8')); }
  catch { return null; }
}

function saveCurrentBuild(instDir, info) {
  fs.writeFileSync(path.join(instDir, '.atgs-jar-info.json'), JSON.stringify(info, null, 2));
}

// ── Perform update ───────────────────────────────────────────
function updatePaper(instDir, version, build, jarName) {
  const url = `https://api.papermc.io/v2/projects/paper/versions/${version}/builds/${build}/downloads/${jarName}`;
  // Backup old jar
  const old = path.join(instDir, 'server.jar');
  if (fs.existsSync(old)) {
    fs.copyFileSync(old, path.join(instDir, 'server.jar.backup'));
  }
  execSync(`curl -fSL -o "${path.join(instDir, 'server.jar')}" "${url}"`, { timeout: 300000 });
  saveCurrentBuild(instDir, { variant: 'paper', version, build, jarName, updatedAt: new Date().toISOString() });
}

function updateFabric(instDir, version) {
  const installerPath = path.join(instDir, 'fabric-installer.jar');
  execSync(`curl -fSL -o "${installerPath}" "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.1/fabric-installer-1.0.1.jar"`, { timeout: 120000 });

  // Backup existing
  const launchJar = path.join(instDir, 'fabric-server-launch.jar');
  if (fs.existsSync(launchJar)) fs.copyFileSync(launchJar, launchJar + '.backup');

  execSync(`cd "${instDir}" && java -jar fabric-installer.jar server -mcversion ${version} -downloadMinecraft`, { timeout: 600000 });
  try { fs.unlinkSync(installerPath); } catch {}

  saveCurrentBuild(instDir, { variant: 'fabric', version, updatedAt: new Date().toISOString() });
}

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    // Check for updates
    router.get('/:instanceId/check', (req, res) => {
      const inst = context.store.instances.find(req.params.instanceId);
      if (!inst) return res.status(404).json({ error: 'Not found' });

      const instDir = path.join(context.config.instancesDir, inst.id);
      const current = getCurrentBuild(instDir, inst.variantId);
      let available = null;

      switch (inst.variantId) {
        case 'paper':
          available = checkPaper(inst.version);
          break;
        case 'fabric':
          available = checkFabric(inst.version);
          break;
        case 'forge':
          available = checkForge(inst.version);
          break;
        default:
          return res.json({ updateAvailable: false, message: 'Auto-update not supported for this variant' });
      }

      if (!available) return res.json({ updateAvailable: false, current });

      let updateAvailable = false;
      if (inst.variantId === 'paper' && current?.build && available.latestBuild > current.build) {
        updateAvailable = true;
      } else if (inst.variantId === 'paper' && !current?.build) {
        updateAvailable = true; // No marker = unknown, offer update
      } else if (inst.variantId === 'fabric' && available.latestLoader) {
        updateAvailable = true; // Always offer for Fabric since we can't easily compare
      }

      res.json({ updateAvailable, current, available, variant: inst.variantId });
    });

    // Perform update
    router.post('/:instanceId/update', async (req, res) => {
      const inst = context.store.instances.find(req.params.instanceId);
      if (!inst) return res.status(404).json({ error: 'Not found' });

      const instDir = path.join(context.config.instancesDir, inst.id);
      const wasRunning = inst.status === 'running';

      try {
        // Stop if running
        if (wasRunning) {
          await context.docker.stopInstance(inst.id);
          context.store.instances.update(inst.id, { status: 'updating' });
        }

        switch (inst.variantId) {
          case 'paper': {
            const info = checkPaper(inst.version);
            if (!info) throw new Error('Could not fetch Paper build info');
            updatePaper(instDir, inst.version, info.latestBuild, info.jarName);
            break;
          }
          case 'fabric':
            updateFabric(instDir, inst.version);
            break;
          default:
            throw new Error('Update not supported for ' + inst.variantId);
        }

        context.store.instances.update(inst.id, { status: wasRunning ? 'running' : 'stopped' });

        // Restart if was running
        if (wasRunning) {
          await context.docker.startInstance(inst.id);
        }

        res.json({ ok: true, message: 'Server jar updated successfully' });
      } catch (e) {
        context.store.instances.update(inst.id, { status: 'stopped' });
        res.status(500).json({ error: e.message });
      }
    });

    context.app.use('/api/jar-update', router);
  },
};
