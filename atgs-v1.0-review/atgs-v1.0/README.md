# ATGS — Your Own Minecraft Hosting Panel

**Version 1.0** — April 2026

> **Never set up a server before? Read [`GETTING_STARTED.md`](GETTING_STARTED.md) first.** That guide assumes nothing — no Linux experience, no Docker experience, no VPS experience. It walks you through every command from "how do I open a terminal" to "my friends are playing on my server." About 45 minutes.
>
> If you're already comfortable with Linux / Docker / SSH, keep reading — this README is the technical overview.

---

Think of ATGS like your personal Pterodactyl or Pelican. You run the "control panel" on one computer, and then any friend's computer can become a server host by running a small program called the **Keeper**. You manage everything from a normal Windows app on your laptop.

Three Minecraft flavors work out of the box: **Paper** (plugins), **Fabric** (mods), and **Bedrock** (mobile / Xbox / Nintendo / Windows 10 clients).

---

## The big picture in 60 seconds

```
┌─────────────────┐          ┌─────────────────┐
│  Your laptop    │          │  Server machine │
│  (Windows)      │          │  (Linux)        │
│                 │  mTLS    │                 │
│  Progenitor ────┼──────────┼──→ Central      │  ← brain of the operation
│  (the admin UI) │  over    │    (the panel)  │
│                 │  HTTPS   │                 │
│                 │          │    Postgres     │  ← database
│                 │          └────────┬────────┘
│                 │                   │
│                 │                   │  mTLS over WebSocket
│                 │                   ↓
│                 │          ┌─────────────────┐
│                 │          │  Any machine    │
│                 │          │  with Docker    │
│                 │          │                 │
│                 │          │   Keeper        │  ← agent that runs game servers
│                 │          │   (agent)       │
│                 │          │                 │
│                 │          │   Docker:       │
│                 │          │   ├─ Paper      │  ← actual game servers
│                 │          │   ├─ Fabric     │
│                 │          │   └─ Bedrock    │
└─────────────────┘          └─────────────────┘
```

**Central** is the brain. One of these runs on your main server.

**Keeper** is the muscle. One of these runs on *every* machine that hosts game servers. That could be the same machine as Central, or totally separate computers — yours, your friend's, a $5/mo VPS, whatever.

**Progenitor** is the steering wheel. You install it on your Windows laptop. From there you control everything: add servers, take backups, restart instances, revoke keepers that are misbehaving.

---

## What you get in the download

```
atgs-v1.0/
├── binaries/
│   ├── central-linux-amd64              ← run this on your Central server
│   ├── keeper-linux-amd64               ← run this on Linux game-host machines
│   ├── keeper-windows-amd64.exe         ← run this on Windows game-host machines
│   └── progenitor-windows-amd64.exe     ← the admin panel for your laptop
├── deploy/
│   ├── docker-compose.yml               ← "I just want to run it with one command"
│   ├── central.Dockerfile
│   ├── keeper.Dockerfile
│   ├── haproxy-java.cfg                 ← advanced: multiple relays
│   └── envoy-java.yaml                  ← advanced: alternative to HAProxy
├── docs/
│   ├── SECURITY.md                      ← how the crypto works (for the curious)
│   └── OPERATIONS.md                    ← day-to-day tasks
├── eggs/                                ← "server templates"
│   ├── minecraft-java-paper/            ← Paper (plugins)
│   ├── minecraft-java-fabric/           ← Fabric (mods)
│   └── minecraft-bedrock/               ← Bedrock (mobile/console)
├── migrations/                          ← database schema (auto-applied)
└── source/
    └── atgs-v1.0-source.tar.gz          ← full source code if you want to tinker
```

---

## Step 1: Set up Central (the brain)

You need one Linux machine. A cheap VPS works. This is where the control panel lives. It does not run game servers itself — that's what Keepers do.

**Install Postgres if you don't have it.**
```bash
sudo apt install postgresql      # Debian/Ubuntu
sudo systemctl start postgresql
sudo -u postgres createdb atgs
sudo -u postgres createuser atgs -s
sudo -u postgres psql -c "ALTER USER atgs WITH PASSWORD 'pickagoodpassword';"
```

