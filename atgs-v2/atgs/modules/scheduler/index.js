const cron = require('node-cron');
let ctx = null;
const jobs = new Map();

function startJob(s) {
  if (!cron.validate(s.cron)) return;
  jobs.set(s.id, cron.schedule(s.cron, () => {
    const inst = ctx.store.instances.find(s.instanceId);
    if (!inst) return;
    switch (s.action) {
      case 'restart': ctx.docker.restartInstance(inst.id).catch(() => {}); break;
      case 'stop': ctx.docker.stopInstance(inst.id).catch(() => {}); break;
      case 'command':
        if (s.command && inst.rconPort) ctx.rcon.sendCommand(`atgs-${inst.id}`, inst.rconPort, inst.rconPassword, s.command).catch(() => {});
        break;
    }
  }));
}

function stopJob(id) { const j = jobs.get(id); if (j) { j.stop(); jobs.delete(id); } }

module.exports = {
  init(context) {
    ctx = context;
    const router = context.createRouter();

    router.get('/', (req, res) => res.json(ctx.store.schedules.all()));

    router.post('/', (req, res) => {
      const { instanceId, name, cron: expr, action, command, enabled } = req.body;
      if (!instanceId || !expr || !action) return res.status(400).json({ error: 'instanceId, cron, action required' });
      const s = { id: require('crypto').randomBytes(4).toString('hex'), instanceId, name: name || 'Schedule', cron: expr, action, command: command || null, enabled: enabled !== false, createdAt: new Date().toISOString() };
      ctx.store.schedules.create(s);
      if (s.enabled) startJob(s);
      res.json({ ok: true, schedule: s });
    });

    router.delete('/:id', (req, res) => {
      stopJob(req.params.id);
      ctx.store.schedules.delete(req.params.id);
      res.json({ ok: true });
    });

    context.app.use('/api/schedules', router);

    // Start existing
    for (const s of ctx.store.schedules.all()) if (s.enabled) startJob(s);
  },

  destroy() { for (const [, j] of jobs) j.stop(); jobs.clear(); },
};
