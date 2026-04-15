// ==============================================================
//  ATGS Module: Crash Detection
//  Monitors instances for crashes, parses reports, prevents
//  boot loops, and provides human-readable diagnostics.
// ==============================================================
const fs = require('fs');
const path = require('path');

let ctx = null;
let pollTimer = null;

// Track restart timestamps per instance for boot-loop detection
const restartLog = {}; // instanceId -> [timestamp, timestamp, ...]
// Track previous container status to detect transitions
const prevStatus = {}; // instanceId -> { running, exitCode }
// Store crash events
const crashHistory = {}; // instanceId -> [{ time, reason, file }, ...]

// ── Known crash patterns ─────────────────────────────────────
const CRASH_PATTERNS = [
  { pattern: /java\.lang\.OutOfMemoryError/i, reason: 'Out of memory — increase MAX_RAM in instance settings' },
  { pattern: /Failed to bind to port/i, reason: 'Port already in use — another instance or program is using this port' },
  { pattern: /Mixin apply.*failed/i, reason: 'Mod mixin conflict — two mods are modifying the same game code' },
  { pattern: /DuplicateModsFoundException/i, reason: 'Duplicate mod — the same mod is installed twice (check mods/ folder)' },
  { pattern: /NoClassDefFoundError.*fabric/i, reason: 'Missing Fabric API — install Fabric API from the Addons browser' },
  { pattern: /NoSuchMethodError|NoSuchFieldError/i, reason: 'Mod version mismatch — a mod is incompatible with this Minecraft version' },
  { pattern: /Failed to load.*mixin/i, reason: 'Mod loading error — a mod failed to initialize. Check the crash report for details' },
  { pattern: /java\.net\.BindException/i, reason: 'Port conflict — the server port is already in use' },
  { pattern: /Encountered an unexpected exception/i, reason: 'Server encountered an internal error — check the crash report' },
  { pattern: /World file is corrupted/i, reason: 'World corruption — restore from a backup' },
];

// ── Parse a crash report file ────────────────────────────────
function parseCrashReport(filePath) {
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    // Try known patterns first
    for (const { pattern, reason } of CRASH_PATTERNS) {
      if (pattern.test(content)) return reason;
    }
    // Extract the main exception line
    const exMatch = content.match(/Description: (.+)/);
    if (exMatch) return exMatch[1].trim();
    const caused = content.match(/Caused by: (.+)/);
    if (caused) return caused[1].trim();
    return 'Unknown crash — see crash report for details';
  } catch {
    return 'Could not read crash report';
  }
}

// ── Find latest crash report ─────────────────────────────────
function getLatestCrash(instanceId) {
  const dir = path.join(ctx.config.instancesDir, instanceId, 'crash-reports');
  if (!fs.existsSync(dir)) return null;

  const files = fs.readdirSync(dir)
    .filter(f => f.endsWith('.txt'))
    .map(f => ({ name: f, time: fs.statSync(path.join(dir, f)).mtime }))
    .sort((a, b) => b.time - a.time);

  if (!files.length) return null;
  const latest = files[0];
  return {
    file: latest.name,
    time: latest.time.toISOString(),
    reason: parseCrashReport(path.join(dir, latest.name)),
  };
}

// ── Also check JVM crash logs (hs_err_pid) ───────────────────
function getLatestJvmCrash(instanceId) {
  const dir = path.join(ctx.config.instancesDir, instanceId);
  try {
    const files = fs.readdirSync(dir)
      .filter(f => f.startsWith('hs_err_pid') && f.endsWith('.log'))
      .map(f => ({ name: f, time: fs.statSync(path.join(dir, f)).mtime }))
      .sort((a, b) => b.time - a.time);

    if (!files.length) return null;
    return {
      file: files[0].name,
      time: files[0].time.toISOString(),
      reason: 'JVM crash (native error) — likely out of memory or a native library issue',
    };
  } catch { return null; }
}

// ── Boot loop detection ──────────────────────────────────────
function recordRestart(instanceId) {
  if (!restartLog[instanceId]) restartLog[instanceId] = [];
  restartLog[instanceId].push(Date.now());

  const cfg = ctx.getConfig();
  const window = (cfg.bootLoopWindow || 5) * 60000;
  const threshold = cfg.bootLoopThreshold || 3;

  // Prune old entries
  const cutoff = Date.now() - window;
  restartLog[instanceId] = restartLog[instanceId].filter(t => t > cutoff);

  if (restartLog[instanceId].length >= threshold) {
    console.error(`[Crash Detect] BOOT LOOP detected for ${instanceId}: ${restartLog[instanceId].length} restarts in ${cfg.bootLoopWindow}m`);

    const reason = `Boot loop detected (${restartLog[instanceId].length} restarts in ${cfg.bootLoopWindow} minutes)`;
    addCrashEvent(instanceId, reason);

    // Fire hook for Discord
    ctx.broadcast('crash', { instanceId, reason });
    const modules = require(path.join(__dirname, '..', '..', 'panel', 'src', 'modules'));
    try { modules.fireHook('onCrashDetected', instanceId, reason); } catch {}

    if (cfg.autoStopOnBootLoop) {
      console.log(`[Crash Detect] Auto-stopping ${instanceId} to break loop`);
      ctx.docker.stopInstance(instanceId).catch(() => {});
      ctx.store.instances.update(instanceId, { status: 'crashed', crashReason: reason });
    }

    restartLog[instanceId] = [];
  }
}

