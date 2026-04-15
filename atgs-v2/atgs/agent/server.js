#!/usr/bin/env node
const crypto = require('crypto');
const http = require('http');
const express = require('express');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync, spawnSync } = require('child_process');
const { URL } = require('url');
const { Rcon } = require('rcon-client');

function readSetting(name, fallback = '') {
  const filePath = process.env[`${name}_FILE`];
  if (filePath) {
    try {
      return fs.readFileSync(filePath, 'utf8').trim();
    } catch {}
  }

  const value = process.env[name];
  return value !== undefined ? value : fallback;
}

const PORT = parseInt(process.env.PORT || '9393', 10);
const AGENT_SECRET = readSetting('AGENT_SECRET', 'change-me');
const DOCKER_HOST = process.env.DOCKER_HOST || '';
const DOCKER_SOCKET = process.env.DOCKER_SOCKET || '/var/run/docker.sock';
const DATA_DIR = process.env.DATA_DIR || '/data';
const EGGS_DIR = process.env.EGGS_DIR || '/eggs';
const NETWORK_NAME = process.env.NETWORK_NAME || 'atgs-runtime';
const RUNNER_IMAGE = process.env.RUNNER_IMAGE || 'atgs-runner:latest';
const RUNTIME_CONTAINER_NAME = process.env.RUNTIME_CONTAINER_NAME || 'atgs-minecraft';
const INSTANCE_ID = process.env.MAIN_INSTANCE_ID || 'main';
const RCON_HOST = process.env.RCON_HOST || RUNTIME_CONTAINER_NAME;
const RCON_ENABLED = process.env.RCON_ENABLED !== 'false';
const INSTANCE_DIR = path.join(DATA_DIR, 'instances', INSTANCE_ID);
const FILES_DIR = path.join(INSTANCE_DIR, 'files');
const BACKUPS_DIR = path.join(INSTANCE_DIR, 'backups');
const IMPORTS_DIR = path.join(DATA_DIR, 'imports');
const BACKUP_FORMAT = 'atgs-v2';
const BACKUP_FORMAT_VERSION = 1;

let hostDataDir = null;

function getDockerEndpoint() {
  if (DOCKER_HOST.startsWith('tcp://')) {
    const url = new URL(DOCKER_HOST.replace('tcp://', 'http://'));
    return { type: 'tcp', protocol: 'http:', hostname: url.hostname, port: parseInt(url.port || '2375', 10) };
  }

  if (DOCKER_HOST.startsWith('http://') || DOCKER_HOST.startsWith('https://')) {
    const url = new URL(DOCKER_HOST);
    return { type: 'tcp', protocol: url.protocol, hostname: url.hostname, port: parseInt(url.port || (url.protocol === 'https:' ? '443' : '80'), 10) };
  }

  if (DOCKER_HOST.startsWith('unix://')) {
    return { type: 'unix', socketPath: DOCKER_HOST.slice('unix://'.length) };
  }

  return { type: 'unix', socketPath: DOCKER_SOCKET };
}

const dockerEndpoint = getDockerEndpoint();

function dockerRequestOptions(method, endpoint) {
  const options = {
    path: endpoint,
    method,
    headers: {}
  };

  if (dockerEndpoint.type === 'unix') {
    options.socketPath = dockerEndpoint.socketPath;
  } else {
    options.protocol = dockerEndpoint.protocol;
    options.hostname = dockerEndpoint.hostname;
    options.port = dockerEndpoint.port;
  }

  return options;
}

function dockerRequest(method, endpoint, body = null) {
  return new Promise((resolve, reject) => {
    const payload = body ? JSON.stringify(body) : null;
    const options = dockerRequestOptions(method, endpoint);
    if (payload) {
      options.headers['Content-Type'] = 'application/json';
      options.headers['Content-Length'] = Buffer.byteLength(payload);
    }

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, data: data ? JSON.parse(data) : null });
        } catch {
          resolve({ status: res.statusCode, data });
        }
      });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function resolveHostDataDir() {
  if (process.env.HOST_DATA_DIR) {
    hostDataDir = process.env.HOST_DATA_DIR;
    return hostDataDir;
  }

  const containerId = os.hostname();
  const ownContainer = await dockerRequest('GET', `/containers/${containerId}/json`).catch(() => null);
  const mounts = ownContainer?.data?.Mounts || [];
  const dataMount = mounts.find((mount) => mount.Destination === '/data');
  hostDataDir = dataMount?.Source || DATA_DIR;
  return hostDataDir;
}

