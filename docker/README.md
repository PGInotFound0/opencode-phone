# OpenCode serve container

Runs `opencode serve --port 4096 --hostname 0.0.0.0` in Docker so any machine
(laptop, NAS, VPS) can host the bridge server the same way. Default opencode
version: 1.19.0 — change with `--build-arg OPENCODE_VERSION=x.y.z` (empty string = latest).

## Quick start (docker compose)

```bash
cd docker
printf 'OPENCODE_SERVER_PASSWORD=pick-a-password\n' > .env
mkdir -p workspace   # put your project files here (or point the volume elsewhere)
docker compose up -d --build
```

Then in the phone app: `http://<this-machine-ip>:4096` + user `opencode` + your password.

## Plain docker, no compose

```bash
docker build -t opencode-serve .
docker run -d --name opencode-serve --restart unless-stopped \
  -p 4096:4096 \
  -e OPENCODE_SERVER_PASSWORD=pick-a-password \
  -v "$PWD":/workspace \
  -v oc-config:/root/.config/opencode \
  -v oc-data:/root/.local/share/opencode \
  opencode-serve
```

## What gets persisted

| Volume | Contents |
|---|---|
| `oc-config` | `/root/.config/opencode` — `opencode.json` config, agents, plugins |
| `oc-data` | `/root/.local/share/opencode` — `auth.json` (logins), caches |

First run: drop your `opencode.json` into the config volume (`docker cp` or volume mount a folder), or pass provider keys as `-e ANTHROPIC_API_KEY=...` etc. The health check hits `/global/health` on 127.0.0.1 inside the container.

## Notes

- At build time the Dockerfile pulls the pinned opencode release from `dl.github.com`/opencode.ai — no network needed at runtime (except the model APIs themselves).
- Publishing 4096 to the internet raw is not recommended; a Tailscale overlay network is the safe alternative.
