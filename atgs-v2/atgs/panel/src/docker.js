// ==============================================================
//  ATGS — Docker Service
//
//  CRITICAL FIX: Host path detection for bind mounts.
//
//  The panel runs inside a container where data is at /data.
//  When creating instance containers, Docker needs the HOST path
//  (e.g., C:\atgs\data on Windows), not the panel's internal path.
//
//  On startup, we inspect our own container to find what host
//  path maps to /data, then use that for all bind mounts.
// ==============================================================
const http = require('http');
const os = require('os');
const config = require('./config');

// Cached host path — resolved once at startup
let hostDataDir = null;

function dockerRequest(method, endpoint, body = null) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      socketPath: config.dockerSocket, path: endpoint, method,
      headers: { 'Content-Type': 'application/json' },
    }, res => {
      let data = '';
      res.on('data', c => (data += c));
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: data ? JSON.parse(data) : null }); }
        catch { resolve({ status: res.statusCode, data }); }
      });
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

// Raw request that returns the body as a string (for logs endpoint)
function dockerRequestRaw(method, endpoint) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      socketPath: config.dockerSocket, path: endpoint, method,
    }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        // Docker logs have 8-byte frame headers when tty=false
        // Strip them to get clean text
        let text = '';
        let offset = 0;
        while (offset + 8 <= buf.length) {
          const size = buf.readUInt32BE(offset + 4);
          if (offset + 8 + size > buf.length) break;
          text += buf.slice(offset + 8, offset + 8 + size).toString('utf8');
          offset += 8 + size;
        }
        // If parsing failed (tty=true containers), just use raw text
        if (!text && buf.length > 0) text = buf.toString('utf8');
        resolve(text);
      });
    });
    req.on('error', reject);
    req.end();
  });
}

// ── Host Path Detection ──────────────────────────────────────
// Inspect our own container to find what host path maps to /data.
// This runs once at startup.
async function resolveHostDataDir() {
  // If explicitly set via env, use that
  if (process.env.HOST_DATA_DIR) {
    hostDataDir = process.env.HOST_DATA_DIR;
    console.log(`[Docker] Using HOST_DATA_DIR from env: ${hostDataDir}`);
    return;
  }

  try {
    // Our container ID is our hostname
    const containerId = os.hostname();
    const r = await dockerRequest('GET', `/containers/${containerId}/json`);

    if (r.status === 200 && r.data?.Mounts) {
      // Find the mount that maps to /data inside our container
      const dataMount = r.data.Mounts.find(m => m.Destination === '/data');
      if (dataMount) {
        hostDataDir = dataMount.Source;
        console.log(`[Docker] Detected host data path: ${hostDataDir}`);
        return;
      }
    }

    // Fallback: try by container name
    const r2 = await dockerRequest('GET', '/containers/atgs-panel/json');
    if (r2.status === 200 && r2.data?.Mounts) {
      const dataMount = r2.data.Mounts.find(m => m.Destination === '/data');
      if (dataMount) {
        hostDataDir = dataMount.Source;
        console.log(`[Docker] Detected host data path (by name): ${hostDataDir}`);
        return;
      }
    }
  } catch (e) {
    console.warn('[Docker] Could not auto-detect host path:', e.message);
  }

  // Last resort: use the internal path (works if panel runs on bare metal)
  hostDataDir = config.dataDir;
  console.warn(`[Docker] WARNING: Using internal path as host path: ${hostDataDir}`);
  console.warn('[Docker] If instances have empty files, set HOST_DATA_DIR in .env');
}

// Convert an internal panel path to a host path
function toHostPath(internalPath) {
  if (!hostDataDir) return internalPath;
  // Replace /data prefix with the detected host path
  if (internalPath.startsWith(config.dataDir)) {
    return hostDataDir + internalPath.slice(config.dataDir.length);
  }
  return internalPath;
}

// ── Container Management ─────────────────────────────────────
async function ensureNetwork() {
  const check = await dockerRequest('GET', `/networks?filters={"name":["${config.networkName}"]}`);
  if (check.data?.length > 0) return;
  await dockerRequest('POST', '/networks/create', { Name: config.networkName, Driver: 'bridge' });
}

