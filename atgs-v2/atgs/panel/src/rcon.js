const { Rcon } = require('rcon-client');
const config = require('./config');

async function send(command, server) {
  let client;
  try {
    client = await Rcon.connect({
      host: config.rconHost,
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

async function listPlayers(server) {
  try {
    const response = await send('list', server);
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
  } catch {
    return { online: false, players: 0, maxPlayers: server.maxPlayers, playerNames: [] };
  }
}

async function getTps(server) {
  try {
    return (await send('tps', server)).replace(/§[0-9a-fk-or]/gi, '').trim();
  } catch {
    return 'N/A';
  }
}

module.exports = {
  send,
  listPlayers,
  getTps
};
