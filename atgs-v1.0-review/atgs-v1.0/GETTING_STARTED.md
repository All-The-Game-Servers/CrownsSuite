# ATGS — Getting Started (Really, Actually From Scratch)

Hi. If you're reading this, someone sent you `atgs-v1.0.tar.gz` and told you "just do the do." This document is the do.

No Linux experience assumed. No Docker experience assumed. No idea what a VPS is? That's fine. We'll walk through everything.

By the end of this guide you will have:
- A working Minecraft server (Java or Bedrock, your choice)
- That you own and control
- That your friends can join
- That will keep your worlds backed up automatically

**Time needed**: about 45 minutes the first time. Most of that is waiting for things to install.

---

## Before you start: what you need

**One computer you can leave running 24/7.** This is where your servers actually live. Options:
- **Your own PC or laptop** — works for testing, but your game servers turn off when you close the lid.
- **An old desktop at home** — totally fine. Plug it into ethernet, leave it in a closet.
- **A VPS (virtual private server)** — $5-10/month from providers like Hetzner, DigitalOcean, Linode, or Vultr. This is the "proper" answer if you want your server online reliably.

**What's a VPS?** A VPS is a Linux computer that lives in a datacenter somewhere. You rent it, you get a username and password (or an SSH key), and you log in through a terminal to use it. For your first time, I recommend **Hetzner** or **DigitalOcean** because they're cheap and easy. Get the smallest plan with at least **2 GB of RAM** and **Ubuntu 22.04 or 24.04** as the operating system.

**Your regular computer.** This is what you actually use every day. Windows or Mac, doesn't matter. You'll install one small program on here (Progenitor) to control your servers remotely.

**Basic tools you'll need installed on your regular computer:**
- **A terminal.** On Windows, that's PowerShell or Windows Terminal. On Mac, that's the "Terminal" app in Applications → Utilities. You have this already, you just might not have opened it before.
- **An SSH client.** On Mac and modern Windows, `ssh` is built into the terminal already. Test it by opening your terminal and typing `ssh` and pressing Enter. If you see "usage: ssh [...]" stuff, you're good.

---

## Part 1: Get onto your server

If you bought a VPS, the provider gave you:
- An **IP address** — looks like `123.45.67.89`
- A **username** — usually `root` or `ubuntu`
- A **password** — or an SSH key file

**From your regular computer's terminal**, type:

```
ssh username@ip-address
```

For example: `ssh root@123.45.67.89`. Press Enter. It'll ask for your password. Type it (nothing shows up as you type — that's normal, Linux just hides passwords). Press Enter.

If you see something like:
```
root@myserver:~#
```

**You're in.** You're now controlling the server through your terminal. Every command from here on in Part 2 and Part 3 gets typed into this terminal.

**If you get "connection refused" or "permission denied":** double-check the IP and password from your VPS provider's dashboard. They sometimes send these in email with extra spaces that get pasted wrong.

---

## Part 2: Install Central (the brain of ATGS)

This is where the ATGS control panel lives. Run these commands one at a time in the terminal where you're SSH'd into your server.

### 2.1 — Install what we need

```bash
sudo apt update
sudo apt install -y postgresql curl
```

This updates the package list and installs Postgres (the database) and curl (for downloading things). It'll scroll a bunch of text. Wait until you see your prompt again (the `$` or `#`).

### 2.2 — Make Postgres ready for ATGS

Three commands. Copy them one at a time:

```bash
sudo systemctl start postgresql
sudo -u postgres createdb atgs
sudo -u postgres createuser atgs -s
sudo -u postgres psql -c "ALTER USER atgs WITH PASSWORD 'changethispassword';"
```

Replace `changethispassword` with an actual password. Something long and weird. **Write it down somewhere** — you'll need it in a minute.

### 2.3 — Upload ATGS to your server

On your **regular computer** (not the server), open a new terminal window. Go to the folder where you downloaded `atgs-v1.0.tar.gz` and upload it:

```bash
scp atgs-v1.0.tar.gz username@ip-address:~/
```

For example: `scp atgs-v1.0.tar.gz root@123.45.67.89:~/`

It'll ask for the password again, then show a progress bar. When it's done, go back to your **server terminal** (the SSH session from Part 1).

### 2.4 — Unpack it

In the server terminal:

```bash
tar xzf atgs-v1.0.tar.gz
cd atgs-v1.0
ls
```

You should see a list of folders: `binaries/`, `deploy/`, `docs/`, etc. If you do, great.

### 2.5 — Run the setup wizard

This is the magic command that does all the first-time setup. Copy the password you wrote down in 2.2 and paste it into this command where it says `yourpassword`:

```bash
export ATGS_CENTRAL_DATABASE_URL="postgres://atgs:yourpassword@localhost/atgs?sslmode=disable"
./binaries/central-linux-amd64 setup
```

The wizard will now:

