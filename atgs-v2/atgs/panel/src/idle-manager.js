const db = require('./db');
const config = require('./config');
const service = require('./server-service');

let timer = null;
let busy = false;

async function tick() {
  if (busy) return;
  busy = true;
  try {
    const summary = await service.getServerSummary();
    if (!summary.runtime.running || !summary.sleepEnabled) return;

    if (summary.players.players > 0) {
      if (summary.state !== 'running' || summary.lastEmptyAt) {
        db.saveServer({ state: 'running', lastEmptyAt: null });
      }
      return;
    }

    const lastEmptyAt = summary.lastEmptyAt || new Date().toISOString();
    if (!summary.lastEmptyAt) {
      db.saveServer({ state: 'idle', lastEmptyAt });
      return;
    }

    const idleForMs = Date.now() - new Date(lastEmptyAt).getTime();
    if (idleForMs < summary.idleGraceSeconds * 1000) {
      if (summary.state !== 'idle') db.saveServer({ state: 'idle' });
      return;
    }

    try {
      await service.sendConsoleCommand(`say Server entering sleep mode after ${summary.idleGraceSeconds} seconds of inactivity.`);
    } catch {}

    await service.sleepServer('idle');
  } finally {
    busy = false;
  }
}

function start() {
  if (timer) return;
  timer = setInterval(() => {
    tick().catch((error) => {
      console.error('[Idle]', error.message);
    });
  }, config.idlePollMs);
}

function stop() {
  if (!timer) return;
  clearInterval(timer);
  timer = null;
}

module.exports = {
  start,
  stop
};
