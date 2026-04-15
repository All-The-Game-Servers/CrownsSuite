const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const Database = require('better-sqlite3');
const config = require('./config');

fs.mkdirSync(path.dirname(config.dbPath), { recursive: true });

const db = new Database(config.dbPath);
db.pragma('journal_mode = WAL');

db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    role TEXT NOT NULL,
    created_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS server_config (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    state TEXT NOT NULL,
    flavor TEXT NOT NULL,
    version TEXT NOT NULL,
    profile TEXT NOT NULL,
    min_ram TEXT NOT NULL,
    max_ram TEXT NOT NULL,
    game_port INTEGER NOT NULL,
    gateway_port INTEGER NOT NULL,
    bedrock_port INTEGER NOT NULL,
    geyser_enabled INTEGER NOT NULL,
    sleep_enabled INTEGER NOT NULL,
    idle_grace_seconds INTEGER NOT NULL,
    motd TEXT NOT NULL,
    difficulty TEXT NOT NULL,
    max_players INTEGER NOT NULL,
    online_mode INTEGER NOT NULL,
    whitelist_enabled INTEGER NOT NULL,
    view_distance INTEGER NOT NULL,
    simulation_distance INTEGER NOT NULL,
    level_name TEXT NOT NULL,
    rcon_port INTEGER NOT NULL,
    rcon_password TEXT NOT NULL,
    java_args TEXT NOT NULL,
    last_empty_at TEXT,
    map_url TEXT DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS performance_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    tps REAL,
    cpu_percent REAL,
    ram_used_mb INTEGER,
    ram_max_mb INTEGER,
    player_count INTEGER
  );

  CREATE TABLE IF NOT EXISTS scheduled_restarts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT NOT NULL,
    time TEXT NOT NULL,
    days TEXT,
    next_run_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS announcements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    type TEXT DEFAULT 'info',
    broadcast_at INTEGER,
    broadcast_sent_at INTEGER,
    created_at INTEGER NOT NULL,
    active INTEGER DEFAULT 1
  );

  CREATE TABLE IF NOT EXISTS notification_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    discord_webhook_url TEXT DEFAULT '',
    notify_crash INTEGER DEFAULT 1,
    notify_start INTEGER DEFAULT 1,
    notify_backup INTEGER DEFAULT 1,
    notify_restart INTEGER DEFAULT 1,
    vapid_public_key TEXT DEFAULT '',
    vapid_private_key TEXT DEFAULT '',
    vapid_subject TEXT DEFAULT ''
  );

  CREATE TABLE IF NOT EXISTS push_subscriptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    endpoint TEXT NOT NULL UNIQUE,
    subscription_json TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
