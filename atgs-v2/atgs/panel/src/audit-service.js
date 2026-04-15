const fs = require('fs');
const path = require('path');
const config = require('./config');

const AUDIT_LOG_PATH = path.join(config.controlDir, 'audit.jsonl');
const MAX_AUDIT_ENTRIES = 5000;

function appendAudit({ action, user = 'system', detail = '', metadata = {} }) {
  const entry = {
    timestamp: Date.now(),
    action,
    user,
    detail,
    metadata
  };

  fs.mkdirSync(path.dirname(AUDIT_LOG_PATH), { recursive: true });
  fs.appendFileSync(AUDIT_LOG_PATH, JSON.stringify(entry) + '\n', 'utf8');

  try {
    const lines = fs.readFileSync(AUDIT_LOG_PATH, 'utf8').split(/\r?\n/).filter(Boolean);
    if (lines.length > MAX_AUDIT_ENTRIES) {
      fs.writeFileSync(AUDIT_LOG_PATH, lines.slice(-MAX_AUDIT_ENTRIES).join('\n') + '\n', 'utf8');
    }
  } catch {}

  return entry;
}

function listAuditEntries({ search = '', action = '', page = 1, pageSize = 30 } = {}) {
  const all = fs.existsSync(AUDIT_LOG_PATH)
    ? fs.readFileSync(AUDIT_LOG_PATH, 'utf8')
        .split(/\r?\n/)
        .filter(Boolean)
        .map((line) => {
          try {
            return JSON.parse(line);
          } catch {
            return null;
          }
        })
        .filter(Boolean)
        .reverse()
    : [];

  const needle = String(search || '').trim().toLowerCase();
  const filtered = all.filter((entry) => {
    if (action && entry.action !== action) return false;
    if (!needle) return true;
    return `${entry.action} ${entry.user} ${entry.detail}`.toLowerCase().includes(needle);
  });

  const start = (Math.max(1, Number(page) || 1) - 1) * pageSize;
  return {
    entries: filtered.slice(start, start + pageSize),
    total: filtered.length,
    actions: [...new Set(all.map((entry) => entry.action))].sort()
  };
}

module.exports = {
  appendAudit,
  listAuditEntries
};