function toHostPath(internalPath) {
  if (!hostDataDir) throw new Error('Host data directory not resolved');
  if (!internalPath.startsWith(DATA_DIR)) return internalPath;
  return hostDataDir + internalPath.slice(DATA_DIR.length);
}

function ensureDirs() {
  for (const dir of [FILES_DIR, BACKUPS_DIR, IMPORTS_DIR]) fs.mkdirSync(dir, { recursive: true });
}

function sanitizeLabel(label = 'manual') {
  return String(label || 'manual').toLowerCase().replace(/[^a-z0-9-_]+/g, '-').replace(/-{2,}/g, '-').replace(/^-|-$/g, '') || 'manual';
}

function buildServerSnapshot(server) {
  return {
    id: server.id,
    name: server.name,
    flavor: server.flavor,
    version: server.version,
    profile: server.profile,
    minRam: server.minRam,
    maxRam: server.maxRam,
    gamePort: server.gamePort,
    gatewayPort: server.gatewayPort,
    bedrockPort: server.bedrockPort,
    geyserEnabled: Boolean(server.geyserEnabled),
    sleepEnabled: Boolean(server.sleepEnabled),
    idleGraceSeconds: server.idleGraceSeconds,
    motd: server.motd,
    difficulty: server.difficulty,
    maxPlayers: server.maxPlayers,
    onlineMode: Boolean(server.onlineMode),
    whitelistEnabled: Boolean(server.whitelistEnabled),
    viewDistance: server.viewDistance,
    simulationDistance: server.simulationDistance,
    levelName: server.levelName,
    rconPort: server.rconPort,
    rconPassword: server.rconPassword,
    javaArgs: server.javaArgs
  };
}

function buildBackupManifest(server, addons, label, fileName) {
  return {
    format: BACKUP_FORMAT,
    formatVersion: BACKUP_FORMAT_VERSION,
    backupType: 'portable-export',
    label,
    fileName,
    createdAt: new Date().toISOString(),
    source: {
      instanceId: server.id,
      name: server.name,
      flavor: server.flavor,
      version: server.version,
      profile: server.profile
    },
    includes: {
      files: true,
      serverConfig: true,
      addons: true,
      secrets: false
    },
    restore: {
      strategy: 'manifest-guided',
      runtimeFilesRegenerated: true,
      secretsRequired: true
    },
    counts: {
      addons: Array.isArray(addons) ? addons.length : 0
    }
  };
}

function removeEphemeralPaths(rootDir) {
  for (const name of ['logs', 'crash-reports', 'backups']) {
    const target = path.join(rootDir, name);
    if (fs.existsSync(target)) fs.rmSync(target, { recursive: true, force: true });
  }
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, JSON.stringify(value, null, 2) + '\n', 'utf8');
}

function tryReadArchiveEntry(archivePath, candidates) {
  for (const candidate of candidates) {
    try {
      return execFileSync('tar', ['-xOf', archivePath, candidate], {
        timeout: 30000,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      });
    } catch {}
  }
  return null;
}

