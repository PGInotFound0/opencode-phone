# OpenCode Bridge — Full Installation Guide

Turn your phone into a notification remote for the **opencode** coding agent:

- ✅ notification when a model **finishes** a run (with the last reply as body)
- ❓ notification when the model **has a question** — answer with buttons right from the notification (or in-app with checkboxes / custom text)
- 🔒 **permission requests** — Allow once / Always / Deny from the lock screen
- 💬 **chat view** — read the full transcript and type prompts from the phone
- 🖥 **multiple machines** — connect any number of opencode servers at once

Everything runs on your own hardware. No accounts, no cloud middleman.

---

## 0. What you need

| Component | Where | Notes |
|---|---|---|
| opencode ≥ 1.19 | computer (or Docker host) | `curl -fsSL https://opencode.ai/install \| bash` |
| OpenCode Bridge app | Android phone | Android 8.0+ (API 26) |
| Same network | phone + computer | LAN, Tailscale, or another VPN |
| Java 17+ (only to build from source) | build machine | `java -version` |

Two install paths for the app: **A. prebuilt APK** (fastest), **B. build from source**.

---

## 1. Start the server on the computer

### Option A — native (what this repo's author uses)

```bash
# in any project directory you want opencode to work in
opencode serve --port 4096 --hostname 0.0.0.0
```

Recommended: password-protect it (the app supports basic auth):

```bash
OPENCODE_SERVER_PASSWORD=choose-one opencode serve --port 4096 --hostname 0.0.0.0
```

Keep that terminal open (or run it under `tmux`/`systemd`). Ctrl-C stops it.

Find the computer's LAN IP:

```bash
ip addr    # look for "inet 192.168.x.x"
```

**Linux firewall (common gotcha):** if the phone can't connect, the firewall is likely blocking it:

```bash
sudo ufw allow 4096/tcp comment 'opencode'
```

### Option B — Docker (any Docker machine, same behavior)

```bash
cd docker
printf 'OPENCODE_SERVER_PASSWORD=pick-a-password\n' > .env
mkdir -p workspace                 # your project files live here
docker compose up -d --build       # builds from pinned opencode release, health-checked
```

Details (plain `docker run`, volumes, provider keys): [`docker/README.md`](docker/README.md).

Quick test from another terminal:

```bash
curl http://localhost:4096/global/health
# {"healthy":true,"version":"1.19.0"}
```

---

## 2. Install the phone app

### A. Use the prebuilt APK (fastest)

`OpenCodeBridge.apk` ships in this repo (debug-signed, 668 KB).

1. Copy it to the phone (cable / email / cloud drive).
2. Open it — Android asks once to allow installs from that source.
3. That's it. Or via adb:

```bash
adb install OpenCodeBridge.apk         # first install
adb install -r OpenCodeBridge.apk      # update over an existing one
```

### B. Build from source

```bash
git clone <this-repo-url>
cd opencode-phone
./toolchain.sh    # downloads Android SDK build-tools 34 + platform 34 + Kotlin 2.1.20 into ./sdk (~1 GB, once)
./build.sh        # -> OpenCodeBridge.apk
```

`build.sh` needs no Gradle; paths are script-relative and overridable
(`BRIDGE_SDK=...`, `BRIDGE_APK=...`, `BRIDGE_BUILD=...`, `BRIDGE_KEYSTORE=...`).
The APK is signed with an auto-generated debug keystore under `build/`.

---

## 3. Connect the app to the server

1. Open **OpenCode Bridge**.
2. **Server address**: `http://<computer-ip>:4096` — e.g. `http://192.168.1.10:4096`
   - *http* only — the server has no TLS; `https://` will fail with a "TLS packet header" error.
   - username: `opencode` (default), password only if you set `OPENCODE_SERVER_PASSWORD`.
3. Enable **Notify** permission when asked (first launch).
4. Options:
   - *Keep CPU awake when screen is off* — holds a partial wakelock so the live connection survives Doze (recommended).
   - *Reconnect after reboot* — boots the service automatically.
5. Tap **Save & connect**.
6. Tap **Test** → expect `OK · opencode <version>`.
7. Tap **New session + ping** → within a minute you should get a "Done · From phone …" notification on the phone.

Status line shows `Connected · n machine(s)` once everything is up.

---

## 4. Add more machines (optional)

The form in the app is the **primary** machine. Any number of extras:

1. **+ Add machine** → name, `http://<ip>:4096`, user/password → Save.
2. Rows have **Start/Stop** (pause a connection), **Edit**, **Remove**.
3. Sessions from all machines merge into one list, labeled `[$machine]` per row.
4. Notifications are tagged with the machine name so you know where work finished.

---

## 5. Using chats from the phone

- Tap any session row → **Chat** opens the full transcript (user/assistant bubbles, tool runs with status 🛠).
- Type in the composer → **Send** → runs `prompt_async` on that machine. You'll get the normal "Done" notification when the model finishes.
- **Abort run** at the top stops the current turn.
- The screen polls every 4 s while it's open.

Questions & permissions that need you appear in the "Waiting on you" list *and* as notifications; answering from either place is identical.

---

## 6. Access from outside your LAN

Recommended: **Tailscale** (encrypted, no open ports). Install it on the computer and the phone, join the same tailnet, then use `http://<tailscale-ip>:4096` (the machine's 100.x.y.z address) as the server address — from anywhere.

Avoid raw port-forwarding of 4096 to the internet: the API can run shell commands on your computer, and without TLS a password travels in cleartext.

---

## 7. Manual event reference (what the app subscribes to)

| Server event | Phone reaction |
|---|---|
| `session.idle` | "Done · machine · session" + last assistant text |
| `question.asked` / `question.v2.asked` | question notification, up to 3 option buttons, Reject |
| `permission.asked` / `permission.v2.asked` | Allow once / Always / Deny |
| `session.error` | error name + message |

Replies use `POST /session/{id}/permissions/{pid}`, `POST /question/{rid}/reply`, `POST /session/{id}/prompt_async`, `POST /session/{id}/abort`.

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| "Failed to connect to /192.168.x.x (port 4096)" | server not running, or firewall → `sudo ufw allow 4096/tcp` |
| "unable to parse TLS packet header" | URL starts with `https://` — the server is plain **http** |
| "OK · opencode…" but no notifications | notification permission denied in system settings; or battery optimization killed the app (opt it out) |
| Notifications stop after a while with screen off | enable *Keep CPU awake* in the app |
| `adb install` fails with signature change | uninstall old app first (`adb uninstall com.opencode.bridge`), then install |
| Wrong session titles in notifications | they self-fill from `session.created/updated`; reconnect to re-cache |
| Two phones both get all notifications | expected — `/global/event` broadcasts everything to every client |

## 9. Uninstall

- Phone: normal app removal stops everything.
- Computer: stop the server process / `docker compose down`.
- Firewalls: `sudo ufw delete allow 4096/tcp`.