**Run the Central binary's setup wizard.** This does *everything* you need in one command:

```bash
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:pickagoodpassword@localhost/atgs?sslmode=disable"
./central-linux-amd64 setup
```

It will:
1. Check Postgres is reachable
2. Create the database tables
3. Generate the internal security certificates
4. Ask you for an admin email and password
5. Give you a **one-time token** — copy this, you need it for Step 2
6. Offer to install a systemd service so Central auto-starts on boot

Then actually start the server:
```bash
./central-linux-amd64 serve
```

If you accepted the systemd offer, use `sudo systemctl start atgs-central` instead.

---

## Step 2: Set up a Keeper (a game-server host)

You can do this on:
- **The same Linux machine as Central** (fine for starting out)
- **Another Linux machine** (this is the normal case)
- **A Windows machine** (works, see below)

### 2A — Linux Keeper

**Install Docker first.** The Keeper uses Docker to run each game server in its own sandbox.
```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
# log out and back in so the group takes effect
```

**Run the Keeper's setup wizard:**
```bash
./keeper-linux-amd64 init
```

It asks four questions:
1. **Central URL** — whatever the Central machine's address is, like `https://central.mydomain.com:8443` or `https://192.168.1.50:8443`
2. **Enrollment token** — the one-time token Central gave you in Step 1
3. **State directory** — where this Keeper stores its identity and game server data. Default (`~/.atgs-keeper`) is fine.
4. **Eggs directory** — where the server templates live. Point this at the `eggs/` folder from the download.

The wizard writes a `keeper.env` file next to the binary. Start the Keeper:

```bash
set -a; . ./keeper.env; set +a
./keeper-linux-amd64
```

On first run it "enrolls" with Central — basically, it presents the token, gets a permanent certificate, and then stays connected.

### 2B — Windows Keeper

**Install Docker Desktop** from docker.com. Make sure it's running before you start the Keeper.

Download `keeper-windows-amd64.exe` from the `binaries/` folder. Put it somewhere permanent like `C:\atgs\keeper.exe` (not Downloads — Windows gets weird about running from Downloads).

**Open PowerShell** and `cd` to wherever you put the exe. Run the setup wizard:

```powershell
.\keeper-windows-amd64.exe init
```

Answer the same four questions as the Linux version. The wizard writes `keeper.env` in the same folder.

**To actually run the Keeper on Windows**, there are two ways:

**Option 1 (simplest) — PowerShell script.** Create a file next to the exe called `run-keeper.ps1`:

```powershell
# run-keeper.ps1
Get-Content .\keeper.env | ForEach-Object {
    if ($_ -and -not $_.StartsWith('#')) {
        $name, $value = $_ -split '=', 2
        [System.Environment]::SetEnvironmentVariable($name, $value)
    }
}
.\keeper-windows-amd64.exe
```

Then double-click the `.ps1` file or run `.\run-keeper.ps1` from PowerShell. Keep the PowerShell window open — closing it stops the Keeper.

**Option 2 (proper) — run as a Windows service** using `nssm` (Non-Sucking Service Manager). Download nssm from nssm.cc, then:

```powershell
# Install as a service
nssm install ATGSKeeper "C:\atgs\keeper-windows-amd64.exe"
nssm set ATGSKeeper AppDirectory "C:\atgs"
# Read each line of keeper.env and set the env on the service
Get-Content C:\atgs\keeper.env | ForEach-Object {
    if ($_ -and -not $_.StartsWith('#')) {
        $name, $value = $_ -split '=', 2
        nssm set ATGSKeeper AppEnvironmentExtra "$name=$value"
    }
}
nssm start ATGSKeeper
```

Now the Keeper runs as a Windows service, starts on boot, restarts on crash. Logs are viewable in `Event Viewer → Windows Logs → Application`.

**Firewall on Windows.** If players are connecting directly to this Keeper's host (which is what Bedrock needs), open the right port in Windows Firewall:

```powershell
# For Java (Paper or Fabric):
New-NetFirewallRule -DisplayName "Minecraft Java" -Direction Inbound -Protocol TCP -LocalPort 25565 -Action Allow
# For Bedrock:
New-NetFirewallRule -DisplayName "Minecraft Bedrock" -Direction Inbound -Protocol UDP -LocalPort 19132 -Action Allow
```

