const fs = require('fs');
const path = require('path');
const config = require('./config');
const db = require('./db');
const agent = require('./agent');
const modrinth = require('./modrinth');
const { serializeProperties } = require('./properties');
const { appendAudit, listAuditEntries } = require('./audit-service');
const { aggregateStats, readRules } = require('./stats-service');
const { sendNotification } = require('./notification-service');
const publicPages = require('./public-pages');

const addonCatalog = {
  geyser: { slug: 'geyser', label: 'Geyser', loader: 'paper', subDir: 'plugins' },
  floodgate: { slug: 'floodgate', label: 'Floodgate', loader: 'paper', subDir: 'plugins' }
};

const resourceThresholds = { cpu: 85, ram: 90 };
const retentionMs = 7 * 24 * 60 * 60 * 1000;
const countdownSeconds = [600, 300, 60, 30, 10];
const state = {
  backgroundStarted: false,
  pendingRestartJobs: new Map(),
  maintenanceWindows: []
};

async function ensureProperties(server) {
  let existing = '';
  try {
    existing = (await agent.readFile('server.properties')).content || '';
  } catch {}

  const content = serializeProperties(existing, {
    'server-port': '25565',
    motd: server.motd,
    'online-mode': server.onlineMode ? 'true' : 'false',
    'max-players': String(server.maxPlayers),
    difficulty: server.difficulty,
    'view-distance': String(server.viewDistance),
    'simulation-distance': String(server.simulationDistance),
    'white-list': server.whitelistEnabled ? 'true' : 'false',
    'level-name': server.levelName,
    'enable-rcon': config.rconEnabled ? 'true' : 'false',
    'rcon.port': String(server.rconPort),
    'rcon.password': server.rconPassword
  });

  await agent.writeFile('server.properties', content);
}

async function getRuntime(server) {
  const [status, stats] = await Promise.all([
    agent.runtimeStatus().catch(() => ({ exists: false, running: false, status: 'missing', health: 'unknown' })),
    agent.runtimeStats().catch(() => null)
  ]);

  const runtime = { ...status, stats };
  if (!runtime.running && ['running', 'idle', 'waking', 'stopping', 'restarting'].includes(server.state)) {
    server = db.saveServer({ state: 'sleeping' });
  } else if (runtime.running && server.state === 'waking' && runtime.health === 'healthy') {
    server = db.saveServer({ state: 'running' });
  }

  return { server, runtime };
}

function deriveAlerts(server, runtime) {
  const crash = !runtime.running && !['sleeping', 'provisioning', 'restoring', 'importing'].includes(server.state)
    ? { message: `${server.name} is not running unexpectedly.`, detectedAt: Date.now() }
    : null;
  const stats = runtime.stats || null;
  const ramPercent = stats?.memLimit ? (stats.memUsed / stats.memLimit) * 100 : 0;
  const resource = stats && (Number(stats.cpu || 0) >= resourceThresholds.cpu || ramPercent >= resourceThresholds.ram)
    ? { message: `Resource thresholds are elevated: CPU ${Number(stats.cpu || 0).toFixed(1)}%, RAM ${Math.round(ramPercent)}%.` }
    : null;
  return { crash, resource };
}

async function getServerSummary() {
  let server = db.getServer();
  const hydrated = await getRuntime(server);
  server = hydrated.server;
  const runtime = hydrated.runtime;

  let players = { online: false, players: 0, maxPlayers: server.maxPlayers, playerNames: [] };
  let tps = 'N/A';
  if (config.rconEnabled && runtime.running && runtime.health !== 'unhealthy') {
    const telemetry = await agent.runtimeTelemetry(server).catch(() => null);
    if (telemetry) {
      players = telemetry.players || players;
      tps = telemetry.tps || tps;
    }
  }

  return {
    ...server,
    runtime,
    players,
    tps,
    ready: Boolean(runtime.running && runtime.health === 'healthy'),
    alerts: deriveAlerts(server, runtime),
    maintenanceWindows: state.maintenanceWindows.slice().sort((a, b) => a.startsAt - b.startsAt),
    security: {
      adminDirectExposureDisabled: !config.panelDirectPublish,
      authProxyActive: config.authProxyEnabled,
      ipAllowlistActive: config.adminAllowedCidrs.length > 0,
      ipAllowlistRules: config.adminAllowedCidrs,
      socketProxyActive: config.socketProxyEnabled,
      agentInternalOnly: true,
      bedrockPublished: Boolean(server.geyserEnabled),
      rconInternalOnly: config.rconEnabled,
      adminDomain: config.adminDomain,
      authDomain: config.authDomain
    }
  };
}

