const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const config = require('./config');

class Store {
  constructor(name, defaults = []) {
    this.file = path.join(config.dbDir, `${name}.json`);
    fs.mkdirSync(config.dbDir, { recursive: true });
    if (!fs.existsSync(this.file)) fs.writeFileSync(this.file, JSON.stringify(defaults, null, 2));
  }
  _read() { try { return JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch { return []; } }
  _write(data) { fs.writeFileSync(this.file, JSON.stringify(data, null, 2)); }
  all() { return this._read(); }
  find(id) { return this._read().find(i => i.id === id) || null; }
  findBy(key, val) { return this._read().find(i => i[key] === val) || null; }
  create(item) { const d = this._read(); d.push(item); this._write(d); return item; }
  update(id, u) { const d = this._read(); const i = d.findIndex(x => x.id === id); if (i === -1) return null; d[i] = { ...d[i], ...u }; this._write(d); return d[i]; }
  delete(id) { const d = this._read(); const f = d.filter(i => i.id !== id); if (f.length === d.length) return false; this._write(f); return true; }
}

function hashPass(p) { const s = crypto.randomBytes(16).toString('hex'); return `${s}:${crypto.scryptSync(p, s, 64).toString('hex')}`; }
function verifyPass(p, stored) { const [s, h] = stored.split(':'); return crypto.scryptSync(p, s, 64).toString('hex') === h; }

const users = new Store('users');
if (users.all().length === 0) {
  users.create({ id: 'owner', username: config.adminUser, password: hashPass(config.adminPass), role: 'owner', instances: ['*'], createdAt: new Date().toISOString() });
}

module.exports = { instances: new Store('instances'), templates: new Store('templates'), schedules: new Store('schedules'), users, hashPass, verifyPass };