function readBackupManifest(archivePath) {
  const raw = tryReadArchiveEntry(archivePath, ['manifest.json', './manifest.json']);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function buildBackupMetadata(name) {
  const fullPath = path.join(BACKUPS_DIR, name);
  const stat = fs.statSync(fullPath);
  const manifest = readBackupManifest(fullPath);
  const isAtgsV2 = manifest?.format === BACKUP_FORMAT;
  return {
    name,
    size: stat.size,
    created: stat.mtime.toISOString(),
    format: isAtgsV2 ? BACKUP_FORMAT : 'legacy-files',
    formatVersion: isAtgsV2 ? manifest.formatVersion || BACKUP_FORMAT_VERSION : 0,
    backupType: manifest?.backupType || 'files-only',
    label: manifest?.label || null,
    source: manifest?.source || null,
    includes: manifest?.includes || { files: true, serverConfig: false, addons: false, secrets: false }
  };
}

function ensureAuth(req, res, next) {
  if (req.headers['x-atgs-secret'] !== AGENT_SECRET) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
}

function safeFilePath(relPath = '') {
  const resolved = path.resolve(FILES_DIR, relPath || '.');
  if (!resolved.startsWith(FILES_DIR)) throw new Error('Access denied');
  return resolved;
}

async function ensureNetwork() {
  const response = await dockerRequest('GET', `/networks/${encodeURIComponent(NETWORK_NAME)}`).catch(() => null);
  if (response?.status === 200) return;
  throw new Error(`Required Docker network "${NETWORK_NAME}" was not found. Start the ATGS compose stack first.`);
}

async function inspectRuntime() {
  const response = await dockerRequest('GET', `/containers/${RUNTIME_CONTAINER_NAME}/json`).catch(() => null);
  if (!response || response.status !== 200) {
    return { exists: false, running: false, status: 'missing', health: 'unknown' };
  }
  const state = response.data.State || {};
  return {
    exists: true,
    running: Boolean(state.Running),
    status: state.Status || 'unknown',
    health: state.Health?.Status || 'none',
    startedAt: state.StartedAt,
    exitCode: state.ExitCode,
    labels: response.data.Config?.Labels || {}
  };
}

async function getRuntimeStats() {
  const response = await dockerRequest('GET', `/containers/${RUNTIME_CONTAINER_NAME}/stats?stream=false`).catch(() => null);
  if (!response || response.status !== 200) return null;
  const data = response.data;
  const cpuDelta = data.cpu_stats.cpu_usage.total_usage - (data.precpu_stats?.cpu_usage?.total_usage || 0);
  const systemDelta = (data.cpu_stats.system_cpu_usage || 0) - (data.precpu_stats?.system_cpu_usage || 0);
  return {
    cpu: (systemDelta > 0 ? (cpuDelta / systemDelta) * (data.cpu_stats.online_cpus || 1) * 100 : 0).toFixed(1),
    memUsed: data.memory_stats?.usage || 0,
    memLimit: data.memory_stats?.limit || 0
  };
}

async function getRuntimeLogs(tail = 200) {
  return await new Promise((resolve) => {
    const req = http.request(dockerRequestOptions('GET', `/containers/${RUNTIME_CONTAINER_NAME}/logs?stdout=true&stderr=true&tail=${tail}&timestamps=false`));

    req.on('response', (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        const buffer = Buffer.concat(chunks);
        let output = '';
        let offset = 0;
        while (offset + 8 <= buffer.length) {
          const size = buffer.readUInt32BE(offset + 4);
          if (offset + 8 + size > buffer.length) break;
          output += buffer.slice(offset + 8, offset + 8 + size).toString('utf8');
          offset += 8 + size;
        }
        if (!output) output = buffer.toString('utf8');
        resolve({ lines: output.split('\n').filter(Boolean) });
      });
    });
    req.on('error', () => resolve({ lines: [] }));
    req.end();
  });
}

function hashServerConfig(server) {
  return crypto.createHash('sha1').update(JSON.stringify({
    flavor: server.flavor,
    version: server.version,
    profile: server.profile,
    minRam: server.minRam,
    maxRam: server.maxRam,
    rconPort: server.rconPort,
    rconPassword: server.rconPassword,
    bedrockPort: server.bedrockPort,
    geyserEnabled: server.geyserEnabled,
    javaArgs: server.javaArgs
  })).digest('hex');
}

async function createRuntimeContainer(server) {
  await ensureNetwork();
  await resolveHostDataDir();
  ensureDirs();

  const configHash = hashServerConfig(server);
  const hostFilesDir = toHostPath(FILES_DIR);
  const env = [
    `MIN_RAM=${server.minRam}`,
    `MAX_RAM=${server.maxRam}`,
    'EULA=true',
    `SERVER_FLAVOR=${server.flavor}`,
    `PERF_PROFILE=${server.profile}`,
    `JAVA_EXTRA_FLAGS=${server.javaArgs || ''}`,
    `RCON_PORT=${server.rconPort}`,
    `RCON_PASSWORD=${server.rconPassword}`
  ];

  const exposedPorts = { '25565/tcp': {} };
  const portBindings = {};
  if (server.geyserEnabled) {
    exposedPorts['19132/udp'] = {};
    portBindings['19132/udp'] = [{ HostPort: String(server.bedrockPort) }];
  }

  const response = await dockerRequest('POST', `/containers/create?name=${RUNTIME_CONTAINER_NAME}`, {
    Image: RUNNER_IMAGE,
    Env: env,
    Tty: true,
    ExposedPorts: exposedPorts,
    Labels: {
      'atgs.role': 'minecraft-runtime',
      'atgs.config-hash': configHash
    },
    Healthcheck: {
      Test: ['CMD-SHELL', 'bash -lc "exec 3<>/dev/tcp/127.0.0.1/25565"'],
      Interval: 15000000000,
      Timeout: 5000000000,
      Retries: 8,
      StartPeriod: 90000000000
    },
    HostConfig: {
      Binds: [`${hostFilesDir}:/instance`],
      PortBindings: portBindings,
      RestartPolicy: { Name: 'unless-stopped' },
      SecurityOpt: ['no-new-privileges:true']
    },
    NetworkingConfig: {
      EndpointsConfig: { [NETWORK_NAME]: {} }
    }
  });

  if (response.status !== 201) {
    throw new Error(response.data?.message || 'Failed to create runtime container');
  }
}