async function snapshotPerformance() {
  const summary = await getServerSummary();
  db.insertPerformance({
    timestamp: Date.now(),
    tps: Number.parseFloat(String(summary.tps).replace(/[^0-9.-]/g, '')) || null,
    cpuPercent: summary.runtime?.stats ? Number(summary.runtime.stats.cpu || 0) : null,
    ramUsedMb: summary.runtime?.stats ? Math.round((summary.runtime.stats.memUsed || 0) / 1024 / 1024) : null,
    ramMaxMb: summary.runtime?.stats ? Math.round((summary.runtime.stats.memLimit || 0) / 1024 / 1024) : null,
    playerCount: summary.players.players || 0
  });
  db.prunePerformance(Date.now() - retentionMs);
}

function getPerformance(range = '1h') {
  const windows = { '1h': 3600000, '6h': 21600000, '24h': 86400000, '7d': 604800000 };
  const since = Date.now() - (windows[range] || windows['1h']);
  return db.listPerformance(since).map((entry) => ({
    ...entry,
    label: new Date(entry.timestamp).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
  }));
}

async function reconcileRuntime() {
  const server = db.getServer();
  await ensureProperties(server);
  return agent.reconcileRuntime(server);
}

async function bootstrapServer({ flavor, version }, actor = 'system') {
  const current = db.getServer();
  const updated = db.saveServer({ flavor: flavor || current.flavor, version: version || current.version, state: 'provisioning' });
  await agent.bootstrapRuntime(updated);
  await ensureProperties(updated);
  db.saveServer({ state: 'sleeping' });
  appendAudit({ action: 'server.bootstrap', user: actor, detail: `${updated.flavor} ${updated.version}` });
  return getServerSummary();
}

async function waitForReady(timeoutMs = config.wakeTimeoutMs) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const summary = await getServerSummary();
    if (summary.ready) return summary;
    await new Promise((resolve) => setTimeout(resolve, config.wakePollMs));
  }
  return getServerSummary();
}

async function wakeServer(reason = 'manual', actor = 'system') {
  await reconcileRuntime();
  db.saveServer({ state: 'waking' });
  await agent.startRuntime();
  const summary = await waitForReady();
  if (summary.ready) {
    db.saveServer({ state: 'running', lastEmptyAt: null });
    const notifications = db.getNotificationConfig();
    if (notifications.notifyStart) {
      await sendNotification('Server Started', `${summary.name} is online.`, 'success', { Server: summary.name, Reason: reason });
    }
  }
  appendAudit({ action: 'server.wake', user: actor, detail: reason });
  return { ...(await getServerSummary()), wakeReason: reason };
}

async function sleepServer(reason = 'manual', actor = 'system') {
  try { await sendConsoleCommand('save-all flush', actor); } catch {}
  db.saveServer({ state: 'stopping' });
  await agent.stopRuntime(reason);
  db.saveServer({ state: 'sleeping', lastEmptyAt: null });
  appendAudit({ action: 'server.sleep', user: actor, detail: reason });
  return getServerSummary();
}

async function restartServer(actor = 'system') {
  db.saveServer({ state: 'restarting' });
  await agent.restartRuntime();
  const summary = await waitForReady();
  if (summary.ready) db.saveServer({ state: 'running' });
  const notifications = db.getNotificationConfig();
  if (notifications.notifyRestart) {
    await sendNotification('Server Restarted', `${summary.name} finished restarting.`, 'info', { Server: summary.name });
  }
  appendAudit({ action: 'server.restart', user: actor, detail: 'Immediate restart' });
  return getServerSummary();
}