1. **"Checking Postgres connection..."** → should say OK. If it doesn't, your password is wrong.
2. **"Applying database migrations..."** → OK.
3. **"Generating CA and signing key..."** → OK. It prints a certificate block; ignore it.
4. **"Admin email:"** → type your email (any email, it doesn't have to be real). Press Enter.
5. **"Admin password (min 12 chars):"** → pick a password with at least 12 characters. Write this down too. Press Enter.
6. **"Install systemd unit for Central?"** → type `y` and press Enter. This makes Central start automatically when your server reboots.

At the end you'll see something like:

```
Next steps:
  ...
  2. Enroll your first keeper using this token:
       6950078c82554e962344c0474b63ae9bcab874be6b66753b50bf55657f4070bc
```

**COPY THAT TOKEN.** Save it in Notes or somewhere. You need it in Part 3.

### 2.6 — Start Central

```bash
sudo systemctl start atgs-central
sudo systemctl status atgs-central
```

The `status` command should show green text that says "active (running)". Press `q` to exit the status view.

### 2.7 — Open the firewall

Your VPS has a firewall. We need to let Central's two network ports through, and the game ports too:

```bash
sudo ufw allow 8080/tcp      # Admin API (for Progenitor)
sudo ufw allow 8443/tcp      # Keeper connections
sudo ufw allow 25565/tcp     # Minecraft Java port
sudo ufw allow 19132/udp     # Minecraft Bedrock port
sudo ufw allow 22/tcp        # SSH (IMPORTANT — don't lock yourself out)
sudo ufw --force enable
```

### 2.8 — Sanity check

Still in the server terminal:

```bash
curl http://localhost:8080/api/v1/version
```

You should see something like `{"server_version":"smoke-test","protocol_version":1}`. If you do, **Central is running.** Part 2 is done.

---

## Part 3: Install a Keeper (what actually runs game servers)

The Keeper is what hosts the Minecraft servers. For your first time, **run it on the same server as Central.** You can always add more Keepers on other machines later.

Keep your terminal open; we're still in the `atgs-v1.0/` folder.

### 3.1 — Install Docker

The Keeper uses Docker to run each game server in its own sandbox. Install it:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
```

Then log out and back in so Docker permissions take effect:

```bash
exit
```

Then SSH back in from your regular computer:

```bash
ssh username@ip-address
cd atgs-v1.0
```

Test Docker works:

```bash
docker ps
```

If you see a header row (maybe with no entries under it), Docker is working.

### 3.2 — Run the Keeper setup wizard

```bash
./binaries/keeper-linux-amd64 init
```

Four questions:

1. **Central URL**: type `https://localhost:8443` (since Central is on this same machine). Press Enter.
2. **Token**: paste the long token you saved from Part 2.5. Press Enter.
3. **State directory**: just press Enter to accept the default.
4. **Eggs directory**: type `/root/atgs-v1.0/eggs` (or wherever `atgs-v1.0` is on your machine — run `pwd` if you're not sure, then add `/eggs` to the end). Press Enter.
5. **Continue?**: type `y` and press Enter.

It writes a file called `keeper.env`. Good.

### 3.3 — Make the Keeper trust Central (dev mode)

Since Central is using a self-signed certificate, you need to tell the Keeper that's OK:

```bash
echo "ATGS_KEEPER_INSECURE_TLS=true" >> keeper.env
```

### 3.4 — Start the Keeper

```bash
set -a; . ./keeper.env; set +a
./binaries/keeper-linux-amd64 &
```

The `&` at the end lets it run in the background. You should see lines like:

```
keeper starting version=1.0 headless=false
enrolling with central central_url=https://localhost:8443
```

Give it about 10 seconds. If you don't see an error message, **it's working.**

**To make the Keeper also auto-start on reboot**, there's a bit more setup — but for now, don't worry about it. You can just re-run `./binaries/keeper-linux-amd64 &` if the server ever reboots.

---

## Part 4: Install Progenitor on your regular computer

This is the pretty admin panel. You install it on your laptop, not on the server.

### 4.1 — Generate a cert bundle

Back in the **server terminal**:

```bash
./binaries/central-linux-amd64 mint-progenitor-cert ~/prog-bundle
ls ~/prog-bundle
```

You should see 4 files: `client.crt`, `client.key`, `ca.crt`, `progenitor.id`.

### 4.2 — Download that folder to your regular computer

On your **regular computer's terminal** (not the server):

```bash
scp -r username@ip-address:~/prog-bundle ~/Desktop/prog-bundle
```

Now `prog-bundle` is on your Desktop.

### 4.3 — Run Progenitor

**On Windows**: find `progenitor-windows-amd64.exe` in the download (in the `binaries/` folder). Double-click it.

**On Mac**: there's no Mac build in v1.0 yet. For now you'll manage through the command line — see "Without Progenitor" at the end of this guide.

On Progenitor's first screen:
- **Central URL**: type `http://YOUR-SERVER-IP:8080` (replace with your actual VPS IP)
- **Bundle directory**: click Browse, pick the `prog-bundle` folder on your Desktop
- Click **Connect**

Then log in with the email and password you set in Part 2.5.

---

## Part 5: Make your first Minecraft server

In Progenitor:

1. Click **Instances** on the left sidebar
2. Click **New Instance**
3. **Keeper**: pick the one Keeper (there's only one right now).
4. **Egg**: pick one
   - **Minecraft Java (Paper)** for a normal server where people join with the regular Java Minecraft
   - **Minecraft Java (Fabric)** for a modded server
   - **Minecraft Bedrock** for mobile/Xbox/Switch players
5. **Name**: call it whatever ("my-first-server" is fine)
6. **Memory**: 2048 MB for Paper or Bedrock, 4096 MB for Fabric
7. Click **Create**

The Keeper downloads the right Docker image (first time, takes 2-5 minutes). You can watch the progress by clicking on the instance and hitting "Logs."

Once it says "Done (xx.xxs)! For help, type help", **your server is running.**

---

## Part 6: Connect to your server

**For Java (Paper or Fabric):**
- Open Minecraft
- Multiplayer → Add Server
- Server Address: `YOUR-SERVER-IP:25565` (replace with your VPS IP)
- Done → click the server → Join Server

**For Bedrock:**
- Open Minecraft (on phone, Xbox, PlayStation, Switch, or Windows 10 edition)
- Play → Servers → Add Server
- Server Name: anything
- Server Address: `YOUR-SERVER-IP`
- Port: `19132`
- Save → Join

**If it won't connect:**
- Did you do the firewall step (Part 2.7)?
- Is the instance actually running (green "running" state in Progenitor)?
- For Bedrock: your VPS provider might block UDP. Hetzner and DigitalOcean are fine. Some cheaper ones aren't.

---

## Common "to do the do" failures

### "ssh: connection refused"
The IP or port is wrong. Double-check your VPS provider's dashboard. Some providers don't allow SSH until you've clicked a confirmation email.

### "Password: " just hangs
You're typing the password into a frozen session. Press Ctrl+C, try `ssh` again, and type the password when it asks. Linux doesn't show typed passwords.

### `./binaries/central-linux-amd64: No such file or directory`
You're not in the `atgs-v1.0` folder. Run `cd ~/atgs-v1.0` first.

### `Permission denied` when running a binary
Run `chmod +x binaries/central-linux-amd64 binaries/keeper-linux-amd64` to make them executable.

### Setup wizard says "Postgres connection FAILED"
Your password in the `ATGS_CENTRAL_DATABASE_URL` doesn't match what you set in Part 2.2. Re-do step 2.2's `ALTER USER` command with a password you remember, then re-export the URL with that password.

### Progenitor says "connection error" or "certificate verify failed"
- The URL needs to be `http://` not `https://` for the admin port (8080)
- The Central URL port is **8080**, not 8443 (8443 is for Keepers, 8080 is for Progenitor)
- Your VPS firewall might not allow port 8080 from outside. In your server terminal: `sudo ufw status` — make sure 8080/tcp is ALLOW

### "Keeper enrollment rejected"
Tokens expire in 15 minutes. Mint a fresh one:
```bash
./binaries/central-linux-amd64 mint-enrollment-token
```
That prints a new token. Edit your `keeper.env` (`nano keeper.env`) and replace the old `ATGS_ENROLL_TOKEN=...` line with the new one. Then re-run `keeper init` OR just restart the keeper.

### Keeper connects but "docker: permission denied"
You didn't log out and back in after `usermod -aG docker`. `exit`, SSH back in, and try again.

### Game server starts but crashes immediately
Click the instance in Progenitor and view logs. 99% of the time it's either:
- Out of memory (bump memory higher, restart)
- `FAILED TO BIND TO PORT!` (something else is using the port — you probably have two instances configured for the same port)

### Players can't connect but everything looks running
- Is the firewall open for 25565/tcp (Java) or 19132/udp (Bedrock)? Run `sudo ufw status`.
- Can you telnet to it from outside? From your regular computer: `telnet YOUR-VPS-IP 25565`. If that fails, the firewall is the issue.

---

## Without Progenitor (if you're on Linux/Mac)

You can do everything from the command line:

```bash
# Log in (saves a session cookie)
curl -c cookies.txt -X POST http://YOUR-VPS-IP:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"yourpassword"}'

# List keepers
curl -b cookies.txt http://YOUR-VPS-IP:8080/api/v1/keepers

# Create an instance (replace KEEPER-ID with the id from the list above)
curl -b cookies.txt -X POST http://YOUR-VPS-IP:8080/api/v1/keepers/KEEPER-ID/instances \
  -H "Content-Type: application/json" \
  -d '{"egg_id":"minecraft-java-paper","name":"test-server","memory_bytes":2147483648}'
```

Not pretty, but it works.

---

## You did the do

Your server is up. Tell your friends the IP. Go play.

If anything is still broken, check `docs/OPERATIONS.md` and `docs/SECURITY.md` in the download, or come back to this guide and the Troubleshooting section.

Good luck, don't die in a creeper.