`);

function ensureColumn(tableName, columnName, definition) {
  const columns = db.prepare(`PRAGMA table_info(${tableName})`).all();
  if (!columns.some((column) => column.name === columnName)) {
    db.exec(`ALTER TABLE ${tableName} ADD COLUMN ${columnName} ${definition}`);
  }
}

ensureColumn('server_config', 'map_url', "TEXT DEFAULT ''");

function hashPass(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  return `${salt}:${crypto.scryptSync(password, salt, 64).toString('hex')}`;
}

function verifyPass(password, stored) {
  const [salt, hash] = String(stored || '').split(':');
  if (!salt || !hash) return false;
  return crypto.scryptSync(password, salt, 64).toString('hex') === hash;
}

function mapUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    username: row.username,
    password: row.password,
    role: row.role,
    createdAt: row.created_at
  };
}

function mapServer(row) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name,
    state: row.state,
    flavor: row.flavor,
    version: row.version,
    profile: row.profile,
    minRam: row.min_ram,
    maxRam: row.max_ram,
    gamePort: row.game_port,
    gatewayPort: row.gateway_port,
    bedrockPort: row.bedrock_port,
    geyserEnabled: Boolean(row.geyser_enabled),
    sleepEnabled: Boolean(row.sleep_enabled),
    idleGraceSeconds: row.idle_grace_seconds,
    motd: row.motd,
    difficulty: row.difficulty,
    maxPlayers: row.max_players,
    onlineMode: Boolean(row.online_mode),
    whitelistEnabled: Boolean(row.whitelist_enabled),
    viewDistance: row.view_distance,
    simulationDistance: row.simulation_distance,
    levelName: row.level_name,
    rconPort: row.rcon_port,
    rconPassword: row.rcon_password,
    javaArgs: row.java_args,
    lastEmptyAt: row.last_empty_at,
    mapUrl: row.map_url || '',
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

function mapPerformance(row) {
  if (!row) return null;
  return {
    id: row.id,
    timestamp: row.timestamp,
    tps: row.tps,
    cpuPercent: row.cpu_percent,
    ramUsedMb: row.ram_used_mb,
    ramMaxMb: row.ram_max_mb,
    playerCount: row.player_count
  };
}

function mapScheduledRestart(row) {
  if (!row) return null;
  return {
    id: row.id,
    type: row.type,
    time: row.time,
    days: row.days ? JSON.parse(row.days) : [],
    nextRunAt: row.next_run_at,
    createdAt: row.created_at
  };
}

function mapAnnouncement(row) {
  if (!row) return null;
  return {
    id: row.id,
    title: row.title,
    message: row.message,
    type: row.type,
    broadcastAt: row.broadcast_at,
    broadcastSentAt: row.broadcast_sent_at,
    createdAt: row.created_at,
    active: Boolean(row.active)
  };
}

function mapNotificationConfig(row) {
  if (!row) {
    return {
      discordWebhookUrl: '',
      notifyCrash: true,
      notifyStart: true,
      notifyBackup: true,
      notifyRestart: true,
      vapidPublicKey: '',
      vapidPrivateKey: '',
      vapidSubject: ''
    };
  }
  return {
    discordWebhookUrl: row.discord_webhook_url || '',
    notifyCrash: Boolean(row.notify_crash),
    notifyStart: Boolean(row.notify_start),
    notifyBackup: Boolean(row.notify_backup),
    notifyRestart: Boolean(row.notify_restart),
    vapidPublicKey: row.vapid_public_key || '',
    vapidPrivateKey: row.vapid_private_key || '',
    vapidSubject: row.vapid_subject || ''
  };
}

function mapPushSubscription(row) {
  if (!row) return null;
  return {
    id: row.id,
    endpoint: row.endpoint,
    subscription: JSON.parse(row.subscription_json),
    createdAt: row.created_at
  };
}

const insertUser = db.prepare(`
  INSERT INTO users (id, username, password, role, created_at)
  VALUES (@id, @username, @password, @role, @created_at)
`);

const updateUser = db.prepare(`
  UPDATE users
  SET username = @username,
      password = COALESCE(@password, password),
      role = @role
  WHERE id = @id
`);

const moveUserId = db.prepare(`
  UPDATE users
  SET id = @nextId,
      username = @username,
      role = @role
  WHERE id = @currentId
`);

const upsertServer = db.prepare(`
  INSERT INTO server_config (
    id, name, state, flavor, version, profile, min_ram, max_ram, game_port,
    gateway_port, bedrock_port, geyser_enabled, sleep_enabled, idle_grace_seconds,
    motd, difficulty, max_players, online_mode, whitelist_enabled, view_distance,
    simulation_distance, level_name, rcon_port, rcon_password, java_args,
    last_empty_at, map_url, created_at, updated_at
  ) VALUES (
    @id, @name, @state, @flavor, @version, @profile, @minRam, @maxRam, @gamePort,
    @gatewayPort, @bedrockPort, @geyserEnabled, @sleepEnabled, @idleGraceSeconds,
    @motd, @difficulty, @maxPlayers, @onlineMode, @whitelistEnabled, @viewDistance,
    @simulationDistance, @levelName, @rconPort, @rconPassword, @javaArgs,
    @lastEmptyAt, @mapUrl, @createdAt, @updatedAt
  )
  ON CONFLICT(id) DO UPDATE SET
    name=excluded.name,
    state=excluded.state,
    flavor=excluded.flavor,
    version=excluded.version,
    profile=excluded.profile,
    min_ram=excluded.min_ram,
    max_ram=excluded.max_ram,
    game_port=excluded.game_port,
    gateway_port=excluded.gateway_port,
    bedrock_port=excluded.bedrock_port,
    geyser_enabled=excluded.geyser_enabled,
    sleep_enabled=excluded.sleep_enabled,
    idle_grace_seconds=excluded.idle_grace_seconds,
    motd=excluded.motd,
    difficulty=excluded.difficulty,
    max_players=excluded.max_players,
    online_mode=excluded.online_mode,
    whitelist_enabled=excluded.whitelist_enabled,
    view_distance=excluded.view_distance,
    simulation_distance=excluded.simulation_distance,
    level_name=excluded.level_name,
    rcon_port=excluded.rcon_port,
    rcon_password=excluded.rcon_password,
    java_args=excluded.java_args,
    last_empty_at=excluded.last_empty_at,
    map_url=excluded.map_url,
    updated_at=excluded.updated_at