async function updateServerSettings(patch, actor = 'system') {
  const current = db.getServer();
  const next = db.saveServer({ ...current, ...patch });
  await ensureProperties(next);
  await reconcileRuntime();
  appendAudit({ action: 'server.settings.update', user: actor, detail: `Updated settings for ${next.name}` });
  return getServerSummary();
}

function getServerConfig() {
  return db.getServer();
}

async function updateServerConfig(patch, actor = 'system') {
  const next = db.saveServer({ ...patch });
  await ensureProperties(next);
  appendAudit({ action: 'server.config.update', user: actor, detail: `Updated config for ${next.name}` });
  return getServerConfig();
}

async function createBackup(label, actor = 'system') {
  const backup = await agent.createBackup(db.getServer(), listAddons(), label || 'manual');
  if (db.getNotificationConfig().notifyBackup) {
    await sendNotification('Backup Completed', `ATGS created backup ${backup.name}.`, 'success', { Backup: backup.name });
  }
  appendAudit({ action: 'backup.create', user: actor, detail: backup.name });
  return backup;
}

async function restoreBackup(name, actor = 'system') {
  const server = db.saveServer({ state: 'restoring' });
  const result = await agent.restoreBackup(server, name);
  if (result?.serverConfig) db.saveServer({ ...result.serverConfig, state: 'sleeping', lastEmptyAt: null });
  await ensureProperties(db.getServer());
  db.saveServer({ state: 'sleeping' });
  appendAudit({ action: 'backup.restore', user: actor, detail: name });
  return { ...(await getServerSummary()), restore: result || { format: 'legacy' } };
}

async function importLegacyBackup(filename, actor = 'system') {
  const server = db.saveServer({ state: 'importing' });
  const result = await agent.importLegacyBackup(server, filename);
  if (result?.serverConfig) db.saveServer({ ...result.serverConfig, state: 'sleeping', lastEmptyAt: null });
  await ensureProperties(db.getServer());
  db.saveServer({ state: 'sleeping' });
  appendAudit({ action: 'backup.import', user: actor, detail: filename });
  return { ...(await getServerSummary()), restore: result || { format: 'legacy-files' } };
}

async function sendConsoleCommand(command, actor = 'system') {
  if (!config.rconEnabled) throw new Error('RCON-backed server commands are disabled for this deployment');
  const response = await agent.runtimeCommand(db.getServer(), command);
  appendAudit({ action: 'console.command', user: actor, detail: command });
  return response.response;
}

function listAddons() {
  const pluginsDir = path.join(config.serverFilesDir, 'plugins');
  return fs.existsSync(pluginsDir) ? modrinth.listInstalled(config.serverFilesDir, 'plugins') : [];
}

function installAddon(addonId, actor = 'system') {
  const addon = addonCatalog[addonId];
  if (!addon) throw new Error('Unknown addon');
  const server = db.getServer();
  const result = modrinth.installModWithDeps(addon.slug, config.serverFilesDir, {
    loader: addon.loader,
    gameVersion: server.version === 'latest' ? '' : server.version,
    subDir: addon.subDir
  });
  appendAudit({ action: 'addon.install', user: actor, detail: addon.label });
  return result;
}

function searchMods(query, options = {}) {
  return modrinth.search(query, { loader: options.loader || 'fabric', gameVersion: options.version || '', projectType: options.projectType || 'mod' });
}

function installMod(slug, options = {}, actor = 'system') {
  const result = modrinth.installModWithDeps(slug, config.serverFilesDir, {
    loader: options.loader || 'fabric',
    gameVersion: options.gameVersion || db.getServer().version,
    subDir: options.loader === 'paper' ? 'plugins' : 'mods'
  });
  appendAudit({ action: 'mod.install', user: actor, detail: slug });
  return result;
}

function listInstalledMods() {
  return [
    ...modrinth.listInstalled(config.serverFilesDir, 'mods').map((entry) => ({ ...entry, location: 'mods', kind: 'mod' })),
    ...modrinth.listInstalled(config.serverFilesDir, 'plugins').map((entry) => ({ ...entry, location: 'plugins', kind: 'plugin' }))
  ].sort((left, right) => left.filename.localeCompare(right.filename));
}

