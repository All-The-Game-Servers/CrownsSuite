function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function shell(title, body) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="refresh" content="30">
<title>${escapeHtml(title)} | ATGS v3</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700&family=IBM+Plex+Mono:wght@400;600&display=swap" rel="stylesheet">
<style>
:root{--bg:#071019;--panel:#112031;--line:#23425e;--text:#eff7ff;--muted:#8ca6bf;--accent:#5ce1e6;--success:#8df2af;--warn:#ffd27d;--danger:#ff8e8e}
*{box-sizing:border-box}body{margin:0;font-family:'Space Grotesk',sans-serif;background:radial-gradient(circle at top,#16324d 0,#071019 58%);color:var(--text)}
nav{display:flex;gap:18px;flex-wrap:wrap;padding:18px 24px;border-bottom:1px solid var(--line);background:rgba(7,16,25,.88);position:sticky;top:0;backdrop-filter:blur(16px)}
nav a{color:var(--muted);text-decoration:none}nav a:hover{color:var(--text)}
main{max-width:1120px;margin:0 auto;padding:28px 18px 64px}.hero{display:flex;justify-content:space-between;gap:18px;flex-wrap:wrap;align-items:end;margin-bottom:22px}
.hero h1{margin:0 0 8px}.hero p{margin:0;color:var(--muted)}.card,.tile{background:rgba(17,32,49,.9);border:1px solid var(--line);border-radius:18px;padding:18px;backdrop-filter:blur(16px)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:14px}.tile strong{display:block;font-size:.76rem;letter-spacing:.12em;text-transform:uppercase;color:var(--muted);margin-bottom:8px}.tile span{font-size:1.7rem;font-weight:700}
.list{display:grid;gap:12px}.player,.announce,.rule{display:flex;align-items:center;gap:12px;padding:12px 14px;border:1px solid var(--line);border-radius:14px;background:rgba(7,16,25,.42)}.player img{border-radius:10px}.muted{color:var(--muted)}
.status-dot{width:10px;height:10px;border-radius:999px;display:inline-block;margin-right:8px}.online{background:var(--success)}.offline{background:var(--danger)}.idle{background:var(--warn)}footer{margin-top:26px;color:var(--muted);font-size:.92rem;text-align:center}
</style>
</head>
<body>
<nav>
<a href="/status">Status</a>
<a href="/players">Players</a>
<a href="/stats">Stats</a>
<a href="/rules">Rules</a>
</nav>
<main>${body}<footer>Powered by ATGS Worldwide Container System - XKStudios</footer></main>
</body>
</html>`;
}

function renderStatusPage(status) {
  const dot = status.ready ? 'online' : status.state === 'sleeping' ? 'idle' : 'offline';
  return shell(status.name, `
  <section class="hero"><div><h1>${escapeHtml(status.name)}</h1><p>Public live status for your ATGS instance.</p></div><div class="card"><span class="status-dot ${dot}"></span>${escapeHtml(status.state)}</div></section>
  <section class="grid">
    <div class="tile"><strong>Status</strong><span>${escapeHtml(status.state)}</span></div>
    <div class="tile"><strong>Players</strong><span>${status.players.players}/${status.maxPlayers}</span></div>
    <div class="tile"><strong>Mode</strong><span>${status.ready ? 'Online' : 'Standby'}</span></div>
  </section>
  <section class="card" style="margin-top:18px"><h2>Online Players</h2><div class="list">${status.players.playerNames.length ? status.players.playerNames.map((name) => `<div class="player"><img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/32" width="32" height="32" alt="${escapeHtml(name)}"><div><strong>${escapeHtml(name)}</strong></div></div>`).join('') : '<div class="muted">Nobody is online right now.</div>'}</div></section>
  <section class="card" style="margin-top:18px"><h2>Announcements</h2><div class="list">${status.announcements.length ? status.announcements.map((item) => `<div class="announce"><div><strong>${escapeHtml(item.title)}</strong><div class="muted">${escapeHtml(item.message)}</div></div></div>`).join('') : '<div class="muted">No active announcements.</div>'}</div></section>`);
}

function renderPlayersPage(status) {
  return shell('Players', `
  <section class="hero"><div><h1>Players</h1><p>Who is online across the primary ATGS instance.</p></div></section>
  <section class="card"><h2>${escapeHtml(status.name)}</h2><div class="muted" style="margin-bottom:14px">${status.players.players}/${status.maxPlayers} online</div><div class="list">${status.players.playerNames.length ? status.players.playerNames.map((name) => `<div class="player"><img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/32" width="32" height="32" alt="${escapeHtml(name)}"><div><strong>${escapeHtml(name)}</strong></div></div>`).join('') : '<div class="muted">No players online.</div>'}</div></section>`);
}

function renderStatsPage(stats) {
  return shell('Player Stats', `
  <section class="hero"><div><h1>Player Stats</h1><p>Cached from Minecraft stat files and refreshed every 5 minutes.</p></div></section>
  ${Object.values(stats.categories).map((category) => `<section class="card" style="margin-bottom:18px"><h2>${escapeHtml(category.label)}</h2><div class="list">${category.entries.length ? category.entries.map((entry, index) => `<div class="player"><span class="muted">#${index + 1}</span><img src="${entry.avatar}" width="32" height="32" alt="${escapeHtml(entry.player)}"><div><strong>${escapeHtml(entry.player)}</strong><div class="muted">${entry.value.toLocaleString()}</div></div></div>`).join('') : '<div class="muted">No stat data yet.</div>'}</div></section>`).join('')}`);
}

function renderRulesPage(rules) {
  return shell('Rules', `
  <section class="hero"><div><h1>Rules</h1><p>Shared server rules for every player.</p></div></section>
  <section class="card"><div class="list">${rules.map((rule, index) => `<div class="rule"><strong>${index + 1}.</strong><div>${escapeHtml(rule)}</div></div>`).join('')}</div><p class="muted" style="margin-top:16px">Admins: edit data/rules.txt to customize.</p></section>`);
}

module.exports = {
  renderStatusPage,
  renderPlayersPage,
  renderStatsPage,
  renderRulesPage
};