async function reconcileRuntime(server) {
  const current = await inspectRuntime();
  const nextHash = hashServerConfig(server);
  if (!current.exists) {
    await createRuntimeContainer(server);
    return { recreated: true };
  }
  if (current.labels?.['atgs.config-hash'] === nextHash) {
    return { recreated: false };
  }
  if (current.running) await dockerRequest('POST', `/containers/${RUNTIME_CONTAINER_NAME}/stop?t=15`);
  await dockerRequest('DELETE', `/containers/${RUNTIME_CONTAINER_NAME}?force=true`);
  await createRuntimeContainer(server);
  return { recreated: true };
}

function updateProperties(server) {
  const propsPath = path.join(FILES_DIR, 'server.properties');
  let content = '';
  try { content = fs.readFileSync(propsPath, 'utf8'); } catch {}
  const lines = content.split(/\r?\n/);
  const patch = new Map([
    ['server-port', '25565'],
    ['motd', server.motd],
    ['online-mode', server.onlineMode ? 'true' : 'false'],
    ['max-players', String(server.maxPlayers)],
    ['difficulty', server.difficulty],
    ['view-distance', String(server.viewDistance)],
    ['simulation-distance', String(server.simulationDistance)],
    ['white-list', server.whitelistEnabled ? 'true' : 'false'],
    ['level-name', server.levelName],
    ['enable-rcon', RCON_ENABLED ? 'true' : 'false'],
    ['rcon.port', String(server.rconPort)],
    ['rcon.password', server.rconPassword]
  ]);

  const output = lines.map((line) => {
    const idx = line.indexOf('=');
    if (idx === -1) return line;
    const key = line.slice(0, idx);
    if (!patch.has(key)) return line;
    const value = patch.get(key);
    patch.delete(key);
    return `${key}=${value}`;
  });

  for (const [key, value] of patch) output.push(`${key}=${value}`);
  fs.writeFileSync(propsPath, output.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd() + '\n');
}

function ensureRuntimeFiles(server) {
  ensureDirs();
  fs.mkdirSync(path.join(FILES_DIR, 'plugins'), { recursive: true });
  fs.writeFileSync(path.join(FILES_DIR, 'eula.txt'), 'eula=true\n');
  const runTemplate = path.join(EGGS_DIR, 'minecraft-java', 'run.sh');
  const targetStart = path.join(FILES_DIR, 'start.sh');
  if (fs.existsSync(runTemplate)) {
    fs.copyFileSync(runTemplate, targetStart);
    fs.chmodSync(targetStart, 0o755);
  }
  updateProperties(server);
}

function bootstrapRuntime(server) {
  ensureRuntimeFiles(server);
  const installScript = path.join(EGGS_DIR, 'minecraft-java', 'install.sh');
  if (!fs.existsSync(installScript)) throw new Error('Minecraft egg installer not found');
  const result = spawnSync('bash', [installScript, server.flavor, server.version, FILES_DIR], {
    cwd: FILES_DIR,
    env: { ...process.env },
    encoding: 'utf8',
    timeout: 600000
  });
  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || `Bootstrap failed with code ${result.status}`);
  }
  ensureRuntimeFiles(server);
}

