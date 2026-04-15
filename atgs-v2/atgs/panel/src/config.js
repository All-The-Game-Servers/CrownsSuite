const fs = require('fs');
const path = require('path');

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

function readBool(name, fallback) {
  const value = process.env[name];
  if (value === undefined) return fallback;
  return value === 'true';
}

function readInt(name, fallback) {
  const value = process.env[name];
  if (value === undefined) return fallback;
  const parsed = parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

const dataDir = process.env.DATA_DIR || '/data';
const controlDir = process.env.CONTROL_DIR || path.join(dataDir, 'control');
const instancesDir = process.env.INSTANCES_DIR || path.join(dataDir, 'instances');
const mainInstanceId = process.env.MAIN_INSTANCE_ID || 'main';
const mainInstanceDir = path.join(instancesDir, mainInstanceId);

module.exports = {
  port: parseInt(process.env.PORT || '8080', 10),
  sessionMaxAge: 1000 * 60 * 60 * 24 * 7,
  ownerUser: 'KrispKlank',
  adminPass: readSetting('ADMIN_PASS', 'atgs'),
  dataDir,
  controlDir,
  dbDir: controlDir,
  dbPath: process.env.DB_PATH || path.join(controlDir, 'atgs.db'),
  importsDir: process.env.IMPORTS_DIR || path.join(dataDir, 'imports'),
  instancesDir,
  mainInstanceId,
  mainInstanceDir,
  serverFilesDir: path.join(mainInstanceDir, 'files'),
  backupsDir: path.join(mainInstanceDir, 'backups'),
  agentBaseUrl: process.env.AGENT_BASE_URL || 'http://agent:9393',
  agentSecret: readSetting('AGENT_SECRET', 'change-me'),
  gatewaySecret: readSetting('GATEWAY_SECRET', 'change-me-too'),
  proxySharedSecret: readSetting('PANEL_PROXY_SECRET', 'change-panel-proxy-secret'),
  panelDirectPublish: readBool('PANEL_DIRECT_PUBLISH', false),
  trustProxy: readBool('PANEL_TRUST_PROXY', true),
  authProxyEnabled: readBool('AUTH_PROXY_ENABLED', true),
  socketProxyEnabled: readBool('SOCKET_PROXY_ENABLED', true),
  secureCookies: readBool('SECURE_COOKIES', true),
  adminAllowedCidrs: String(process.env.ADMIN_ALLOWED_CIDRS || '')
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean),
  loginWindowMs: readInt('LOGIN_WINDOW_MS', 15 * 60 * 1000),
  loginMaxAttemptsIp: readInt('LOGIN_MAX_ATTEMPTS_IP', 25),
  loginMaxAttemptsUser: readInt('LOGIN_MAX_ATTEMPTS_USER', 8),
  loginBlockMs: readInt('LOGIN_BLOCK_MS', 30 * 60 * 1000),
  adminDomain: process.env.ADMIN_DOMAIN || 'panel.example.com',
  authDomain: process.env.AUTH_DOMAIN || 'auth.example.com',
  idlePollMs: parseInt(process.env.IDLE_POLL_MS || '15000', 10),
  wakePollMs: parseInt(process.env.WAKE_POLL_MS || '2000', 10),
  wakeTimeoutMs: parseInt(process.env.WAKE_TIMEOUT_MS || '45000', 10),
  rconHost: process.env.RCON_HOST || 'atgs-minecraft',
  rconEnabled: readBool('RCON_ENABLED', true),
  defaultServer: {
    id: mainInstanceId,
    name: process.env.SERVER_NAME || 'ATGS Survival',
    state: 'sleeping',
    flavor: process.env.SERVER_FLAVOR || 'purpur',
    version: process.env.SERVER_VERSION || 'latest',
    profile: process.env.PERF_PROFILE || 'balanced',
    minRam: process.env.MIN_RAM || '2G',
    maxRam: process.env.MAX_RAM || '6G',
    gamePort: parseInt(process.env.SERVER_PORT || '25565', 10),
    gatewayPort: parseInt(process.env.GATEWAY_PORT || '25577', 10),
    bedrockPort: parseInt(process.env.BEDROCK_PORT || '19132', 10),
    geyserEnabled: process.env.GEYSER_ENABLED === 'true' ? 1 : 0,
    sleepEnabled: process.env.SLEEP_ENABLED === 'false' ? 0 : 1,
    idleGraceSeconds: parseInt(process.env.IDLE_GRACE_SECONDS || '900', 10),
    motd: process.env.SERVER_MOTD || 'ATGS Worldwide',
    difficulty: process.env.SERVER_DIFFICULTY || 'normal',
    maxPlayers: parseInt(process.env.MAX_PLAYERS || '50', 10),
    onlineMode: process.env.ONLINE_MODE === 'false' ? 0 : 1,
    whitelistEnabled: process.env.WHITELIST_ENABLED === 'true' ? 1 : 0,
    viewDistance: parseInt(process.env.VIEW_DISTANCE || '8', 10),
    simulationDistance: parseInt(process.env.SIMULATION_DISTANCE || '6', 10),
    levelName: process.env.LEVEL_NAME || 'world',
    rconPort: parseInt(process.env.RCON_PORT || '25575', 10),
    rconPassword: readSetting('RCON_PASSWORD', 'atgs-rcon'),
    javaArgs: process.env.JAVA_EXTRA_FLAGS || '',
    mapUrl: process.env.MAP_URL || ''
  }
};
