# Always-on cloud host for Happy: the Happy daemon in a container, so the phone
# can start Claude Code sessions there at any time (docs/cloud-host.md).
# Build from the repository root:
#   docker build -f deploy/cloud-host/Dockerfile -t happy-cloud-host .

# Stage 1: dependencies of the CLI and its workspace packages only
FROM node:24-bookworm AS deps

RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
RUN corepack enable && corepack prepare pnpm@10.11.0 --activate

WORKDIR /repo

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY scripts ./scripts
COPY patches ./patches
COPY packages/happy-cli/package.json packages/happy-cli/
COPY packages/happy-wire/package.json packages/happy-wire/
COPY packages/happy-cli/scripts packages/happy-cli/scripts
COPY packages/happy-cli/tools packages/happy-cli/tools

RUN SKIP_HAPPY_WIRE_BUILD=1 pnpm install --frozen-lockfile --filter "happy..."

# Stage 2: build happy-wire and the CLI
FROM deps AS builder

COPY packages/happy-wire ./packages/happy-wire
COPY packages/happy-cli ./packages/happy-cli

RUN pnpm --filter @slopus/happy-wire --fail-if-no-match build \
    && pnpm --filter happy --fail-if-no-match build

# Stage 3: runtime
FROM node:24-bookworm-slim AS runner

# git and gh for repositories, ripgrep for the app's search, tmux for optional
# tmux sessions, tini to reap the sessions the daemon starts.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl git openssh-client ripgrep tmux tini jq \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /repo/node_modules /opt/happy/node_modules
COPY --from=builder /repo/packages/happy-wire /opt/happy/packages/happy-wire
COPY --from=builder /repo/packages/happy-cli /opt/happy/packages/happy-cli
RUN ln -s /opt/happy/packages/happy-cli/bin/happy.mjs /usr/local/bin/happy

COPY deploy/cloud-host/happy-host /usr/local/bin/happy-host
RUN chmod 0755 /usr/local/bin/happy-host

# A non-root user owns the state and the workspaces. The entrypoint starts as
# root only to fix the ownership of freshly mounted volumes, then drops to it.
RUN useradd --create-home --uid 10001 --shell /bin/bash happy \
    && mkdir -p /data /workspace && chown happy:happy /data /workspace

ENV NODE_ENV=production \
    HAPPY_HOME_DIR=/data/happy \
    HAPPY_HOST_KIND=cloud \
    HAPPY_DAEMON_LOG_STDOUT=1 \
    HAPPY_AUTH_HEADLESS=1 \
    HAPPY_DISABLE_CAFFEINATE=1 \
    HAPPY_WORKSPACE=/workspace

VOLUME ["/data", "/workspace"]
WORKDIR /workspace

ENTRYPOINT ["tini", "--", "/usr/local/bin/happy-host"]
