// ==============================================================
//  ATGS Module: Resource Alerts
//  Monitors instance CPU/RAM usage and triggers warnings
//  or auto-actions when thresholds are exceeded.
// ==============================================================
let ctx = null;
let pollTimer = null;

// Track consecutive threshold breaches (avoid single-spike alerts)
const breachCount = {}; // instanceId -> { ram: count, cpu: count }
// Track last alert time to avoid spam
const lastAlert = {}; // instanceId -> timestamp
// Store current alerts for API
const activeAlerts = {}; // instanceId -> [{ level, metric, value, threshold, time }]

const COOLDOWN = 300000; // 5 minutes between repeated alerts

function getAlerts(instanceId) {
  return activeAlerts[instanceId] || [];
}

function addAlert(instanceId, level, metric, value, threshold) {
  if (!activeAlerts[instanceId]) activeAlerts[instanceId] = [];

  // Dedup: don't add if same alert already active
  const exists = activeAlerts[instanceId].find(a => a.metric === metric && a.level === level);
  if (exists) {
    exists.value = value;
    exists.time = new Date().toISOString();
    return;
  }

  activeAlerts[instanceId].unshift({
    level, metric, value, threshold,
    time: new Date().toISOString(),
  });

  // Keep last 20 per instance
  if (activeAlerts[instanceId].length > 20) {
    activeAlerts[instanceId] = activeAlerts[instanceId].slice(0, 20);
  }
}

function clearAlerts(instanceId, metric) {
  if (!activeAlerts[instanceId]) return;
  activeAlerts[instanceId] = activeAlerts[instanceId].filter(a => a.metric !== metric);
}

async function checkInstance(inst) {
  const stats = await ctx.docker.getInstanceStats(inst.id);
  if (!stats) return;

  const cfg = ctx.getConfig();
  const ramPct = stats.memLimit > 0 ? (stats.memUsed / stats.memLimit) * 100 : 0;
  const cpuPct = parseFloat(stats.cpu) || 0;

  if (!breachCount[inst.id]) breachCount[inst.id] = { ram: 0, cpu: 0 };

  // ── RAM Checks ─────────────────────────────────────────────
  const ramCrit = cfg.ramCriticalPercent || 95;
  const ramWarn = cfg.ramWarnPercent || 80;

  if (ramPct >= ramCrit) {
    breachCount[inst.id].ram++;
    if (breachCount[inst.id].ram >= 3) { // 3 consecutive checks
      addAlert(inst.id, 'critical', 'ram', Math.round(ramPct), ramCrit);

      // Notify (throttled)
      const now = Date.now();
      const lastKey = `${inst.id}-ram-critical`;
      if (!lastAlert[lastKey] || now - lastAlert[lastKey] > COOLDOWN) {
        lastAlert[lastKey] = now;
        console.warn(`[Resources] CRITICAL: ${inst.name} RAM at ${Math.round(ramPct)}%`);
        ctx.broadcast('resource-alert', {
          instanceId: inst.id, level: 'critical', metric: 'ram',
          value: Math.round(ramPct), message: `RAM at ${Math.round(ramPct)}% (critical: ${ramCrit}%)`,
        });

        // Auto-restart if configured
        if (cfg.autoRestartOnCritical) {
          console.log(`[Resources] Auto-restarting ${inst.name} due to critical RAM`);
          ctx.docker.restartInstance(inst.id).catch(() => {});
          breachCount[inst.id].ram = 0;
        }
      }
    }
  } else if (ramPct >= ramWarn) {
    breachCount[inst.id].ram = Math.max(0, breachCount[inst.id].ram - 1);
    addAlert(inst.id, 'warning', 'ram', Math.round(ramPct), ramWarn);
  } else {
    breachCount[inst.id].ram = 0;
    clearAlerts(inst.id, 'ram');
  }

  // ── CPU Checks ─────────────────────────────────────────────
  const cpuWarn = cfg.cpuWarnPercent || 90;

  if (cpuPct >= cpuWarn) {
    breachCount[inst.id].cpu++;
    if (breachCount[inst.id].cpu >= 3) {
      addAlert(inst.id, 'warning', 'cpu', Math.round(cpuPct), cpuWarn);

      const now = Date.now();
      const lastKey = `${inst.id}-cpu-warn`;
      if (!lastAlert[lastKey] || now - lastAlert[lastKey] > COOLDOWN) {
        lastAlert[lastKey] = now;
        ctx.broadcast('resource-alert', {
          instanceId: inst.id, level: 'warning', metric: 'cpu',
          value: Math.round(cpuPct), message: `CPU at ${Math.round(cpuPct)}% (threshold: ${cpuWarn}%)`,
        });
      }
    }
  } else {
    breachCount[inst.id].cpu = 0;
    clearAlerts(inst.id, 'cpu');
  }
}

async function poll() {
  for (const inst of ctx.store.instances.all()) {
    if (inst.status !== 'running') continue;
    try { await checkInstance(inst); } catch {}
  }
}

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    // Get alerts for an instance
    router.get('/:instanceId', (req, res) => {
      res.json(getAlerts(req.params.instanceId));
    });

    // Get alerts for all instances
    router.get('/', (req, res) => {
      const all = {};
      for (const [id, alerts] of Object.entries(activeAlerts)) {
        if (alerts.length) all[id] = alerts;
      }
      res.json(all);
    });

    // Clear alerts for an instance
    router.post('/:instanceId/clear', (req, res) => {
      activeAlerts[req.params.instanceId] = [];
      res.json({ ok: true });
    });

    context.app.use('/api/resource-alerts', router);

    const interval = (ctx.getConfig().checkInterval || 30) * 1000;
    pollTimer = setInterval(() => poll().catch(() => {}), interval);
    console.log(`[Resources] Monitoring every ${interval / 1000}s. RAM warn: ${ctx.getConfig().ramWarnPercent || 80}%, critical: ${ctx.getConfig().ramCriticalPercent || 95}%`);
  },

  destroy() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  },
};
