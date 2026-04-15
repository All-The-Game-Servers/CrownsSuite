const fs = require('fs');
const path = require('path');
const config = require('./config');
const db = require('./db');

const RULES_PATH = path.join(config.dataDir, 'rules.txt');
const CACHE_MS = 5 * 60 * 1000;

let cache = null;
let cacheAt = 0;

function ensureRulesFile() {
  if (!fs.existsSync(RULES_PATH)) {
    fs.mkdirSync(path.dirname(RULES_PATH), { recursive: true });
    fs.writeFileSync(
      RULES_PATH,
      [
        'Respect other players and their builds.',
        'No cheating, xray, duping, or exploit abuse.',
        'Keep chat civil and follow staff directions.',
        'PvP only where the server or staff explicitly allow it.',
        'Have fun and help keep the server welcoming.'
      ].join('\n') + '\n',
      'utf8'
    );
  }
}

function readRules() {
  ensureRulesFile();
  return fs.readFileSync(RULES_PATH, 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

function readJsonIfExists(filePath, fallback = null) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return fallback;
  }
}

function loadUserCache() {
  const filePath = path.join(config.serverFilesDir, 'usercache.json');
  const raw = readJsonIfExists(filePath, []);
  const map = new Map();
  if (Array.isArray(raw)) {
    raw.forEach((entry) => {
      if (entry?.uuid && entry?.name) {
        map.set(String(entry.uuid).replace(/-/g, ''), entry.name);
      }
    });
  }
  return map;
}

function findStatsDir() {
  const server = db.getServer();
  return path.join(config.serverFilesDir, server.levelName || 'world', 'stats');
}

function aggregateStats() {
  if (cache && Date.now() - cacheAt < CACHE_MS) return cache;

  const statsDir = findStatsDir();
  const users = loadUserCache();
  const categories = {
    playtime: { label: 'Most Playtime', entries: [] },
    deaths: { label: 'Most Deaths', entries: [] },
    kills: { label: 'Most Kills', entries: [] },
    mined: { label: 'Most Blocks Mined', entries: [] }
  };

  if (fs.existsSync(statsDir)) {
    fs.readdirSync(statsDir)
      .filter((name) => name.endsWith('.json'))
      .forEach((name) => {
        const uuid = name.replace('.json', '').replace(/-/g, '');
        const player = users.get(uuid) || name.replace('.json', '').slice(0, 16);
        const raw = readJsonIfExists(path.join(statsDir, name), {});
        const custom = raw.stats?.['minecraft:custom'] || {};
        const mined = Object.values(raw.stats?.['minecraft:mined'] || {}).reduce((sum, value) => sum + Number(value || 0), 0);

        categories.playtime.entries.push({ player, value: Number(custom['minecraft:play_time'] || 0) });
        categories.deaths.entries.push({ player, value: Number(custom['minecraft:deaths'] || 0) });
        categories.kills.entries.push({ player, value: Number(custom['minecraft:mob_kills'] || 0) });
        categories.mined.entries.push({ player, value: mined });
      });
  }

  cache = {
    updatedAt: Date.now(),
    categories: Object.fromEntries(
      Object.entries(categories).map(([key, category]) => [key, {
        label: category.label,
        entries: category.entries
          .sort((left, right) => right.value - left.value)
          .slice(0, 20)
          .map((entry) => ({
            ...entry,
            avatar: `https://mc-heads.net/avatar/${encodeURIComponent(entry.player)}/32`
          }))
      }])
    )
  };
  cacheAt = Date.now();
  return cache;
}

module.exports = {
  readRules,
  aggregateStats
};