function deleteInstalledMod(filename, actor = 'system') {
  for (const location of ['mods', 'plugins']) {
    const target = path.join(config.serverFilesDir, location, path.basename(filename));
    if (fs.existsSync(target)) {
      fs.unlinkSync(target);
      appendAudit({ action: 'mod.delete', user: actor, detail: `${location}/${path.basename(filename)}` });
      return { ok: true, location };
    }
  }
  throw new Error('Installed file not found');
}

async function listFiles(relPath = '') { return agent.listFiles(relPath); }
async function readFile(relPath) { return agent.readFile(relPath); }
async function writeFile(relPath, content, actor = 'system') { const result = await agent.writeFile(relPath, content); appendAudit({ action: 'files.write', user: actor, detail: relPath }); return result; }
async function uploadFile(relPath, filename, buffer, actor = 'system') { const result = await agent.uploadFile(relPath, filename, buffer.toString('base64')); appendAudit({ action: 'files.upload', user: actor, detail: path.posix.join(relPath || '.', filename) }); return result; }
async function mkdir(relPath, actor = 'system') { const result = await agent.mkdir(relPath); appendAudit({ action: 'files.mkdir', user: actor, detail: relPath }); return result; }
async function deletePath(relPath, actor = 'system') { const result = await agent.deletePath(relPath); appendAudit({ action: 'files.delete', user: actor, detail: relPath }); return result; }

function nextRun(type, time, days = []) {
  const [hour, minute] = String(time).split(':').map((value) => parseInt(value, 10));
  const now = new Date();
  for (let offset = 0; offset < 14; offset += 1) {
    const candidate = new Date(now);
    candidate.setDate(now.getDate() + offset);
    candidate.setHours(hour || 0, minute || 0, 0, 0);
    if (type === 'weekly' && days.length && !days.map(Number).includes(candidate.getDay())) continue;
    if (candidate.getTime() > now.getTime()) return candidate.getTime();
  }
  return now.getTime() + 86400000;
}

