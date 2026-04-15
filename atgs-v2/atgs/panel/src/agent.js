const { URL } = require('url');
const http = require('http');
const https = require('https');
const config = require('./config');

function request(method, route, body) {
  const base = new URL(config.agentBaseUrl);
  const transport = base.protocol === 'https:' ? https : http;

  return new Promise((resolve, reject) => {
    const req = transport.request({
      protocol: base.protocol,
      hostname: base.hostname,
      port: base.port,
      path: route,
      method,
      headers: {
        'Content-Type': 'application/json',
        'x-atgs-secret': config.agentSecret
      }
    }, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        let parsed = null;
        if (data) {
          try {
            parsed = JSON.parse(data);
          } catch {
            parsed = { raw: data };
          }
        }
        if (res.statusCode >= 400) {
          const message = parsed?.error || parsed?.raw || `Agent request failed (${res.statusCode})`;
          return reject(new Error(message));
        }
        resolve(parsed);
      });
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

module.exports = {
  health: () => request('GET', '/health'),
  runtimeStatus: () => request('GET', '/runtime/status'),
  runtimeStats: () => request('GET', '/runtime/stats'),
  runtimeLogs: (tail = 200) => request('GET', `/runtime/logs?tail=${tail}`),
  runtimeTelemetry: (server) => request('POST', '/runtime/telemetry', { server }),
  runtimeCommand: (server, command) => request('POST', '/runtime/command', { server, command }),
  reconcileRuntime: (server) => request('POST', '/runtime/reconcile', { server }),
  bootstrapRuntime: (server) => request('POST', '/runtime/bootstrap', { server }),
  startRuntime: () => request('POST', '/runtime/start'),
  stopRuntime: (reason) => request('POST', '/runtime/stop', { reason }),
  restartRuntime: () => request('POST', '/runtime/restart'),
  listFiles: (relPath = '') => request('GET', `/files?path=${encodeURIComponent(relPath)}`),
  readFile: (relPath) => request('GET', `/files/read?path=${encodeURIComponent(relPath)}`),
  writeFile: (relPath, content) => request('POST', '/files/write', { path: relPath, content }),
  uploadFile: (relPath, filename, contentBase64) => request('POST', '/files/upload', { path: relPath, filename, contentBase64 }),
  mkdir: (relPath) => request('POST', '/files/mkdir', { path: relPath }),
  deletePath: (relPath) => request('POST', '/files/delete', { path: relPath }),
  listBackups: () => request('GET', '/backups'),
  createBackup: (server, addons, label) => request('POST', '/backups/create', { server, addons, label }),
  restoreBackup: (server, name) => request('POST', '/backups/restore', { server, name }),
  importLegacyBackup: (server, filename) => request('POST', '/backups/import-legacy', { server, filename })
};