async function createInstance(inst) {
  await ensureNetwork();
  if (!hostDataDir) await resolveHostDataDir();

  const name = `atgs-${inst.id}`;
  // Use HOST path for bind mount, not the panel-internal path
  const hostInstDir = toHostPath(`${config.instancesDir}/${inst.id}`);

  const portBindings = {};
  const exposedPorts = {};
  const env = [
    `MIN_RAM=${inst.minRam || config.defaultMinRam}`,
    `MAX_RAM=${inst.maxRam || config.defaultMaxRam}`,
  ];

  for (const [k, v] of Object.entries(inst.env || {})) env.push(`${k}=${v}`);

  for (const p of (inst.ports || [])) {
    const key = `${p.port}/${p.protocol || 'tcp'}`;
    exposedPorts[key] = {};
    portBindings[key] = [{ HostPort: String(p.hostPort || p.port) }];
  }

  const rconPort = inst.rconPort;
  if (rconPort) {
    exposedPorts[`${rconPort}/tcp`] = {};
    env.push(`RCON_PORT=${rconPort}`, `RCON_PASSWORD=${inst.rconPassword || 'atgs'}`);
  }

  const healthTest = rconPort
    ? ['CMD-SHELL', `bash -c 'echo > /dev/tcp/127.0.0.1/${rconPort}' 2>/dev/null || exit 1`]
    : ['CMD-SHELL', 'pgrep -f java || exit 1'];

  console.log(`[Docker] Creating container ${name}`);
  console.log(`[Docker]   Bind: ${hostInstDir} -> /instance`);

  const result = await dockerRequest('POST', '/containers/create?name=' + name, {
    name,
    Image: config.runnerImage,
    Env: env,
    ExposedPorts: exposedPorts,
    Tty: true, // Makes log output cleaner (no frame headers)
    Healthcheck: {
      Test: healthTest,
      Interval: 15000000000,
      Timeout: 5000000000,
      StartPeriod: 90000000000,
      Retries: 8,
    },
    HostConfig: {
      Binds: [`${hostInstDir}:/instance`],
      PortBindings: portBindings,
      RestartPolicy: { Name: 'unless-stopped' },
    },
    NetworkingConfig: {
      EndpointsConfig: { [config.networkName]: {} },
    },
  });

  if (result.status !== 201) {
    console.error('[Docker] Create failed:', JSON.stringify(result.data));
    throw new Error(JSON.stringify(result.data));
  }

  return result.data;
}

async function startInstance(id) {
  return dockerRequest('POST', `/containers/atgs-${id}/start`);
}

async function stopInstance(id, t = 30) {
  return dockerRequest('POST', `/containers/atgs-${id}/stop?t=${t}`);
}

async function restartInstance(id) {
  return dockerRequest('POST', `/containers/atgs-${id}/restart?t=15`);
}

async function removeInstance(id) {
  await dockerRequest('POST', `/containers/atgs-${id}/stop?t=5`).catch(() => {});
  return dockerRequest('DELETE', `/containers/atgs-${id}?force=true`);
}

async function getInstanceStatus(id) {
  try {
    const r = await dockerRequest('GET', `/containers/atgs-${id}/json`);
    if (r.status !== 200) return { running: false, status: 'not_created', health: 'unknown' };
    const s = r.data.State || {};
    return {
      running: s.Running || false,
      status: s.Status || 'unknown',
      health: s.Health?.Status || 'none',
      startedAt: s.StartedAt,
      exitCode: s.ExitCode,
      error: s.Error || null,
    };
  } catch { return { running: false, status: 'error', health: 'unknown' }; }
}

async function getInstanceStats(id) {
  try {
    const r = await dockerRequest('GET', `/containers/atgs-${id}/stats?stream=false`);
    if (r.status !== 200) return null;
    const d = r.data;
    const cpuD = d.cpu_stats.cpu_usage.total_usage - (d.precpu_stats?.cpu_usage?.total_usage || 0);
    const sysD = (d.cpu_stats.system_cpu_usage || 0) - (d.precpu_stats?.system_cpu_usage || 0);
    return {
      cpu: (sysD > 0 ? (cpuD / sysD) * (d.cpu_stats.online_cpus || 1) * 100 : 0).toFixed(1),
      memUsed: d.memory_stats?.usage || 0,
      memLimit: d.memory_stats?.limit || 0,
    };
  } catch { return null; }
}

// Get container logs via Docker API (for console streaming)
async function getContainerLogs(id, tail = 200) {
  try {
    return await dockerRequestRaw('GET',
      `/containers/atgs-${id}/logs?stdout=true&stderr=true&tail=${tail}&timestamps=false`
    );
  } catch { return ''; }
}

// Init: resolve host path on startup
async function init() {
  await resolveHostDataDir();
}

module.exports = {
  dockerRequest,
  ensureNetwork,
  createInstance,
  startInstance,
  stopInstance,
  restartInstance,
  removeInstance,
  getInstanceStatus,
  getInstanceStats,
  getContainerLogs,
  init,
  toHostPath,
};