function listScheduledRestarts() { return db.listScheduledRestarts(); }
function createScheduledRestart(schedule, actor = 'system') { const record = db.createScheduledRestart({ type: schedule.type, time: schedule.time, days: schedule.days || [], nextRunAt: nextRun(schedule.type, schedule.time, schedule.days || []) }); appendAudit({ action: 'restart.schedule.create', user: actor, detail: `${record.type} ${record.time}` }); return record; }
function deleteScheduledRestart(id, actor = 'system') { db.deleteScheduledRestart(id); state.pendingRestartJobs.delete(Number(id)); appendAudit({ action: 'restart.schedule.delete', user: actor, detail: String(id) }); }
function listAnnouncements(options = {}) { return db.listAnnouncements(options); }
function createAnnouncement(payload, actor = 'system') { const item = db.createAnnouncement({ title: payload.title, message: payload.message, type: payload.type || 'info', broadcastAt: payload.broadcastAt || payload.broadcast_at || null, active: payload.active !== false }); appendAudit({ action: 'announcement.create', user: actor, detail: item.title }); return item; }
function updateAnnouncement(id, payload, actor = 'system') { const item = db.updateAnnouncement(Number(id), { title: payload.title, message: payload.message, type: payload.type, broadcastAt: payload.broadcastAt !== undefined ? payload.broadcastAt : payload.broadcast_at, active: payload.active, broadcastSentAt: payload.broadcastSentAt }); appendAudit({ action: 'announcement.update', user: actor, detail: String(id) }); return item; }
function deleteAnnouncement(id, actor = 'system') { db.deleteAnnouncement(Number(id)); appendAudit({ action: 'announcement.delete', user: actor, detail: String(id) }); }
async function broadcastAnnouncement(id, actor = 'system') { const item = db.getAnnouncement(Number(id)); if (!item) throw new Error('Announcement not found'); await sendConsoleCommand(`say [${item.type.toUpperCase()}] ${item.title}: ${item.message}`, actor); db.markAnnouncementBroadcastSent(Number(id), Date.now()); appendAudit({ action: 'announcement.broadcast', user: actor, detail: item.title }); return item; }
function getNotificationConfig() { const cfg = db.getNotificationConfig(); return { ...cfg, vapidPrivateKey: cfg.vapidPrivateKey ? 'configured' : '' }; }
function updateNotificationConfig(payload, actor = 'system') { const result = db.updateNotificationConfig({ discordWebhookUrl: payload.discord_webhook_url !== undefined ? payload.discord_webhook_url : payload.discordWebhookUrl, notifyCrash: payload.notify_crash !== undefined ? payload.notify_crash : payload.notifyCrash, notifyStart: payload.notify_start !== undefined ? payload.notify_start : payload.notifyStart, notifyBackup: payload.notify_backup !== undefined ? payload.notify_backup : payload.notifyBackup, notifyRestart: payload.notify_restart !== undefined ? payload.notify_restart : payload.notifyRestart, vapidPublicKey: payload.vapid_public_key !== undefined ? payload.vapid_public_key : payload.vapidPublicKey, vapidPrivateKey: payload.vapid_private_key !== undefined ? payload.vapid_private_key : payload.vapidPrivateKey, vapidSubject: payload.vapid_subject !== undefined ? payload.vapid_subject : payload.vapidSubject }); appendAudit({ action: 'notifications.config.update', user: actor, detail: 'Updated notification settings' }); return { ...result, vapidPrivateKey: result.vapidPrivateKey ? 'configured' : '' }; }
function savePushSubscription(subscription, actor = 'system') { const saved = db.savePushSubscription(subscription); appendAudit({ action: 'notifications.subscribe', user: actor, detail: saved.endpoint }); return saved; }
function deletePushSubscription(endpoint, actor = 'system') { db.deletePushSubscription(endpoint); appendAudit({ action: 'notifications.unsubscribe', user: actor, detail: endpoint }); }
function listNodes() { return []; }
async function getPublicStatus() { const summary = await getServerSummary(); return { name: summary.name, state: summary.state, ready: summary.ready, motd: summary.motd, players: summary.players, maxPlayers: summary.maxPlayers, announcements: db.listAnnouncements({ includeInactive: false }) }; }
async function renderStatusPage() { return publicPages.renderStatusPage(await getPublicStatus()); }
async function renderPlayersPage() { return publicPages.renderPlayersPage(await getPublicStatus()); }
async function renderStatsPage() { return publicPages.renderStatsPage(aggregateStats()); }
async function renderRulesPage() { return publicPages.renderRulesPage(readRules()); }
function getPublicStats() { return aggregateStats(); }
async function whitelistAction(action, player, actor = 'system') { if (action === 'list') return { output: await sendConsoleCommand('whitelist list', actor) }; if (!player) throw new Error('Player is required'); const response = await sendConsoleCommand(`whitelist ${action === 'add' ? 'add' : 'remove'} ${player}`, actor); appendAudit({ action: `whitelist.${action}`, user: actor, detail: player }); return { output: response }; }
async function kickPlayer(player, reason = '', actor = 'system') { const response = await sendConsoleCommand(`kick ${player}${reason ? ` ${reason}` : ''}`, actor); appendAudit({ action: 'player.kick', user: actor, detail: `${player}${reason ? ` (${reason})` : ''}` }); return { output: response }; }
async function banPlayer(player, reason = '', actor = 'system') { const response = await sendConsoleCommand(`ban ${player}${reason ? ` ${reason}` : ''}`, actor); appendAudit({ action: 'player.ban', user: actor, detail: `${player}${reason ? ` (${reason})` : ''}` }); return { output: response }; }
async function pardonPlayer(player, actor = 'system') { const response = await sendConsoleCommand(`pardon ${player}`, actor); appendAudit({ action: 'player.pardon', user: actor, detail: player }); return { output: response }; }
function scheduleMaintenanceWindow(minutes, actor = 'system') { const startsAt = Date.now() + Math.max(1, Number(minutes) || 1) * 60000; const entry = { id: Date.now(), startsAt, minutes: Number(minutes) || 1 }; state.maintenanceWindows.push(entry); countdownSeconds.forEach((seconds) => { const delayMs = startsAt - Date.now() - seconds * 1000; if (delayMs <= 0) return; setTimeout(() => { sendConsoleCommand(`say Maintenance restart in ${seconds >= 60 ? `${Math.round(seconds / 60)} minute${seconds === 60 ? '' : 's'}` : `${seconds} seconds`}.`, actor).catch(() => {}); }, delayMs); }); setTimeout(() => { state.maintenanceWindows = state.maintenanceWindows.filter((window) => window.id !== entry.id); restartServer(actor).catch(() => {}); }, startsAt - Date.now()); appendAudit({ action: 'maintenance.schedule', user: actor, detail: `${minutes} minutes` }); return entry; }
function cancelMaintenanceWindow(id, actor = 'system') { state.maintenanceWindows = state.maintenanceWindows.filter((entry) => String(entry.id) !== String(id)); appendAudit({ action: 'maintenance.cancel', user: actor, detail: String(id) }); return { ok: true }; }
function processDueAnnouncements() { return Promise.all(db.listDueAnnouncements(Date.now()).map(async (item) => { try { await sendConsoleCommand(`say [${item.type.toUpperCase()}] ${item.title}: ${item.message}`, 'system'); db.markAnnouncementBroadcastSent(item.id, Date.now()); } catch {} })); }
function scheduleRestartCountdown(record) { if (state.pendingRestartJobs.has(record.id)) return; const jobs = []; countdownSeconds.forEach((seconds) => { const delayMs = record.nextRunAt - Date.now() - seconds * 1000; if (delayMs <= 0) return; jobs.push(setTimeout(() => { sendConsoleCommand(`say Scheduled restart in ${seconds >= 60 ? `${Math.round(seconds / 60)} minute${seconds === 60 ? '' : 's'}` : `${seconds} seconds`}.`, 'system').catch(() => {}); }, delayMs)); }); jobs.push(setTimeout(async () => { try { await sendNotification('Scheduled Restart Beginning', `${db.getServer().name} is performing a scheduled restart.`, 'warning', { Server: db.getServer().name }); await restartServer('system'); db.updateScheduledRestartNextRun(record.id, nextRun(record.type, record.time, record.days)); } finally { state.pendingRestartJobs.delete(record.id); } }, Math.max(1, record.nextRunAt - Date.now()))); state.pendingRestartJobs.set(record.id, jobs); }
function processScheduledRestarts() { db.listScheduledRestarts().forEach((record) => { if (record.nextRunAt <= Date.now() + 11 * 60000) scheduleRestartCountdown(record); }); }
function startBackgroundTasks() { if (state.backgroundStarted) return; state.backgroundStarted = true; snapshotPerformance().catch(() => {}); setInterval(() => { snapshotPerformance().catch(() => {}); processDueAnnouncements().catch(() => {}); processScheduledRestarts(); state.maintenanceWindows = state.maintenanceWindows.filter((entry) => entry.startsAt > Date.now()); }, 30000); }