function createBackup(server, addons, label) {
  ensureDirs();
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const normalizedLabel = sanitizeLabel(label);
  const fileName = `backup-${timestamp}-${normalizedLabel}.tar.gz`;
  const fullPath = path.join(BACKUPS_DIR, fileName);
  const stageDir = fs.mkdtempSync(path.join(INSTANCE_DIR, 'backup-stage-'));

  try {
    const packageFilesDir = path.join(stageDir, 'files');
    fs.cpSync(FILES_DIR, packageFilesDir, { recursive: true });
    removeEphemeralPaths(packageFilesDir);

    const serverSnapshot = buildServerSnapshot(server);
    const addonSnapshot = Array.isArray(addons) ? addons : [];
    writeJson(path.join(stageDir, 'config', 'server.json'), serverSnapshot);
    writeJson(path.join(stageDir, 'config', 'addons.json'), addonSnapshot);
    writeJson(path.join(stageDir, 'manifest.json'), buildBackupManifest(serverSnapshot, addonSnapshot, normalizedLabel, fileName));

    execFileSync('tar', ['-czf', fullPath, '-C', stageDir, '.'], { timeout: 300000 });
    return buildBackupMetadata(fileName);
  } finally {
    fs.rmSync(stageDir, { recursive: true, force: true });
  }
}

function listBackups() {
  ensureDirs();
  return fs.readdirSync(BACKUPS_DIR)
    .filter((name) => name.endsWith('.tar.gz'))
    .map((name) => buildBackupMetadata(name))
    .sort((left, right) => new Date(right.created) - new Date(left.created));
}

function switchFilesFromStage(stageDir, server) {
  const rollbackDir = path.join(INSTANCE_DIR, `rollback-${Date.now()}`);
  if (fs.existsSync(FILES_DIR)) fs.renameSync(FILES_DIR, rollbackDir);
  fs.renameSync(stageDir, FILES_DIR);
  try {
    ensureRuntimeFiles(server);
    if (fs.existsSync(rollbackDir)) fs.rmSync(rollbackDir, { recursive: true, force: true });
  } catch (error) {
    if (fs.existsSync(FILES_DIR)) fs.rmSync(FILES_DIR, { recursive: true, force: true });
    if (fs.existsSync(rollbackDir)) fs.renameSync(rollbackDir, FILES_DIR);
    throw error;
  }
}

function readJsonFile(filePath, label) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch (error) {
    throw new Error(`Invalid ${label}: ${error.message}`);
  }
}

function normalizeBackupServerConfig(value, fallback) {
  if (!value || typeof value !== 'object') return fallback;
  return {
    ...fallback,
    ...value,
    geyserEnabled: Boolean(value.geyserEnabled ?? fallback.geyserEnabled),
    sleepEnabled: Boolean(value.sleepEnabled ?? fallback.sleepEnabled),
    onlineMode: Boolean(value.onlineMode ?? fallback.onlineMode),
    whitelistEnabled: Boolean(value.whitelistEnabled ?? fallback.whitelistEnabled)
  };
}

function restoreAtgsPackageFromStage(stageDir, fallbackServer) {
  const manifestPath = path.join(stageDir, 'manifest.json');
  if (!fs.existsSync(manifestPath)) return null;

  const manifest = readJsonFile(manifestPath, 'backup manifest');
  if (manifest.format !== BACKUP_FORMAT) {
    throw new Error(`Unsupported backup format: ${manifest.format || 'unknown'}`);
  }

  const filesStageDir = path.join(stageDir, 'files');
  const serverConfigPath = path.join(stageDir, 'config', 'server.json');
  const addonsPath = path.join(stageDir, 'config', 'addons.json');

  if (!fs.existsSync(filesStageDir) || !fs.statSync(filesStageDir).isDirectory()) {
    throw new Error('ATGS backup is missing files/');
  }
  if (!fs.existsSync(serverConfigPath)) {
    throw new Error('ATGS backup is missing config/server.json');
  }

  const restoredServer = normalizeBackupServerConfig(readJsonFile(serverConfigPath, 'server config'), fallbackServer);
  const restoredAddons = fs.existsSync(addonsPath) ? readJsonFile(addonsPath, 'addons metadata') : [];
  switchFilesFromStage(filesStageDir, restoredServer);
  fs.rmSync(stageDir, { recursive: true, force: true });

  return {
    format: BACKUP_FORMAT,
    formatVersion: manifest.formatVersion || BACKUP_FORMAT_VERSION,
    manifest,
    serverConfig: restoredServer,
    addons: restoredAddons
  };
}

