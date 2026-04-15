// ==============================================================
//  ATGS Module: Discord Integration
//  Posts rich embed notifications to a Discord webhook.
// ==============================================================
const https = require('https');
const url = require('url');

let ctx = null;

// ── Webhook Sender ───────────────────────────────────────────
function sendWebhook(embed) {
  const cfg = ctx.getConfig();
  const webhookUrl = cfg.webhookUrl;
  if (!webhookUrl) return;

  const parsed = new URL(webhookUrl);
  const payload = JSON.stringify({
    username: 'ATGS Worldwide',
    embeds: [embed],
  });

  const req = https.request({
    hostname: parsed.hostname,
    port: 443,
    path: parsed.pathname + parsed.search,
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) },
  }, res => {
    if (res.statusCode >= 400) {
      let body = '';
      res.on('data', c => (body += c));
      res.on('end', () => console.error(`[Discord] Webhook error ${res.statusCode}: ${body.slice(0, 200)}`));
    }
  });

  req.on('error', e => console.error('[Discord] Webhook failed:', e.message));
  req.write(payload);
  req.end();
}

function getInstanceName(id) {
  const inst = ctx.store.instances.find(id);
  return inst?.name || id;
}

// ── Embed Builders ───────────────────────────────────────────
const COLORS = { green: 0x30d898, red: 0xf06060, amber: 0xf0b020, blue: 0x00c8ff, purple: 0x7860ff };

function notify(title, description, color, fields, instanceId) {
  const embed = { title, description, color: COLORS[color] || COLORS.blue, timestamp: new Date().toISOString() };
  if (fields) embed.fields = fields;
  if (instanceId) embed.footer = { text: getInstanceName(instanceId) };
  sendWebhook(embed);
}

// ── Log Parsing ──────────────────────────────────────────────
const JOIN_RE = /\]: (\S+) joined the game/;
const LEAVE_RE = /\]: (\S+) left the game/;
const GATE_KICK_RE = /\[Access Gate\] Kicked unapproved player: (\S+)/;

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    // Get/set webhook config
    router.get('/config', (req, res) => {
      const cfg = ctx.getConfig();
      // Don't expose full webhook URL to frontend (only show if set)
      res.json({ ...cfg, webhookUrl: cfg.webhookUrl ? '(configured)' : '' });
    });

    router.post('/config', (req, res) => {
      ctx.setConfig({ ...ctx.getConfig(), ...req.body });
      res.json({ ok: true });
    });

    // Test webhook
    router.post('/test', (req, res) => {
      notify('Test Notification', 'ATGS Discord integration is working!', 'blue', [
        { name: 'Sent by', value: req.user?.username || 'Unknown', inline: true },
      ]);
      res.json({ ok: true });
    });

    context.app.use('/api/discord', router);
    console.log('[Discord] Ready. Configure webhook URL to enable notifications.');
  },

  onInstanceStart(instanceId) {
    const cfg = ctx.getConfig();
    if (!cfg.notifyStart) return;
    notify('Server Started', `**${getInstanceName(instanceId)}** is now online.`, 'green', null, instanceId);
  },

  onInstanceStop(instanceId) {
    const cfg = ctx.getConfig();
    if (!cfg.notifyStop) return;
    notify('Server Stopped', `**${getInstanceName(instanceId)}** has gone offline.`, 'red', null, instanceId);
  },

  onLogLine(instanceId, line) {
    const cfg = ctx.getConfig();
    if (!cfg.webhookUrl) return;

    // Player join
    if (cfg.notifyJoin) {
      const joinMatch = line.match(JOIN_RE);
      if (joinMatch) {
        notify('Player Joined', `**${joinMatch[1]}** joined the server`, 'green', null, instanceId);
        return;
      }
    }

    // Player leave
    if (cfg.notifyLeave) {
      const leaveMatch = line.match(LEAVE_RE);
      if (leaveMatch) {
        notify('Player Left', `**${leaveMatch[1]}** left the server`, 'amber', null, instanceId);
        return;
      }
    }

    // Access Gate kick
    if (cfg.notifyAccessGate) {
      const gateMatch = line.match(GATE_KICK_RE);
      if (gateMatch) {
        notify('Access Gate', `Unapproved player **${gateMatch[1]}** was kicked.`, 'purple', null, instanceId);
        return;
      }
    }
  },

  // Called by crash-detect module via broadcast
  onCrashDetected(instanceId, reason) {
    const cfg = ctx.getConfig();
    if (!cfg.notifyCrash) return;
    notify('Server Crashed', `**${getInstanceName(instanceId)}** has crashed.`, 'red', [
      { name: 'Cause', value: reason || 'Unknown', inline: false },
    ], instanceId);
  },

  // Called by backup module
  onBackupCreated(instanceId, backupName) {
    const cfg = ctx.getConfig();
    if (!cfg.notifyBackup) return;
    notify('Backup Created', `Backup for **${getInstanceName(instanceId)}**`, 'blue', [
      { name: 'File', value: backupName, inline: true },
    ], instanceId);
  },
};
