# OpenRouter for Claude Code sessions

Happy can run a Claude Code session on a paired computer with inference from
[OpenRouter](https://openrouter.ai) instead of Anthropic. Claude Code (through
happy-cli and the Agent SDK) is still the **harness**: it runs the tools, asks
for permissions and streams the turn. OpenRouter only supplies the **inference**.
The choice is made per session, from the phone, and the computer applies it.

## Pieces

| Where | What |
| --- | --- |
| `packages/happy-wire/src/inference.ts` | Shared schemas: `InferenceConfig` (request), `InferenceAck` (what the computer applied), `InferenceCapability`, `InferenceStatus`, `InferenceTestResult`. |
| `packages/happy-cli/src/inference/credentials.ts` | Credential references and the Happy-managed key file. |
| `packages/happy-cli/src/inference/openrouter.ts` | Claude Code environment, model catalog, cost estimates, connection test. |
| `packages/happy-cli/src/inference/relay.ts` | Loopback relay between Claude Code and OpenRouter. |
| `packages/happy-cli/src/inference/sessionInference.ts` | Session-side activation and the acknowledgement in session metadata. |
| `packages/happy-cli/src/inference/daemonInference.ts` | Spawn/resume preparation and the machine RPCs. |
| `packages/happy-android/.../inference/`, `ui/inference/` | Profiles, model browser, provider status, spend. |

## Keys stay on the computer

The phone never holds an OpenRouter key. A profile names a **credential reference**:

- `happy:openrouter`: the file `<happyHomeDir>/credentials/openrouter.key`
  (directory 0700, file 0600). It is written by `happy auth openrouter` on the
  computer, or by the phone's "Store key on this computer", which sends the key
  once over the end-to-end encrypted machine RPC `inference-store-credential`.
  The phone keeps no copy.
- `env:NAME`: an environment variable visible to the Happy daemon, for example
  `env:OPENROUTER_API_KEY`.

```bash
happy auth openrouter            # reads the key from stdin (hidden on a TTY)
happy auth openrouter --test anthropic/claude-sonnet-5.5
happy auth openrouter --remove
```

The daemon resolves the reference when a session starts. A missing key fails the
spawn with a readable error ("No OpenRouter key is stored on this host…").

## Launch path

1. The phone sends `spawn-happy-session` with `inference: InferenceConfig`
   (provider, credential reference, model slug, optional routing variant, server
   tools, budget, profile name). It is end-to-end encrypted like every RPC.
2. The daemon (`daemon/run.ts` → `prepareSpawnInference`) validates it, resolves
   the key, passes `--model <slug>` and gives the child two variables:
   `HAPPY_INFERENCE_CONFIG` (JSON, no secret) and `HAPPY_INFERENCE_KEY`. The
   Anthropic OAuth token the phone may also send is not given to this child.
   This works on the direct and the tmux spawn paths.
3. The session process (`runClaude` → `activateSessionInference`) deletes both
   variables from its environment, starts the relay on `127.0.0.1:<random>` with
   a per-session token, and sets Claude Code's environment:

   | Variable | Value |
   | --- | --- |
   | `ANTHROPIC_BASE_URL` | the relay (direct fallback: `https://openrouter.ai/api`, no `/v1`) |
   | `ANTHROPIC_AUTH_TOKEN` | the relay token (direct fallback: the key) |
   | `ANTHROPIC_API_KEY` | `""` (empty, not unset, as OpenRouter's guide requires) |
   | `ANTHROPIC_MODEL`, `ANTHROPIC_DEFAULT_{OPUS,SONNET}_MODEL`, `CLAUDE_CODE_SUBAGENT_MODEL` | the profile's slug |
   | `ANTHROPIC_DEFAULT_HAIKU_MODEL`, `ANTHROPIC_SMALL_FAST_MODEL` | the background model, else the slug |
   | removed | `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_PROFILE`, `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX` |

   These follow OpenRouter's Claude Code guide and Claude Code's environment
   variable reference (`ANTHROPIC_AUTH_TOKEN` is sent as `Authorization: Bearer`
   and outranks OAuth tokens).
4. Resume (`resume-happy-session`) rebuilds the config from the session's
   acknowledgement, so a resumed session keeps its provider, model, tools,
   budget and the spend counted so far. If the computer no longer has the key,
   resume fails with that error instead of silently using Anthropic.

## The relay

`relay.ts` sits between Claude Code and `https://openrouter.ai/api`:

- It accepts only requests carrying the session's relay token, swaps it for the
  real key upstream, and never logs bodies or keys. Claude Code never sees the key.
- It appends the profile's OpenRouter **server tools** (`openrouter:web_search`,
  `openrouter:web_fetch`, `openrouter:datetime`) to `/v1/messages` bodies.
  Claude Code can't add these itself, and the `:online` suffix is deprecated.
  It decodes gzip/deflate/br request bodies first.
- It reads OpenRouter's `usage` from each response (SSE or JSON), including
  `usage.cost` and `usage.server_tool_use`, and counts the session's spend.
