# Always-on cloud host

Run the Happy daemon on a cloud machine so the phone can start coding sessions
there whenever it likes. To the app it is just another computer: it's always
online, it never sleeps with your laptop lid, and it is labelled **Cloud**.
Claude Code runs there (through happy-cli and the Agent SDK), and inference can
come from OpenRouter with the key kept on the host (`docs/openrouter.md`).

The host only dials out to the Happy server, so it needs no public port, no
domain and no inbound firewall rule.

## What's in `deploy/cloud-host/`

| File | Purpose |
| --- | --- |
| `Dockerfile` | Builds happy-cli from this repository onto `node:24-bookworm-slim` with git, gh, ripgrep, tmux and tini. |
| `happy-host` | Entrypoint: fixes volume ownership, drops to the `happy` user, sets up git/GitHub, clones `REPOS`, then `exec happy daemon start-sync`. |
| `docker-compose.yml` | Any Docker host or VPS. |
| `fly.toml` | Fly.io, one always-on machine. |
| `.env.example` | The settings below. |

`.devcontainer/devcontainer.json` uses the same image in GitHub Codespaces,
which stop when idle: handy, but not always on.

Image defaults: `HAPPY_HOME_DIR=/data/happy` (credentials, the OpenRouter key
file, logs), workspaces in `/workspace` (`HAPPY_WORKSPACE`), `HAPPY_HOST_KIND=cloud`
(the app's Cloud label), `HAPPY_AUTH_HEADLESS=1` and `HAPPY_DAEMON_LOG_STDOUT=1`.

## Settings (environment, all optional)

| Variable | Use |
| --- | --- |
| `OPENROUTER_API_KEY` | OpenRouter key. In the app pick the credential "Environment variable" with name `OPENROUTER_API_KEY`. Without it, use **Store key on this computer** in Settings › AI providers; the key is kept in `/data/happy/credentials/openrouter.key`. |
| `GITHUB_TOKEN` | Lets git clone and push over https (`gh auth setup-git`). Use a fine-grained token limited to the repositories the agent should touch. |
| `REPOS` | Space-separated git URLs cloned into the workspace on first boot. You can also clone from the phone: New session › project › Clone repository. |
| `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL` | Identity for the agent's commits. |
| `HAPPY_SERVER_URL` | A self-hosted Happy server. |

## Docker Compose (any VPS or Docker host)

```bash
cp deploy/cloud-host/.env.example deploy/cloud-host/.env   # edit it; never commit it
docker compose -f deploy/cloud-host/docker-compose.yml up -d --build
docker compose -f deploy/cloud-host/docker-compose.yml logs -f
```

The first start prints a QR code and a `happy://terminal?…` link. In the app,
**Add › Scan** it (or open the link on the phone). The credentials land in the
`happy-data` volume, so later restarts connect without pairing.

## Fly.io

```bash
fly launch --no-deploy --copy-config --config deploy/cloud-host/fly.toml
fly volumes create happy_data --size 10 --config deploy/cloud-host/fly.toml
fly secrets set OPENROUTER_API_KEY=… GITHUB_TOKEN=… --config deploy/cloud-host/fly.toml
fly deploy --config deploy/cloud-host/fly.toml --dockerfile deploy/cloud-host/Dockerfile .
fly logs --config deploy/cloud-host/fly.toml   # scan the QR code once
```

A Fly machine takes one volume, so workspaces live in `/data/workspace` there.
Keep the machine running (no auto-stop): there are no requests to wake it.

## A plain VM without Docker

```bash
# Node 22+ and git, then from a checkout of this repository:
pnpm install && pnpm --filter happy build && npm link ./packages/happy-cli
happy auth login --headless      # scan the QR code from the app
```

`/etc/systemd/system/happy.service`:

```ini
[Unit]
Description=Happy daemon (cloud host)
After=network-online.target
Wants=network-online.target

[Service]
User=happy
Environment=HAPPY_HOST_KIND=cloud HAPPY_DAEMON_LOG_STDOUT=1 HAPPY_DISABLE_CAFFEINATE=1
EnvironmentFile=-/etc/happy/env
ExecStart=/usr/local/bin/happy daemon start-sync
Restart=always

[Install]
WantedBy=multi-user.target
```

`journalctl -u happy -f` shows the QR code on first start, as in a container.

## Using it from the phone

1. The computer appears with a **Cloud** label once paired.
2. Settings › AI providers: the computer row shows which OpenRouter keys it
   has. **Test connection** runs from the host.
3. New session: pick the cloud computer, then a project folder, or **Clone
   repository** to fetch one into the workspace. Then pick a model or an
   OpenRouter profile.

## How it works

- Sign-in without a terminal: when stdin is not a TTY (or with
  `HAPPY_AUTH_HEADLESS=1` / `happy auth login --headless`), the menu is skipped.
  The QR code and both links go to stdout, and the host waits for the phone.
- `HAPPY_DAEMON_LOG_STDOUT=1` copies only the daemon's own lifecycle lines
  (`[DAEMON RUN]`, `[API MACHINE]`) to stdout. Session traffic stays in the
  log files on the volume, so no session text reaches the host's log service.
- No `claude` binary is needed: sessions the daemon starts run Claude Code
  through the Agent SDK's bundled binary, and the daemon now reports Claude as
  available when that binary is installed.
- `HAPPY_HOST_KIND=cloud` adds `hostKind: "cloud"` to the machine's metadata,
  which the app shows as the Cloud label.

## Security

- The agent runs commands in the container with the `happy` user's rights. Give
  the host only the tokens it needs, scoped to the repositories it should
  change.
- Secrets come from the environment or the `/data` volume, never the image.
  The OpenRouter key is never stored on the phone; if you send it from the
  phone, it goes once, end-to-end encrypted.
- Permission prompts still come to the phone: approve tool use there as with
  any computer.
- For a limit on the bill, use an OpenRouter workspace with its own key and
  budget (`docs/openrouter.md`).

## Updating

Rebuild and restart: `docker compose … up -d --build`, or `fly deploy …`. The
volumes keep the pairing, keys and workspaces.

## Verified so far

- In a Linux container without Docker: the entrypoint (privilege drop, volume
  ownership, git setup, `REPOS` with failures tolerated, no secrets printed).
- The real daemon headless against the Happy server: QR code and links on
  stdout, polling, clean exit on SIGTERM.
- Not yet: building the image (no Docker daemon in that environment) and a
  deployment on Fly.io.
