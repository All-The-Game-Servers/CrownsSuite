const https = require('https');
const db = require('./db');

let webpush = null;
try {
  webpush = require('web-push');
} catch {}

function sendDiscordWebhook(url, title, message, type = 'info', extra = {}) {
  const colors = { info: 6214118, warning: 16765565, danger: 16748174, success: 9306799 };
  const payload = JSON.stringify({
    embeds: [{
      title,
      description: message,
      color: colors[type] || colors.info,
      fields: Object.entries(extra).map(([name, value]) => ({
        name,
        value: String(value),
        inline: false
      }))
    }]
  });

  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const req = https.request({
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || 443,
      path: `${target.pathname}${target.search}`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload)
      }
    }, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) return resolve();
        reject(new Error(data || `Webhook failed (${res.statusCode})`));
      });
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

async function sendPushNotification(title, message, extra = {}) {
  const config = db.getNotificationConfig();
  if (!webpush || !config.vapidPublicKey || !config.vapidPrivateKey || !config.vapidSubject) return;

  webpush.setVapidDetails(config.vapidSubject, config.vapidPublicKey, config.vapidPrivateKey);
  const subs = db.listPushSubscriptions();
  await Promise.all(subs.map(async (entry) => {
    try {
      await webpush.sendNotification(entry.subscription, JSON.stringify({
        title,
        body: message,
        extra
      }));
    } catch {
      db.deletePushSubscription(entry.endpoint);
    }
  }));
}

async function sendNotification(title, message, type = 'info', extra = {}) {
  const config = db.getNotificationConfig();
  if (config.discordWebhookUrl) {
    await sendDiscordWebhook(config.discordWebhookUrl, title, message, type, extra).catch(() => {});
  }
  await sendPushNotification(title, message, extra).catch(() => {});
}

module.exports = {
  sendNotification
};