- **Budget**: once OpenRouter-reported spend reaches the profile's `budgetUsd`,
  it answers new Messages requests with an Anthropic-format
  `invalid_request_error` instead of forwarding them. The limit is checked
  before each request, so the request that crosses it completes. It counts
  OpenRouter's own figures, but it is a per-session guard, not a billing
  control: set a credit limit on the OpenRouter key for a hard cap on the bill.
- If the relay can't start, the session runs direct. The acknowledgement then
  says `reportedCost: false`, no server tools and no budget.

Hosts behind an HTTP proxy need Node's proxy support for the relay's upstream
`fetch` (for example `NODE_USE_ENV_PROXY=1` on Node 22.21+).

## Acknowledgement

The session writes `metadata.inference` (`InferenceAck`), end-to-end encrypted:
harness, provider, base URL host, credential reference (name only), requested
model, the model Claude Code reported in its `system/init` message
(`activeModel`), injected server tools, budget, spend and cumulative usage with
`costSource`:

- `reported`: every request carried OpenRouter's own cost;
- `estimated`: some requests were priced from OpenRouter's published prices;
- `unavailable`: no figure.

The app shows a provider as configured only from this object. A profile saved on
the phone is a request, not a fact. If `activeModel` differs from
`requestedModel` (ignoring a `:nitro`/`:floor` suffix), the app warns.

Happy CLI's ordinary `usage-report` socket event keeps working. For OpenRouter
sessions its cost uses OpenRouter's catalog prices instead of Anthropic's list
prices. It is not end-to-end encrypted (unchanged), so the app's spend figures
come from the acknowledgement.

## Machine RPCs

| Method | Params | Answer |
| --- | --- | --- |
| `inference-status` | none | `InferenceStatus`: capability, which offered references resolve (booleans), OpenRouter Ori detection |
| `inference-test` | provider, credentialRef, model, serverTools? | `InferenceTestResult`: `MISSING_CREDENTIAL`, `AUTH_FAILED`, `MODEL_NOT_FOUND`, `MODEL_NO_TOOLS`, `SERVER_TOOL_REJECTED`, `PROVIDER_UNREACHABLE`, `INVALID_REQUEST`, or ok with key label/limit/usage |
| `inference-store-credential` | provider, apiKey | `{ ok, ref?, message }` (never echoes the key) |

The test runs from the computer: `GET /api/v1/key`, a lookup in `/api/v1/models`
(tool calling is required for Claude Code), and for each server tool a
one-token Messages request to prove OpenRouter accepts it on the endpoint
Claude Code uses (the docs only confirm web search on the Responses API).

Capability: hosts that understand all this advertise
`machineMetadata.inference = { version: 1, providers: ["openrouter"], harnesses: ["claude"], … }`.
Without it the app does not offer profiles on that computer.

## Recommended OpenRouter setup: a workspace for Happy

Every OpenRouter key belongs to a workspace, and a workspace can carry daily,
weekly, monthly or lifetime spending limits and guardrails that OpenRouter
enforces itself. Create a "Happy" workspace with its own key and budget and
store that key on the computer. That limits the bill; the profile's hard limit
is a per-session guard on top. OpenRouter documents no per-request workspace
selector, so profiles don't carry one.

Not integrated, on purpose: OpenRouter's container tools (`openrouter:shell`,
`openrouter:bash`) run commands in an OpenRouter sandbox, outside the repository
and outside Happy's permission prompts, so Claude Code keeps its own tools on the
computer. The Files API is only documented for loading files into those
containers.

## Model changes

A session's model chip lists OpenRouter slugs: the launched one, the account's
profiles and its favourites. A pick goes as the message's `meta.model` and
applies from the next message in the same conversation (Claude Code resumes
with the new model). Changing provider needs a new session: the provider is
fixed when the process starts.

## OpenRouter Ori

[Ori Harness](https://openrouter.ai/docs/guides/ori/harness) (alpha) launches
agent CLIs with OpenRouter credentials. Happy's remote Claude sessions run
through the Agent SDK in-process, not a `claude` binary on PATH, so Ori is not
used as the launch path. `inference-status` reports whether `ori` is installed
and any `ORI_MODEL`, and OpenRouter org policies (allowlists, guardrails,
workspace budgets) apply to Happy's requests with an org key all the same.
Reusing Ori's OAuth credential would need a documented credential interface,
which Ori doesn't publish.

## Happy Agent

Happy Agent (`rig`) sessions use the provider catalog the Happy Agent daemon
publishes in machine and session metadata (`providers[]`, `models[]`). Its
OpenRouter/OpenAI-compatible providers appear there and the app already offers
them. Configuring OpenRouter credentials for Happy Agent happens in the
happy-agent daemon, whose source is not in this repository.

## Verifying

```bash
cd packages/happy-cli
pnpm typecheck
npx vitest run --project unit src/inference
```

The unit tests cover credential references, the environment, the relay (a real
local HTTP server stands in for OpenRouter: token swap, server tools, cost,
budget, unreachable upstream, compressed bodies), spawn and resume
preparation, and session activation. An end-to-end check runs the real Agent
SDK through the relay against a loopback stand-in by setting
`HAPPY_INFERENCE_TEST_UPSTREAM` (ignored unless it is `http://127.0.0.1` or
`http://localhost`).