function addCrashEvent(instanceId, reason, file) {
  if (!crashHistory[instanceId]) crashHistory[instanceId] = [];
  crashHistory[instanceId].unshift({ time: new Date().toISOString(), reason, file: file || null });
  // Keep last 50
  if (crashHistory[instanceId].length > 50) crashHistory[instanceId] = crashHistory[instanceId].slice(0, 50);
}

// ── Status Polling ───────────────────────────────────────────
async function pollInstances() {
  for (const inst of ctx.store.instances.all()) {
    try {
      const status = await ctx.docker.getInstanceStatus(inst.id);
      const prev = prevStatus[inst.id];

      if (prev && prev.running && !status.running && status.exitCode !== 0) {
        // Container was running, now stopped with non-zero exit — crash
        console.log(`[Crash Detect] Instance ${inst.id} crashed (exit code ${status.exitCode})`);

        let reason = `Exited with code ${status.exitCode}`;

        // Check crash reports
        const mcCrash = getLatestCrash(inst.id);
        const jvmCrash = getLatestJvmCrash(inst.id);
        const latestCrash = mcCrash || jvmCrash;

        if (latestCrash) {
          const crashAge = Date.now() - new Date(latestCrash.time).getTime();
          if (crashAge < 60000) { // Crash report within last minute
            reason = latestCrash.reason;
          }
        }

        addCrashEvent(inst.id, reason, latestCrash?.file);
        ctx.store.instances.update(inst.id, { status: 'crashed', crashReason: reason });
        ctx.broadcast('crash', { instanceId: inst.id, reason });

        // Fire hook for Discord
        try {
          const mods = require(path.join(__dirname, '..', '..', 'panel', 'src', 'modules'));
          mods.fireHook('onCrashDetected', inst.id, reason);
        } catch {}

        // Record for boot-loop detection (only if container restarts automatically)
        if (status.status === 'restarting') {
          recordRestart(inst.id);
        }
      }

      // Detect restart (was stopped/exited, now running again)
      if (prev && !prev.running && status.running) {
        recordRestart(inst.id);
      }

      prevStatus[inst.id] = { running: status.running, exitCode: status.exitCode };
    } catch {}
  }
}

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    // Get crash history for an instance
    router.get('/:instanceId', (req, res) => {
      const id = req.params.instanceId;
      const history = crashHistory[id] || [];
      const latestReport = getLatestCrash(id);
      const jvmCrash = getLatestJvmCrash(id);
      res.json({ history, latestReport, jvmCrash });
    });

    // Clear crash status (acknowledge)
    router.post('/:instanceId/acknowledge', (req, res) => {
      const inst = ctx.store.instances.find(req.params.instanceId);
      if (!inst) return res.status(404).json({ error: 'Not found' });
      if (inst.status === 'crashed') {
        ctx.store.instances.update(req.params.instanceId, { status: 'stopped', crashReason: null });
      }
      res.json({ ok: true });
    });

    // Get all crash reports as file list
    router.get('/:instanceId/reports', (req, res) => {
      const dir = path.join(ctx.config.instancesDir, req.params.instanceId, 'crash-reports');
      if (!fs.existsSync(dir)) return res.json([]);
      const files = fs.readdirSync(dir)
        .filter(f => f.endsWith('.txt'))
        .map(f => {
          const st = fs.statSync(path.join(dir, f));
          return { name: f, size: st.size, time: st.mtime.toISOString() };
        })
        .sort((a, b) => new Date(b.time) - new Date(a.time));
      res.json(files);
    });

    context.app.use('/api/crashes', router);

    // Start polling every 10 seconds
    pollTimer = setInterval(() => pollInstances().catch(() => {}), 10000);
    console.log('[Crash Detect] Monitoring active. Boot loop threshold: ' +
      (ctx.getConfig().bootLoopThreshold || 3) + ' restarts in ' +
      (ctx.getConfig().bootLoopWindow || 5) + 'm');
  },

  destroy() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  },
};
