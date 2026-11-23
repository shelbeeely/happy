/**
 * Tests for the OpenRouter host integration: credential references, the
 * Claude Code environment, the loopback relay (against a real local HTTP
 * server standing in for OpenRouter), and daemon spawn preparation.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { mkdtempSync, rmSync, statSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CredentialError, listCredentialPresence, resolveCredential, storeOpenRouterKey } from './credentials';
import {
    buildClaudeInferenceEnv,
    applyEnvPatch,
    effectiveModelSlug,
    estimateCostUsd,
    parseOpenRouterModels,
    testOpenRouterConnection,
} from './openrouter';
import { decodeBody, startInferenceRelay, UsageCollector, withServerTools, type RelayUsage } from './relay';
import { gzipSync } from 'node:zlib';
import { prepareResumeInference, prepareSpawnInference } from './daemonInference';
import { activateSessionInference, INFERENCE_CONFIG_ENV, INFERENCE_KEY_ENV, INFERENCE_PRIOR_ENV } from './sessionInference';

const SECRET = 'sk-or-v1-test-secret-value-1234567890';

const tempDirs: string[] = [];
function tempHome(): string {
    const dir = mkdtempSync(join(tmpdir(), 'happy-inference-'));
    tempDirs.push(dir);
    return dir;
}

const servers: Server[] = [];
async function startFakeOpenRouter(handler: (req: IncomingMessage, body: string) => { status: number; headers?: Record<string, string>; body: string | string[] }): Promise<string> {
    const server = createServer(async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const c of req) chunks.push(c as Buffer);
        const reply = handler(req, Buffer.concat(chunks).toString('utf8'));
        res.writeHead(reply.status, reply.headers ?? { 'content-type': 'application/json' });
        for (const part of Array.isArray(reply.body) ? reply.body : [reply.body]) {
            res.write(part);
            await new Promise((r) => setTimeout(r, 5));
        }
        res.end();
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
    return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

afterEach(async () => {
    for (const dir of tempDirs.splice(0)) rmSync(dir, { recursive: true, force: true });
    await Promise.all(servers.splice(0).map((s) => new Promise((r) => s.close(r))));
});

describe('credential references', () => {
    it('resolves env references against the provided environment', async () => {
        await expect(resolveCredential('env:OPENROUTER_API_KEY', { OPENROUTER_API_KEY: SECRET }, tempHome())).resolves.toBe(SECRET);
    });

    it('reports missing credentials without leaking anything', async () => {
        const error = await resolveCredential('env:OPENROUTER_API_KEY', {}, tempHome()).catch((e) => e);
        expect(error).toBeInstanceOf(CredentialError);
        expect(error.code).toBe('MISSING_CREDENTIAL');
    });

    it('rejects arbitrary references such as file paths', async () => {
        const error = await resolveCredential('file:/etc/passwd', {}, tempHome()).catch((e) => e);
        expect(error.code).toBe('INVALID_REQUEST');
    });

    it('stores the happy-managed key with owner-only permissions', async () => {
        const home = tempHome();
        await storeOpenRouterKey(`  ${SECRET}\n`, home);
        const file = join(home, 'credentials', 'openrouter.key');
        expect(readFileSync(file, 'utf8')).toBe(SECRET);
        expect(statSync(file).mode & 0o777).toBe(0o600);
        expect(statSync(join(home, 'credentials')).mode & 0o777).toBe(0o700);
        await expect(resolveCredential('happy:openrouter', {}, home)).resolves.toBe(SECRET);
        expect(await listCredentialPresence({}, home)).toEqual([
            { ref: 'happy:openrouter', present: true },
            { ref: 'env:OPENROUTER_API_KEY', present: false },
        ]);
    });

    it('refuses values that are not key-shaped', async () => {
        await expect(storeOpenRouterKey('two words', tempHome())).rejects.toBeInstanceOf(CredentialError);
    });
});

describe('Claude Code environment', () => {
    const config = { provider: 'openrouter' as const, credentialRef: 'happy:openrouter' as const, model: 'anthropic/claude-sonnet-5.5' };

    it('points Claude Code at OpenRouter and blanks competing auth', () => {
        const env: NodeJS.ProcessEnv = { CLAUDE_CODE_OAUTH_TOKEN: 'oauth', ANTHROPIC_API_KEY: 'sk-ant', KEEP: '1' };
        applyEnvPatch(env, buildClaudeInferenceEnv({ config, baseUrl: 'https://openrouter.ai/api', authToken: SECRET }));
        expect(env).toMatchObject({
            ANTHROPIC_BASE_URL: 'https://openrouter.ai/api',
            ANTHROPIC_AUTH_TOKEN: SECRET,
            ANTHROPIC_API_KEY: '',
            ANTHROPIC_MODEL: 'anthropic/claude-sonnet-5.5',
            ANTHROPIC_DEFAULT_SONNET_MODEL: 'anthropic/claude-sonnet-5.5',
            KEEP: '1',
        });
        expect('CLAUDE_CODE_OAUTH_TOKEN' in env).toBe(false);
    });

    it('applies routing variants as slug suffixes exactly once', () => {
        expect(effectiveModelSlug({ model: 'x/y', routingVariant: 'nitro' })).toBe('x/y:nitro');
        expect(effectiveModelSlug({ model: 'x/y:floor', routingVariant: 'nitro' })).toBe('x/y:nitro');
        expect(effectiveModelSlug({ model: 'x/y' })).toBe('x/y');
    });
});

describe('model catalog', () => {
    const payload = {
        data: [
            { id: 'a/tools', context_length: 200000, supported_parameters: ['tools', 'reasoning'], pricing: { prompt: '0.000003', completion: '0.000015', input_cache_read: '0.0000003' } },
            { id: 'b/notools', supported_parameters: [], pricing: { prompt: '0', completion: '0' } },
            { bogus: true },
        ],
    };

    it('parses tools support, context and pricing', () => {
        const models = parseOpenRouterModels(payload);
        expect(models).toHaveLength(2);
        expect(models[0]).toMatchObject({ id: 'a/tools', contextLength: 200000, supportsTools: true });
        expect(models[1].supportsTools).toBe(false);
        expect(estimateCostUsd(models[0], { input: 1000, output: 100, cacheRead: 1000 })).toBeCloseTo(0.003 + 0.0015 + 0.0003, 10);
    });
});

describe('connection test', () => {
    function fakeFetch(routes: Record<string, () => Response>): typeof fetch {
        return (async (input: string | URL | Request) => {
            const url = String(input);
            for (const [suffix, make] of Object.entries(routes)) {
                if (url.endsWith(suffix)) return make();
            }
            throw new TypeError('fetch failed');
        }) as typeof fetch;
    }
    const models = () => new Response(JSON.stringify({ data: [{ id: 'a/tools', supported_parameters: ['tools'], pricing: {} }, { id: 'b/plain', supported_parameters: [], pricing: {} }] }));
    const key = () => new Response(JSON.stringify({ data: { label: 'happy', limit: 5, usage: 1.25 } }));

    it('maps a rejected key to AUTH_FAILED', async () => {
        const result = await testOpenRouterConnection({ apiKey: SECRET, model: 'a/tools', fetchImpl: fakeFetch({ '/v1/key': () => new Response('{}', { status: 401 }) }) });
        expect(result).toMatchObject({ ok: false, code: 'AUTH_FAILED' });
        expect(JSON.stringify(result)).not.toContain(SECRET);
    });

    it('maps network failure to PROVIDER_UNREACHABLE', async () => {
        const result = await testOpenRouterConnection({ apiKey: SECRET, model: 'a/tools', fetchImpl: fakeFetch({}) });
        expect(result.code).toBe('PROVIDER_UNREACHABLE');
    });

    it('detects unknown and tool-less models', async () => {
        expect((await testOpenRouterConnection({ apiKey: SECRET, model: 'zzz/none', fetchImpl: fakeFetch({ '/v1/key': key, '/v1/models': models }) })).code).toBe('MODEL_NOT_FOUND');
        expect((await testOpenRouterConnection({ apiKey: SECRET, model: 'b/plain', fetchImpl: fakeFetch({ '/v1/key': key, '/v1/models': models }) })).code).toBe('MODEL_NO_TOOLS');
    });

    it('succeeds with key details and accepts routing suffixes', async () => {
        const result = await testOpenRouterConnection({ apiKey: SECRET, model: 'a/tools:nitro', fetchImpl: fakeFetch({ '/v1/key': key, '/v1/models': models }) });
        expect(result).toMatchObject({ ok: true, key: { label: 'happy', limitUsd: 5, usageUsd: 1.25 }, model: { id: 'a/tools', supportsTools: true } });
    });

    it('reports server tools OpenRouter rejects', async () => {
        const result = await testOpenRouterConnection({
            apiKey: SECRET, model: 'a/tools', serverTools: ['web_search'],
            fetchImpl: fakeFetch({ '/v1/key': key, '/v1/models': models, '/v1/messages': () => new Response('{}', { status: 400 }) }),
        });
        expect(result.code).toBe('SERVER_TOOL_REJECTED');
    });
});

describe('usage extraction', () => {
    it('reads OpenRouter cost from an SSE stream split across chunks', () => {
        const collector = new UsageCollector(true);
        const stream = [
            'event: message_start\ndata: {"type":"message_start","message":{"model":"a/tools","usage":{"input_tokens":12,"cache_read_input_tokens":3}}}\n\n',
            'event: message_delta\ndata: {"type":"message_delta","usage":{"output_tokens":7,"cost":0.0021,"server_tool_use":{"web_search_requests":1}}}',
            '\n\n',
        ].join('');
        collector.feed(stream.slice(0, 40));
        collector.feed(stream.slice(40));
        expect(collector.finish()).toEqual({ model: 'a/tools', inputTokens: 12, outputTokens: 7, cacheReadTokens: 3, cacheWriteTokens: 0, costUsd: 0.0021, webSearchRequests: 1 });
    });

    it('returns undefined when no usage was seen', () => {
        const collector = new UsageCollector(false);
        collector.feed('{"error":"x"}');
        expect(collector.finish()).toBeUndefined();
    });

    it('decodes compressed request bodies', () => {
        const plain = Buffer.from('{"tools":[]}');
        expect(decodeBody(gzipSync(plain), 'gzip').toString()).toBe(plain.toString());
        expect(() => decodeBody(plain, 'zstd')).toThrow();
    });

    it('appends server tools without duplicating', () => {
        const body = Buffer.from(JSON.stringify({ tools: [{ name: 'Bash' }, { type: 'openrouter:datetime' }] }));
        const out = JSON.parse(withServerTools(body, ['datetime', 'web_search']).toString());
        expect(out.tools).toEqual([{ name: 'Bash' }, { type: 'openrouter:datetime' }, { type: 'openrouter:web_search' }]);
    });
});

describe('relay', () => {
    it('swaps the token, injects server tools, captures cost and enforces the budget', async () => {
        const seen: { auth?: string; tools?: unknown }[] = [];
        const upstream = await startFakeOpenRouter((req, body) => {
            seen.push({ auth: req.headers.authorization, tools: body ? JSON.parse(body).tools : undefined });
            return {
                status: 200,
                headers: { 'content-type': 'text/event-stream' },
                body: [
                    'data: {"type":"message_start","message":{"model":"a/tools","usage":{"input_tokens":10}}}\n\n',
                    'data: {"type":"message_delta","usage":{"output_tokens":5,"cost":0.6}}\n\n',
                ],
            };
        });
        const usages: RelayUsage[] = [];
        let exceededAt: number | undefined;
        const relay = await startInferenceRelay({
            apiKey: SECRET, upstream, serverTools: ['web_search'], budgetUsd: 1,
            onUsage: (u) => usages.push(u), onBudgetExceeded: (s) => { exceededAt = s; },
        });
        try {
            const send = () => fetch(`${relay.baseUrl}/v1/messages`, {
                method: 'POST',
                headers: { authorization: `Bearer ${relay.authToken}`, 'content-type': 'application/json' },
                body: JSON.stringify({ model: 'a/tools', messages: [], tools: [{ name: 'Read' }] }),
            });

            const unauthorized = await fetch(`${relay.baseUrl}/v1/messages`, { method: 'POST', headers: { authorization: 'Bearer nope' }, body: '{}' });
            expect(unauthorized.status).toBe(401);
            expect(seen).toHaveLength(0);

            const first = await send();
            expect(first.status).toBe(200);
            expect(await first.text()).toContain('message_delta');
            expect(seen[0].auth).toBe(`Bearer ${SECRET}`);
            expect(seen[0].tools).toEqual([{ name: 'Read' }, { type: 'openrouter:web_search' }]);
            expect(usages[0]).toMatchObject({ inputTokens: 10, outputTokens: 5, costUsd: 0.6 });

            await (await send()).text();
            expect(relay.spentUsd()).toBeCloseTo(1.2);
            expect(exceededAt).toBeCloseTo(1.2);

            const blocked = await send();
            expect(blocked.status).toBe(400);
            expect(((await blocked.json()) as { error: { message: string } }).error.message).toContain('budget');
            expect(seen).toHaveLength(2);
        } finally {
            await relay.close();
        }
    });

    it('returns an Anthropic-format error when OpenRouter is unreachable', async () => {
        const relay = await startInferenceRelay({ apiKey: SECRET, upstream: 'http://127.0.0.1:9' });
        try {
            const response = await fetch(`${relay.baseUrl}/v1/messages`, { method: 'POST', headers: { 'x-api-key': relay.authToken }, body: '{}' });
            expect(response.status).toBe(502);
            const body = (await response.json()) as { type: string };
            expect(body.type).toBe('error');
            expect(JSON.stringify(body)).not.toContain(SECRET);
        } finally {
            await relay.close();
        }
    });
});

describe('daemon spawn preparation', () => {
    const config = { provider: 'openrouter', credentialRef: 'env:OPENROUTER_API_KEY', model: 'a/tools', routingVariant: 'floor' };

    it('resolves the credential and selects the model', async () => {
        const prep = await prepareSpawnInference(config, 'claude', { OPENROUTER_API_KEY: SECRET });
        expect(prep.type).toBe('ok');
        if (prep.type !== 'ok') return;
        expect(prep.model).toBe('a/tools:floor');
        expect(prep.env[INFERENCE_KEY_ENV]).toBe(SECRET);
        expect(prep.env[INFERENCE_CONFIG_ENV]).not.toContain(SECRET);
    });

    it('fails clearly when the host has no key', async () => {
        const prep = await prepareSpawnInference(config, undefined, {});
        expect(prep).toMatchObject({ type: 'error' });
        if (prep.type === 'error') expect(prep.errorMessage).toContain('OPENROUTER_API_KEY');
    });

    it('rejects non-Claude harnesses and malformed configs', async () => {
        expect((await prepareSpawnInference(config, 'codex', { OPENROUTER_API_KEY: SECRET })).type).toBe('error');
        expect((await prepareSpawnInference({ provider: 'openrouter', credentialRef: 'file:/x', model: 'a' }, 'claude', {})).type).toBe('error');
        expect((await prepareSpawnInference(undefined, 'claude', {})).type).toBe('none');
    });
});

describe('resume', () => {
    it('rebuilds the provider from the acknowledgement and carries spend over', async () => {
        const ack = {
            harness: 'claude-code', provider: 'openrouter', baseUrlHost: 'openrouter.ai', credentialRef: 'env:OPENROUTER_API_KEY',
            requestedModel: 'a/tools:nitro', serverTools: ['web_search'], reportedCost: true, budgetUsd: 3, spentUsd: 1.5, appliedAt: 1,
            usage: { requests: 2, inputTokens: 10, outputTokens: 5, cacheReadTokens: 0, cacheWriteTokens: 0, costUsd: 1.5, costSource: 'reported', updatedAt: 1 },
        };
        const prep = await prepareResumeInference(ack, { OPENROUTER_API_KEY: SECRET });
        expect(prep.type).toBe('ok');
        if (prep.type !== 'ok') return;
        expect(prep.model).toBe('a/tools:nitro');
        const env: NodeJS.ProcessEnv = { ...prep.env };
        const active = await activateSessionInference(env);
        try {
            expect(env[INFERENCE_PRIOR_ENV]).toBeUndefined();
            expect(active!.ack).toMatchObject({ spentUsd: 1.5, budgetUsd: 3, serverTools: ['web_search'], usage: { requests: 2, costSource: 'reported' } });
        } finally {
            await active?.close();
        }
    });

    it('fails resume clearly if the host lost the credential, and ignores sessions without one', async () => {
        const ack = { harness: 'claude-code', provider: 'openrouter', baseUrlHost: 'openrouter.ai', credentialRef: 'env:OPENROUTER_API_KEY', requestedModel: 'a/tools', reportedCost: true, appliedAt: 1 };
        expect((await prepareResumeInference(ack, {})).type).toBe('error');
        expect((await prepareResumeInference(undefined, {})).type).toBe('none');
    });
});

describe('session activation', () => {
    it('consumes the daemon variables, hides the key from Claude Code and acknowledges the config', async () => {
        const env: NodeJS.ProcessEnv = {
            [INFERENCE_CONFIG_ENV]: JSON.stringify({ provider: 'openrouter', credentialRef: 'happy:openrouter', model: 'a/tools', serverTools: ['datetime'], budgetUsd: 2, profileName: 'Work' }),
            [INFERENCE_KEY_ENV]: SECRET,
            CLAUDE_CODE_OAUTH_TOKEN: 'oauth',
        };
        const active = await activateSessionInference(env);
        try {
            expect(active).not.toBeNull();
            expect(env[INFERENCE_CONFIG_ENV]).toBeUndefined();
            expect(env[INFERENCE_KEY_ENV]).toBeUndefined();
            expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined();
            expect(env.ANTHROPIC_BASE_URL).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/);
            expect(env.ANTHROPIC_AUTH_TOKEN).not.toBe(SECRET);
            expect(env.ANTHROPIC_API_KEY).toBe('');
            expect(Object.values(env)).not.toContain(SECRET);
            expect(active!.ack).toMatchObject({
                harness: 'claude-code', provider: 'openrouter', baseUrlHost: 'openrouter.ai', requestedModel: 'a/tools',
                serverTools: ['datetime'], reportedCost: true, budgetUsd: 2, profileName: 'Work',
            });
            const writes: unknown[] = [];
            active!.attach((handler) => writes.push(handler({ path: '/x', host: 'h' } as never)));
            active!.setActiveModel('a/tools');
            expect(writes.at(-1)).toMatchObject({ inference: { activeModel: 'a/tools' } });
        } finally {
            await active?.close();
        }
    });

    it('is a no-op without a daemon config', async () => {
        expect(await activateSessionInference({})).toBeNull();
    });
});
