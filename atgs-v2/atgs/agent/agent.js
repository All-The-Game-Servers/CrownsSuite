#!/usr/bin/env node
// ==============================================================
//  ATGS Remote Node Agent
//  A product of XKStudios
//
//  Runs on remote machines and connects back to the ATGS panel.
//  Proxies Docker API requests and reports system resources.
//
//  Usage:
//    PANEL_URL=ws://panel-host:8080 NODE_SECRET=your-secret node agent.js
//
//  Or via environment / .env file.
// ==============================================================
const http = require('http');
const https = require('https');
const os = require('os');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const WebSocket = require('ws');

// ── Config ───────────────────────────────────────────────────
const PANEL_URL = process.env.PANEL_URL || 'ws://localhost:8080';
const NODE_SECRET = process.env.NODE_SECRET || 'atgs-node-secret';
const NODE_NAME = process.env.NODE_NAME || os.hostname();
const DOCKER_SOCKET = process.env.DOCKER_SOCKET || '/var/run/docker.sock';
const DATA_DIR = process.env.DATA_DIR || '/data';
const RECONNECT_INTERVAL = 5000;

let ws = null;
let reconnectTimer = null;

// ── Docker API Proxy ─────────────────────────────────────────
function dockerRequest(method, endpoint, body) {
  return new Promise((resolve, reject) => {
    const opts = {
      socketPath: DOCKER_SOCKET,
      path: endpoint,
      method,
      headers: { 'Content-Type': 'application/json' },
    };
    const req = http.request(opts, res => {
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

// ── System Stats ─────────────────────────────────────────────
function getSystemStats() {
  const cpus = os.cpus();
  const totalMem = os.totalmem();
  const freeMem = os.freemem();
  const uptime = os.uptime();

  // CPU usage (average across cores over last tick)
  const cpuAvg = cpus.reduce((sum, cpu) => {
    const total = Object.values(cpu.times).reduce((a, b) => a + b, 0);
    const idle = cpu.times.idle;
    return sum + ((total - idle) / total) * 100;
  }, 0) / cpus.length;

  // Disk usage
  let diskTotal = 0, diskUsed = 0;
  try {
    const df = execSync(`df -B1 ${DATA_DIR} 2>/dev/null | tail -1`).toString().trim().split(/\s+/);
    diskTotal = parseInt(df[1]) || 0;
    diskUsed = parseInt(df[2]) || 0;
  } catch {}

  return {
    hostname: os.hostname(),
    platform: os.platform(),
    arch: os.arch(),
    cpuCount: cpus.length,
    cpuModel: cpus[0]?.model || 'Unknown',
    cpuPercent: cpuAvg.toFixed(1),
    memTotal: totalMem,
    memUsed: totalMem - freeMem,
    memPercent: (((totalMem - freeMem) / totalMem) * 100).toFixed(1),
    diskTotal,
    diskUsed,
    diskPercent: diskTotal > 0 ? ((diskUsed / diskTotal) * 100).toFixed(1) : '0',
    uptime,
    nodeVersion: process.version,
  };
}

// ── List containers on this node ─────────────────────────────
async function listContainers() {
  try {
    const r = await dockerRequest('GET', '/containers/json?all=true&filters={"name":["atgs-"]}');
    if (r.status !== 200) return [];
    return (r.data || []).map(c => ({
      id: c.Id?.slice(0, 12),
      name: c.Names?.[0]?.replace(/^\//, ''),
      state: c.State,
      status: c.Status,
      image: c.Image,
      ports: c.Ports,
    }));
  } catch { return []; }
}

// ── Handle messages from panel ───────────────────────────────
async function handleMessage(msg) {
  try {
    const data = JSON.parse(msg);

    switch (data.type) {
      case 'ping':
        send({ type: 'pong' });
        break;

      case 'stats':
        send({ type: 'stats', requestId: data.requestId, stats: getSystemStats() });
        break;

      case 'containers':
        send({ type: 'containers', requestId: data.requestId, containers: await listContainers() });
        break;

      case 'docker': {
        // Proxy a Docker API request
        const result = await dockerRequest(data.method, data.endpoint, data.body);
        send({ type: 'docker-response', requestId: data.requestId, ...result });
        break;
      }

      case 'file-read': {
        // Read a file from this node (for migration)
        const fp = path.resolve(DATA_DIR, data.path);
        if (!fp.startsWith(DATA_DIR)) {
          send({ type: 'file-response', requestId: data.requestId, error: 'Access denied' });
          break;
        }
        try {
          const content = fs.readFileSync(fp, 'base64');
          send({ type: 'file-response', requestId: data.requestId, content, size: Buffer.byteLength(content, 'base64') });
        } catch (e) {
          send({ type: 'file-response', requestId: data.requestId, error: e.message });
        }
        break;
      }

      case 'file-write': {
        // Write a file to this node (for migration)
        const fp = path.resolve(DATA_DIR, data.path);
        if (!fp.startsWith(DATA_DIR)) {
          send({ type: 'file-response', requestId: data.requestId, error: 'Access denied' });
          break;
        }
        try {
          fs.mkdirSync(path.dirname(fp), { recursive: true });
          fs.writeFileSync(fp, Buffer.from(data.content, 'base64'));
          send({ type: 'file-response', requestId: data.requestId, ok: true });
        } catch (e) {
          send({ type: 'file-response', requestId: data.requestId, error: e.message });
        }
        break;
      }

      case 'exec': {
        // Run a command (for migration tar/untar)
        try {
          const output = execSync(data.command, { timeout: data.timeout || 300000, cwd: DATA_DIR }).toString();
          send({ type: 'exec-response', requestId: data.requestId, output, exitCode: 0 });
        } catch (e) {
          send({ type: 'exec-response', requestId: data.requestId, output: e.message, exitCode: e.status || 1 });
        }
        break;
      }

      default:
        send({ type: 'error', message: `Unknown message type: ${data.type}` });
    }
  } catch (e) {
    console.error('[Agent] Message handling error:', e.message);
  }
}

function send(data) {
  if (ws?.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  }
}

// ── Connection ───────────────────────────────────────────────
function connect() {
  const url = `${PANEL_URL}/agent?secret=${encodeURIComponent(NODE_SECRET)}&name=${encodeURIComponent(NODE_NAME)}`;

  console.log(`[Agent] Connecting to ${PANEL_URL}...`);
  ws = new WebSocket(url);

  ws.on('open', () => {
    console.log('[Agent] Connected to panel');
    // Send initial registration with stats
    send({
      type: 'register',
      name: NODE_NAME,
      stats: getSystemStats(),
    });
  });

  ws.on('message', (msg) => handleMessage(msg.toString()));

  ws.on('close', () => {
    console.log('[Agent] Disconnected. Reconnecting...');
    scheduleReconnect();
  });

  ws.on('error', (err) => {
    console.error('[Agent] Connection error:', err.message);
    scheduleReconnect();
  });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, RECONNECT_INTERVAL);
}

// ── Startup ──────────────────────────────────────────────────
console.log('');
console.log('  ╔══════════════════════════════════════╗');
console.log('  ║   ATGS Remote Node Agent              ║');
console.log('  ║   A product of XKStudios               ║');
console.log('  ╚══════════════════════════════════════╝');
console.log('');
console.log(`  Node: ${NODE_NAME}`);
console.log(`  Panel: ${PANEL_URL}`);
console.log(`  Data: ${DATA_DIR}`);
console.log('');

connect();

// Keep alive — send stats every 30s
setInterval(() => {
  if (ws?.readyState === WebSocket.OPEN) {
    send({ type: 'heartbeat', stats: getSystemStats() });
  }
}, 30000);
