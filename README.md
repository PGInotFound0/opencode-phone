# OpenCode Bridge (Android)

A small Android app (Android 8.0+, ~650 KB, no external libraries) that connects
your phone to a running **opencode** server and keeps you posted. Matte black + red UI.

**🚀 Full step-by-step setup: [`INSTALL.md`](INSTALL.md)** (native server, Docker, app install, multi-machine, Tailscale, troubleshooting).

- **Model finished** — a notification with the session title and a snippet of the last reply (`session.idle`).
- **Model has a question** — the `question` tool's question arrives as a high-priority notification. If there are 1–3 option buttons that fit, you can answer straight from the notification.
- **Permission requested** — e.g. "Allow once / Always / Deny" buttons right on the notification.
- **Session error** — failed runs produce an error notification.
- **Multiple machines** — add any number of opencode servers (each with its own URL/auth); every machine gets its own live SSE connection, merged session lists, and per-machine notification labels ("Homelab · Done · session title").
- **Chats on the phone** — tap a session (or "Chat") to open a chat view: full transcript (text bubbles, tool runs with status), type prompts in the composer, abort runs, auto-refresh every 4 s.
- You can also read the session list, send a prompt to any session from the phone, and abort a running session.

## What's here

```
opencode-phone/
├── OpenCodeBridge.apk          <- the app, built and signed
├── build.sh                    -> rebuilds the APK (no Gradle needed)
├── toolchain.sh                -> downloads the build toolchain (sdk/), one command
├── INSTALL.md                  <- full installation guide
├── app/                        <- Kotlin sources + resources
│   ├── AndroidManifest.xml
│   ├── res/
│   └── src/com/opencode/bridge/
│       ├── OcApi.kt            <- HTTP + SSE client for the opencode server
│       ├── SseService.kt       <- per-machine SSE workers + notifications
│       ├── MainActivity.kt     <- machines, sessions, chat launcher, pending replies
│       ├── ChatActivity.kt     <- transcript view + prompt composer
│       ├── Notifications.kt    <- channels and notification builders
│       ├── Prefs.kt            <- stored settings (machines list)
│       └── BootReceiver.kt     <- optional auto-reconnect after reboot
└── docker/                     <- containerized `opencode serve` (Dockerfile + compose)
```

## 1. Start the server on the computer

In any project directory:

```bash
opencode serve --port 4096 --hostname 0.0.0.0
```

- `--hostname 0.0.0.0` makes it reachable from other devices on your LAN.
- Optional password (recommended): `OPENCODE_SERVER_PASSWORD=secret opencode serve --port 4096 --hostname 0.0.0.0`
  then the app user is `opencode` (default) with password `secret`.
- Find the computer's LAN IP (`ip addr` / `ifconfig`), e.g. `192.168.1.10`.

The app talks to these endpoints (same thing the TUI/Web client uses):
`GET /global/event` (SSE), `GET /global/health`, `GET /session`, `GET /session/status`,
`POST /session`, `POST /session/{id}/prompt_async`, `POST /session/{id}/abort`,
`POST /session/{id}/permissions/{pid}`, `POST /question/{rid}/reply`, `POST /question/{rid}/reject`.

### Alternative: run the server from Docker

The `docker/` folder (also packaged as `opencode-serve-docker.zip` for sharing) runs
the identical server on any Docker machine:

```bash
cd docker
printf 'OPENCODE_SERVER_PASSWORD=pick-a-password\n' > .env
docker compose up -d --build
```

Details, plain-`docker run` variant, and volume layout are in `docker/README.md`.

## 2. Install the app on the phone

Copy `OpenCodeBridge.apk` to the phone (email, cable, cloud…) and tap it, or:

```bash
adb install OpenCodeBridge.apk
```

Android will ask to allow installs from this source once. It's an HTTP LAN app, so also
allow "network access" if your ROM asks.

## 3. In the app

1. Enter `http://192.168.1.10:4096` (and user/password if set), pick options:
   - *Keep CPU awake when screen is off* — recommended; holds a partial wakelock so the connection survives Doze. Costs some battery.
   - *Reconnect after reboot* — reconnects automatically at boot.
2. Tap **Save & connect** (first launch also asks for notification permission).
3. Tap **Test** — should toast `OK · opencode <version>`.
4. Tap **New session + ping** to do a full round-trip test: it creates a session, tells
   the model to reply `pong`, and you should get a "Done" notification on the phone.

## Notifications mapping

| Server event | What the phone does |
|---|---|
| `session.idle` | "Done · <session title>" with the last assistant text as the body |
| `question.asked` / `question.v2.asked` | "Question · <title>" and up to 3 option buttons (+ Reject). More options → tap to open the app and answer there (checkboxes for multi-select, custom text supported) |
| `permission.asked` / `permission.v2.asked` | "Permission needed · <title>" with Allow once / Always / Deny |
| `session.error` | "Error · <title>" with the error name/message |

Pending requests also appear under **"Waiting on you"** in the app itself, and clear
automatically when answered (or when the session goes idle).

## Rebuilding

```bash
bash build.sh   # outputs OpenCodeBridge.apk (tools live under ./sdk, needs Java 17+)
```

## Notes & limits

- Phone and computer must be on the same network. For anywhere access, run opencode inside
  a Tailscale/VPN network and use its IP/port.
- The connection is plain HTTP on your LAN; if other devices share the network, set
  `OPENCODE_SERVER_PASSWORD` (the app supports basic auth).
- Answers sent from the phone go to the **headless server's** sessions. If you also keep the
  TUI open, it either has its own server (or connect the TUI to a fixed port and both
  clients share one server — run the TUI in the same project dir).
- Reconnects automatically with backoff (5–30 s) whenever the connection drops.