### 2C — Docker Keeper (for nerds)

If you'd rather run the Keeper inside its own container:

```bash
docker run -d --name atgs-keeper \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v atgs-keeper-state:/state \
  -v $(pwd)/eggs:/eggs:ro \
  -e ATGS_KEEPER_CENTRAL_URL=https://central.example.com:8443 \
  -e ATGS_ENROLL_TOKEN=<your-token> \
  -e ATGS_KEEPER_STATE_DIR=/state \
  -e ATGS_KEEPER_EGGS_DIR=/eggs \
  -e ATGS_KEEPER_DATA_ROOT=/state/instances \
  xkstudios/atgs-keeper:1.0.0
```

---

## Step 3: Install Progenitor (the admin panel)

On your Windows laptop:

1. **Copy `progenitor-windows-amd64.exe` to your computer.** Put it somewhere permanent (e.g. `C:\Programs\ATGS\`).
2. **Mint a "cert bundle"** on the Central server. This is a little folder of credentials that lets Progenitor talk to Central:
   ```bash
   ./central-linux-amd64 mint-progenitor-cert /tmp/prog-bundle
   ```
   That creates `/tmp/prog-bundle/` with four files. Copy that whole folder to your Windows laptop (over SFTP/Dropbox/a USB stick — anything private).
3. **Run `progenitor-windows-amd64.exe`.** First launch shows a connection screen:
   - **Central URL**: whatever your Central's external admin address is, e.g. `https://central.mydomain.com:8080`
   - **Bundle directory**: browse to the folder you copied in step 2
   - Click **Connect**
4. Log in with the email + password you set in Step 1 (when running `central setup`).

Now you can click around:
- **Keepers** — see every host that's enrolled. Online/offline status, resource meters, revoke a keeper if it's compromised.
- **Instances** — your game servers. Create, start, stop, delete. Click "Logs" to see what's happening.
- **Backups** — see all backups across all instances. Restore with one click.
- **Schedules** — set up daily auto-backups with retention policies.

---

## Step 4: Make your first game server

In Progenitor:

1. Click **Instances** → **New Instance**
2. Pick a Keeper (which host do you want it on?)
3. Pick an egg:
   - **Minecraft Java (Paper)** — for normal servers with plugins like EssentialsX, WorldGuard, etc.
   - **Minecraft Java (Fabric)** — for modded servers. Drop mod jars into the instance's `/data/mods/` folder on the Keeper host.
   - **Minecraft Bedrock** — for mobile/Xbox/Nintendo/Windows 10 clients.
4. Name it something.
5. Memory limit. 2 GB for vanilla-ish Paper. 4–8 GB for Fabric with mods. 2 GB for Bedrock.
6. Click **Create**.

The Keeper downloads the right Docker image (first time only, might take a few minutes), creates a container, and starts it. You can watch the log in Progenitor while this happens.

**To connect as a player:**
- Java (Paper or Fabric): open Minecraft, go to Multiplayer → Add Server, address is `<keeper-host-ip>:25565`
- Bedrock: open Minecraft, go to Servers → Add, address is `<keeper-host-ip>` port 19132

---

## The three server flavors explained

### Paper (plugins)
For most people. Vanilla-ish Minecraft with support for Spigot/Bukkit/Paper plugins. Think of plugins as server-side add-ons: anti-grief, economy, custom chat, etc. Players join with a normal unmodded Java Edition client.

**Memory**: 2 GB is plenty unless you're running big plugins.

### Fabric (mods)
Full modding platform. Sodium + Lithium for performance, Simple Voice Chat for voice, Create for factory gameplay, thousands more. **Everyone connecting to a Fabric server needs the same mods installed on their client.** That's how Minecraft modding works — not ATGS-specific.

**Memory**: 4–8 GB. Mods eat RAM.

**How to install mods**: after the instance is created, on the Keeper host navigate to `<state-dir>/instances/<instance-id>/mods/` and drop .jar files in. Restart the instance from Progenitor.

### Bedrock (mobile / Xbox / Switch / Windows 10)
For players on phones, tablets, consoles, and the "Minecraft for Windows" edition. Uses UDP instead of TCP so firewall rules are different. Bedrock servers can't run Java plugins or mods — it's its own ecosystem. You'd add behavior packs and resource packs via the Minecraft admin in-game console.

**Memory**: 2 GB is fine.

**Important**: Bedrock needs UDP port 19132 open on the Keeper host. In v1.0 players connect **directly** to the Keeper — there's no relay in between. So your Keeper host needs a public IP and open UDP port.

---

## Common questions

**"Can I have Central and a Keeper on the same machine?"**
Yes, totally normal setup for small deployments.

**"Can one person run Central while friends run Keepers?"**
Yes — that's actually what ATGS is designed for. You mint an enrollment token, DM it to your friend, they run `keeper init` and paste it. Now their machine hosts your servers.

**"Can I manage multiple friends' Centrals from one Progenitor?"**
Not yet in v1.0 — you can switch between them by disconnecting and re-connecting with a different bundle. Multi-connection picker is planned for v1.1.

**"Is this production ready?"**
The core platform is hardened: every message is cryptographically signed, there's a full audit log, there's a kill switch that wipes compromised keepers. It's built like a real product. But v1.0 is a first release. Start with a test deployment before betting your business on it.

**"What about DDoS protection?"**
ATGS doesn't do DDoS mitigation itself. Put Cloudflare Spectrum, AWS Shield, or a similar service in front of your Keeper IPs if you're worried about it.

**"Can I charge people for servers hosted this way?"**
The platform supports it (it has user accounts, instance ownership, etc.) but there's no built-in billing in v1.0. That's on the v1.1 roadmap. For now you'd need to handle payments externally and use ATGS just for the technical hosting.

**"What if my Keeper machine dies?"**
Your backups live on Central (not on the Keeper), so they survive. You spin up a new Keeper on new hardware, enroll it, and restore backups into new instances there.

**"Does this work with modpacks from CurseForge or Modrinth?"**
The underlying Docker image (`itzg/minecraft-server`) supports modpack auto-installation — you add env vars like `AUTO_CURSEFORGE=1` to the instance config. In v1.0 that's a manual edit to the egg or instance env; v1.1 will have a Marketplace UI to click-install popular modpacks.

---

## Troubleshooting

**Central fails to start with "connection refused"**
Postgres isn't running, or the DATABASE_URL is wrong. Test manually:
```bash
psql "postgres://atgs:password@localhost/atgs"
```

**Keeper says "enrollment rejected"**
Tokens expire in 15 minutes by default. Mint a fresh one:
```bash
./central-linux-amd64 mint-enrollment-token
```

**Progenitor can't connect**
- Is the Central URL reachable from your laptop? Try opening it in a browser.
- Is the bundle folder complete? It should have 4 files: `client.crt`, `client.key`, `ca.crt`, `progenitor.id`.

**Game server won't start**
Check the Keeper's log (terminal where you ran the Keeper, or `docker logs atgs-keeper` if containerized). Usually either the memory limit is too low, Docker isn't running, or the egg config is broken.

**Players on Bedrock can't connect**
- UDP 19132 must be open in the host firewall
- The Keeper host must have a public IP (or the player is on the same LAN)
- Bedrock doesn't like NAT — port forwarding needs to preserve source port

**Nothing works and I'm frustrated**
Look at `/var/log/syslog` (Linux) or the Event Viewer (Windows) for the service you're running. Central logs go to stdout when you run `serve` directly, or to `journalctl -u atgs-central` if you installed the systemd unit. Keeper logs go to stdout / `journalctl -u atgs-keeper` / Event Viewer (Windows nssm service).

---

## Where to go from here

- Read `docs/OPERATIONS.md` for day-to-day admin tasks
- Read `docs/SECURITY.md` if you care about the crypto details
- The source code is in `source/atgs-v1.0-source.tar.gz` if you want to build your own version or contribute

---

Built by Krisp Klank. XKStudios. First version, April 2026.

If it works for you, tell somebody. If it breaks, tell me.