async function restoreBackup(server, name) {
  const backupPath = path.join(BACKUPS_DIR, path.basename(name));
  if (!fs.existsSync(backupPath)) throw new Error('Backup not found');
  const current = await inspectRuntime();
  if (current.running) await dockerRequest('POST', `/containers/${RUNTIME_CONTAINER_NAME}/stop?t=15`);
  const stageDir = path.join(INSTANCE_DIR, `restore-stage-${Date.now()}`);
  fs.mkdirSync(stageDir, { recursive: true });
  execFileSync('tar', ['-xzf', backupPath, '-C', stageDir], { timeout: 300000 });
  const packaged = restoreAtgsPackageFromStage(stageDir, server);
  if (packaged) return packaged;

  const nestedFilesDir = path.join(stageDir, 'files');
  if (fs.existsSync(nestedFilesDir) && fs.statSync(nestedFilesDir).isDirectory()) {
    switchFilesFromStage(nestedFilesDir, server);
    fs.rmSync(stageDir, { recursive: true, force: true });
    return { format: 'legacy-files', formatVersion: 0 };
  }

  switchFilesFromStage(stageDir, server);
  return { format: 'legacy-files', formatVersion: 0 };
}

async function importLegacyBackup(server, filename) {
  const archivePath = path.join(IMPORTS_DIR, path.basename(filename));
  if (!fs.existsSync(archivePath)) throw new Error('Import archive not found');
  const current = await inspectRuntime();
  if (current.running) await dockerRequest('POST', `/containers/${RUNTIME_CONTAINER_NAME}/stop?t=15`);
  const extractDir = path.join(INSTANCE_DIR, `import-stage-${Date.now()}`);
  fs.mkdirSync(extractDir, { recursive: true });
  execFileSync('tar', ['-xzf', archivePath, '-C', extractDir], { timeout: 600000 });
  const packaged = restoreAtgsPackageFromStage(extractDir, server);
  if (packaged) return packaged;

  const nestedFilesDir = path.join(extractDir, 'files');
  if (fs.existsSync(nestedFilesDir) && fs.statSync(nestedFilesDir).isDirectory()) {
    switchFilesFromStage(nestedFilesDir, server);
    fs.rmSync(extractDir, { recursive: true, force: true });
    return { format: 'legacy-files', formatVersion: 0 };
  }
  switchFilesFromStage(extractDir, server);
  return { format: 'legacy-files', formatVersion: 0 };
}

async function sendRcon(command, server) {
  if (!RCON_ENABLED) {
    throw new Error('RCON is disabled for this deployment');
  }

  let client;
  try {
    client = await Rcon.connect({
      host: RCON_HOST,
      port: server.rconPort,
      password: server.rconPassword,
      timeout: 5000
    });
    return await client.send(command);
  } finally {
    if (client) {
      try { await client.end(); } catch {}
    }
  }
}

function parsePlayersResponse(response, server) {
  const counts = response.match(/(\d+)\s+of\s+a\s+max\s+of\s+(\d+)/);
  const names = response.includes(':')
    ? response.split(':')[1].split(',').map((name) => name.trim()).filter(Boolean)
    : [];

  return {
    online: true,
    players: counts ? parseInt(counts[1], 10) : names.length,
    maxPlayers: counts ? parseInt(counts[2], 10) : server.maxPlayers,
    playerNames: names
  };
}

async function getRuntimeTelemetry(server) {
  if (!RCON_ENABLED) {
    return {
      players: { online: false, players: 0, maxPlayers: server.maxPlayers, playerNames: [] },
      tps: 'N/A',
      rconEnabled: false
    };
  }

  try {
    const [playersResponse, tpsResponse] = await Promise.all([
      sendRcon('list', server),
      sendRcon('tps', server).catch(() => 'N/A')
    ]);

    return {
      players: parsePlayersResponse(playersResponse, server),
      tps: String(tpsResponse || 'N/A').replace(/\u00A7[0-9a-fk-or]/gi, '').trim() || 'N/A',
      rconEnabled: true
    };
  } catch {
    return {
      players: { online: false, players: 0, maxPlayers: server.maxPlayers, playerNames: [] },
      tps: 'N/A',
      rconEnabled: true
    };
  }
}

const app = express();
app.use(express.json({ limit: '25mb' }));
app.use(ensureAuth);

app.get('/health', (req, res) => {
  res.json({ ok: true });
});

app.get('/runtime/status', async (req, res) => {
  res.json(await inspectRuntime());
});