module.exports = {
  appendAudit,
  getAuditEntries: listAuditEntries,
  getServerSummary,
  getServerConfig,
  updateServerConfig,
  reconcileRuntime,
  bootstrapServer,
  wakeServer,
  sleepServer,
  restartServer,
  updateServerSettings,
  createBackup,
  restoreBackup,
  importLegacyBackup,
  sendConsoleCommand,
  listAddons,
  installAddon,
  searchMods,
  installMod,
  listInstalledMods,
  deleteInstalledMod,
  listFiles,
  readFile,
  writeFile,
  uploadFile,
  mkdir,
  deletePath,
  getPerformance,
  listScheduledRestarts,
  createScheduledRestart,
  deleteScheduledRestart,
  scheduleMaintenanceWindow,
  cancelMaintenanceWindow,
  listAnnouncements,
  createAnnouncement,
  updateAnnouncement,
  deleteAnnouncement,
  broadcastAnnouncement,
  getNotificationConfig,
  updateNotificationConfig,
  savePushSubscription,
  deletePushSubscription,
  listNodes,
  getPublicStatus,
  renderStatusPage,
  renderPlayersPage,
  renderStatsPage,
  renderRulesPage,
  getPublicStats,
  whitelistAction,
  kickPlayer,
  banPlayer,
  pardonPlayer,
  startBackgroundTasks
};