`);

function ensureDefaults() {
  const ownerById = db.prepare('SELECT * FROM users WHERE id = ?').get('owner');
  const ownerByUsername = db.prepare('SELECT * FROM users WHERE username = ?').get(config.ownerUser);

  if (!ownerById && !ownerByUsername) {
    insertUser.run({
      id: 'owner',
      username: config.ownerUser,
      password: hashPass(config.adminPass),
      role: 'owner',
      created_at: new Date().toISOString()
    });
  } else if (ownerById) {
    updateUser.run({
      id: 'owner',
      username: config.ownerUser,
      password: null,
      role: 'owner'
    });
  } else if (ownerByUsername && ownerByUsername.id !== 'owner') {
    moveUserId.run({
      currentId: ownerByUsername.id,
      nextId: 'owner',
      username: config.ownerUser,
      role: 'owner'
    });
  }

  const current = db.prepare('SELECT * FROM server_config WHERE id = ?').get(config.defaultServer.id);
  if (!current) {
    const now = new Date().toISOString();
    upsertServer.run({
      ...config.defaultServer,
      geyserEnabled: config.defaultServer.geyserEnabled ? 1 : 0,
      sleepEnabled: config.defaultServer.sleepEnabled ? 1 : 0,
      onlineMode: config.defaultServer.onlineMode ? 1 : 0,
      whitelistEnabled: config.defaultServer.whitelistEnabled ? 1 : 0,
      lastEmptyAt: null,
      mapUrl: config.defaultServer.mapUrl || '',
      createdAt: now,
      updatedAt: now
    });
  }

  db.prepare(`
    INSERT INTO notification_config (
      id, discord_webhook_url, notify_crash, notify_start, notify_backup, notify_restart, vapid_public_key, vapid_private_key, vapid_subject
    ) VALUES (1, '', 1, 1, 1, 1, '', '', '')
    ON CONFLICT(id) DO NOTHING
  `).run();
}

ensureDefaults();

function getUserByUsername(username) {
  return mapUser(db.prepare('SELECT * FROM users WHERE username = ?').get(username));
}

function getUserById(id) {
  return mapUser(db.prepare('SELECT * FROM users WHERE id = ?').get(id));
}

function listUsers() {
  return db.prepare('SELECT * FROM users ORDER BY created_at ASC').all().map(mapUser);
}

function createUser({ id, username, password, role }) {
  const row = {
    id,
    username,
    password,
    role,
    created_at: new Date().toISOString()
  };
  insertUser.run(row);
  return getUserById(id);
}

function resetOwnerPasswordFromSecret() {
  updateUser.run({
    id: 'owner',
    username: config.ownerUser,
    password: hashPass(config.adminPass),
    role: 'owner'
  });
  return getUserById('owner');
}

function deleteUser(id) {
  db.prepare('DELETE FROM users WHERE id = ?').run(id);
}

function getServer() {
  return mapServer(db.prepare('SELECT * FROM server_config WHERE id = ?').get(config.defaultServer.id));
}

function saveServer(server) {
  const current = getServer();
  const now = new Date().toISOString();
  const payload = {
    ...current,
    ...server,
    geyserEnabled: server.geyserEnabled !== undefined ? (server.geyserEnabled ? 1 : 0) : (current.geyserEnabled ? 1 : 0),
    sleepEnabled: server.sleepEnabled !== undefined ? (server.sleepEnabled ? 1 : 0) : (current.sleepEnabled ? 1 : 0),
    onlineMode: server.onlineMode !== undefined ? (server.onlineMode ? 1 : 0) : (current.onlineMode ? 1 : 0),
    whitelistEnabled: server.whitelistEnabled !== undefined ? (server.whitelistEnabled ? 1 : 0) : (current.whitelistEnabled ? 1 : 0),
    createdAt: current.createdAt,
    updatedAt: now,
    mapUrl: server.mapUrl !== undefined ? server.mapUrl : current.mapUrl
  };
  upsertServer.run(payload);
  return getServer();
}

function insertPerformance(sample) {
  db.prepare(`
    INSERT INTO performance_log (timestamp, tps, cpu_percent, ram_used_mb, ram_max_mb, player_count)
    VALUES (@timestamp, @tps, @cpuPercent, @ramUsedMb, @ramMaxMb, @playerCount)
  `).run(sample);
}

function prunePerformance(olderThanTimestamp) {
  db.prepare('DELETE FROM performance_log WHERE timestamp < ?').run(olderThanTimestamp);
}

function listPerformance(sinceTimestamp) {
  return db.prepare(`
    SELECT * FROM performance_log
    WHERE timestamp >= ?
    ORDER BY timestamp ASC
  `).all(sinceTimestamp).map(mapPerformance);
}

function listScheduledRestarts() {
  return db.prepare('SELECT * FROM scheduled_restarts ORDER BY next_run_at ASC').all().map(mapScheduledRestart);
}

function createScheduledRestart({ type, time, days, nextRunAt }) {
  const createdAt = Date.now();
  const result = db.prepare(`
    INSERT INTO scheduled_restarts (type, time, days, next_run_at, created_at)
    VALUES (?, ?, ?, ?, ?)
  `).run(type, time, Array.isArray(days) ? JSON.stringify(days) : null, nextRunAt, createdAt);
  return mapScheduledRestart(db.prepare('SELECT * FROM scheduled_restarts WHERE id = ?').get(result.lastInsertRowid));
}

function deleteScheduledRestart(id) {
  db.prepare('DELETE FROM scheduled_restarts WHERE id = ?').run(id);
}

function updateScheduledRestartNextRun(id, nextRunAt) {
  db.prepare('UPDATE scheduled_restarts SET next_run_at = ? WHERE id = ?').run(nextRunAt, id);
}

function listAnnouncements({ includeInactive = true } = {}) {
  const query = includeInactive
    ? 'SELECT * FROM announcements ORDER BY created_at DESC'
    : 'SELECT * FROM announcements WHERE active = 1 ORDER BY created_at DESC';
  return db.prepare(query).all().map(mapAnnouncement);
}

function getAnnouncement(id) {
  return mapAnnouncement(db.prepare('SELECT * FROM announcements WHERE id = ?').get(id));
}

function createAnnouncement({ title, message, type, broadcastAt, active = true }) {
  const createdAt = Date.now();
  const result = db.prepare(`
    INSERT INTO announcements (title, message, type, broadcast_at, broadcast_sent_at, created_at, active)
    VALUES (?, ?, ?, ?, NULL, ?, ?)
  `).run(title, message, type || 'info', broadcastAt || null, createdAt, active ? 1 : 0);
  return getAnnouncement(result.lastInsertRowid);
}

function updateAnnouncement(id, patch) {
  const current = getAnnouncement(id);
  if (!current) return null;
  db.prepare(`
    UPDATE announcements
    SET title = ?,
        message = ?,
        type = ?,
        broadcast_at = ?,
        active = ?,
        broadcast_sent_at = ?
    WHERE id = ?
  `).run(
    patch.title !== undefined ? patch.title : current.title,
    patch.message !== undefined ? patch.message : current.message,
    patch.type !== undefined ? patch.type : current.type,
    patch.broadcastAt !== undefined ? patch.broadcastAt : current.broadcastAt,
    patch.active !== undefined ? (patch.active ? 1 : 0) : (current.active ? 1 : 0),
    patch.broadcastSentAt !== undefined ? patch.broadcastSentAt : current.broadcastSentAt,
    id
  );
  return getAnnouncement(id);
}

function deleteAnnouncement(id) {
  db.prepare('DELETE FROM announcements WHERE id = ?').run(id);
}

function listDueAnnouncements(nowTimestamp) {
  return db.prepare(`
    SELECT * FROM announcements
    WHERE active = 1
      AND broadcast_at IS NOT NULL
      AND broadcast_at <= ?
      AND broadcast_sent_at IS NULL
    ORDER BY broadcast_at ASC
  `).all(nowTimestamp).map(mapAnnouncement);
}

function markAnnouncementBroadcastSent(id, timestamp) {
  db.prepare('UPDATE announcements SET broadcast_sent_at = ? WHERE id = ?').run(timestamp, id);
}

function getNotificationConfig() {
  return mapNotificationConfig(db.prepare('SELECT * FROM notification_config WHERE id = 1').get());
}

function updateNotificationConfig(patch) {
  const current = getNotificationConfig();
  db.prepare(`
    INSERT INTO notification_config (
      id, discord_webhook_url, notify_crash, notify_start, notify_backup, notify_restart, vapid_public_key, vapid_private_key, vapid_subject
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      discord_webhook_url = excluded.discord_webhook_url,
      notify_crash = excluded.notify_crash,
      notify_start = excluded.notify_start,
      notify_backup = excluded.notify_backup,
      notify_restart = excluded.notify_restart,
      vapid_public_key = excluded.vapid_public_key,
      vapid_private_key = excluded.vapid_private_key,
      vapid_subject = excluded.vapid_subject
  `).run(
    1,
    patch.discordWebhookUrl !== undefined ? patch.discordWebhookUrl : current.discordWebhookUrl,
    patch.notifyCrash !== undefined ? (patch.notifyCrash ? 1 : 0) : (current.notifyCrash ? 1 : 0),
    patch.notifyStart !== undefined ? (patch.notifyStart ? 1 : 0) : (current.notifyStart ? 1 : 0),
    patch.notifyBackup !== undefined ? (patch.notifyBackup ? 1 : 0) : (current.notifyBackup ? 1 : 0),
    patch.notifyRestart !== undefined ? (patch.notifyRestart ? 1 : 0) : (current.notifyRestart ? 1 : 0),
    patch.vapidPublicKey !== undefined ? patch.vapidPublicKey : current.vapidPublicKey,
    patch.vapidPrivateKey !== undefined ? patch.vapidPrivateKey : current.vapidPrivateKey,
    patch.vapidSubject !== undefined ? patch.vapidSubject : current.vapidSubject
  );
  return getNotificationConfig();
}

function listPushSubscriptions() {
  return db.prepare('SELECT * FROM push_subscriptions ORDER BY created_at ASC').all().map(mapPushSubscription);
}

function savePushSubscription(subscription) {
  const endpoint = subscription?.endpoint;
  if (!endpoint) throw new Error('Push subscription endpoint required');
  db.prepare(`
    INSERT INTO push_subscriptions (endpoint, subscription_json, created_at)
    VALUES (?, ?, ?)
    ON CONFLICT(endpoint) DO UPDATE SET subscription_json = excluded.subscription_json
  `).run(endpoint, JSON.stringify(subscription), Date.now());
  return listPushSubscriptions().find((entry) => entry.endpoint === endpoint) || null;
}

function deletePushSubscription(endpoint) {
  db.prepare('DELETE FROM push_subscriptions WHERE endpoint = ?').run(endpoint);
}

module.exports = {
  db,
  hashPass,
  verifyPass,
  getUserByUsername,
  getUserById,
  listUsers,
  createUser,
  deleteUser,
  resetOwnerPasswordFromSecret,
  getServer,
  saveServer,
  insertPerformance,
  prunePerformance,
  listPerformance,
  listScheduledRestarts,
  createScheduledRestart,
  deleteScheduledRestart,
  updateScheduledRestartNextRun,
  listAnnouncements,
  getAnnouncement,
  createAnnouncement,
  updateAnnouncement,
  deleteAnnouncement,
  listDueAnnouncements,
  markAnnouncementBroadcastSent,
  getNotificationConfig,
  updateNotificationConfig,
  listPushSubscriptions,
  savePushSubscription,
  deletePushSubscription
};