app.get('/runtime/stats', async (req, res) => {
  res.json(await getRuntimeStats());
});

app.get('/runtime/logs', async (req, res) => {
  res.json(await getRuntimeLogs(parseInt(req.query.tail || '200', 10)));
});

app.post('/runtime/telemetry', async (req, res) => {
  try {
    res.json(await getRuntimeTelemetry(req.body.server || req.body));
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/runtime/command', async (req, res) => {
  try {
    if (!req.body?.command) return res.status(400).json({ error: 'Command required' });
    res.json({ response: await sendRcon(req.body.command, req.body.server || req.body) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/runtime/reconcile', async (req, res) => {
  try {
    ensureRuntimeFiles(req.body.server);
    res.json(await reconcileRuntime(req.body.server));
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/runtime/bootstrap', async (req, res) => {
  try {
    bootstrapRuntime(req.body.server);
    await reconcileRuntime(req.body.server);
    res.json({ ok: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/runtime/start', async (req, res) => {
  try {
    await dockerRequest('POST', `/containers/${RUNTIME_CONTAINER_NAME}/start`);
    res.json({ ok: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/runtime/stop', async (req, res) => {
  try {
    await dockerRequest('POST', `/containers/${RUNTIME_CONTAINER_NAME}/stop?t=20`);
    res.json({ ok: true, reason: req.body?.reason || 'manual' });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/runtime/restart', async (req, res) => {
  try {
    await dockerRequest('POST', `/containers/${RUNTIME_CONTAINER_NAME}/restart?t=20`);
    res.json({ ok: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get('/files', (req, res) => {
  try {
    const dir = safeFilePath(req.query.path || '');
    const entries = fs.readdirSync(dir, { withFileTypes: true }).map((entry) => {
      const stat = fs.statSync(path.join(dir, entry.name));
      return {
        name: entry.name,
        type: entry.isDirectory() ? 'dir' : 'file',
        size: entry.isDirectory() ? null : stat.size,
        modified: stat.mtime.toISOString(),
        path: path.relative(FILES_DIR, path.join(dir, entry.name)).replace(/\\/g, '/')
      };
    });
    res.json({ path: path.relative(FILES_DIR, dir) || '.', entries });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.get('/files/read', (req, res) => {
  try {
    const filePath = safeFilePath(req.query.path || '');
    res.json({ content: fs.readFileSync(filePath, 'utf8') });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.post('/files/write', (req, res) => {
  try {
    const filePath = safeFilePath(req.body.path || '');
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, req.body.content || '', 'utf8');
    res.json({ ok: true });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.post('/files/upload', (req, res) => {
  try {
    const filename = path.basename(req.body.filename || '');
    if (!filename) return res.status(400).json({ error: 'Filename required' });
    const dir = safeFilePath(req.body.path || '');
    const target = path.join(dir, filename);
    if (!target.startsWith(FILES_DIR)) return res.status(400).json({ error: 'Access denied' });
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, Buffer.from(req.body.contentBase64 || '', 'base64'));
    res.json({ ok: true, path: path.relative(FILES_DIR, target).replace(/\\/g, '/') });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.post('/files/mkdir', (req, res) => {
  try {
    const dir = safeFilePath(req.body.path || '');
    fs.mkdirSync(dir, { recursive: true });
    res.json({ ok: true, path: path.relative(FILES_DIR, dir).replace(/\\/g, '/') || '.' });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.post('/files/delete', (req, res) => {
  try {
    const target = safeFilePath(req.body.path || '');
    if (target === FILES_DIR) return res.status(400).json({ error: 'Cannot delete root' });
    fs.rmSync(target, { recursive: true, force: true });
    res.json({ ok: true });
  } catch (error) {
    res.status(400).json({ error: error.message });
  }
});

app.get('/backups', (req, res) => {
  try {
    res.json(listBackups());
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/backups/create', (req, res) => {
  try {
    res.json(createBackup(req.body?.server || {}, req.body?.addons || [], req.body?.label));
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/backups/restore', async (req, res) => {
  try {
    await restoreBackup(req.body.server || req.body, req.body.name);
    res.json({ ok: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/backups/import-legacy', async (req, res) => {
  try {
    await importLegacyBackup(req.body.server || req.body, req.body.filename);
    res.json({ ok: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

ensureDirs();
resolveHostDataDir().finally(() => {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`[ATGS Agent] listening on ${PORT}`);
  });
});
