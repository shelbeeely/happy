/**
 * OpenRouter integration for Claude Code sessions.
 *
 * Claude Code talks to OpenRouter's Anthropic-compatible endpoint
 * (`https://openrouter.ai/api`, no `/v1`) when it is launched with:
 *   ANTHROPIC_BASE_URL=https://openrouter.ai/api
 *   ANTHROPIC_AUTH_TOKEN=<key>
 *   ANTHROPIC_API_KEY=""      (empty, not unset)
 * per OpenRouter's Claude Code guide. Happy normally points
 * ANTHROPIC_BASE_URL at a loopback relay instead (see relay.ts) so the
 * Claude Code process never sees the real key and so OpenRouter-reported
 * cost can be captured; the direct form is the fallback.
 *
 * This module owns model metadata, connection tests, and the environment
 * Claude Code is launched with. It never logs credential values.
 */

import {
    type InferenceConfig,
    type InferenceTestResult,
    type OpenRouterServerTool,
} from '@slopus/happy-wire';

export const OPENROUTER_API_ORIGIN = 'https://openrouter.ai';
export const OPENROUTER_ANTHROPIC_BASE_URL = `${OPENROUTER_API_ORIGIN}/api`;

/** Env keys that would let Claude Code authenticate against Anthropic instead. */
export const COMPETING_AUTH_ENV_KEYS = ['CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_PROFILE', 'CLAUDE_CODE_USE_BEDROCK', 'CLAUDE_CODE_USE_VERTEX'] as const;

export interface OpenRouterModel {
    id: string;
    contextLength?: number;
    supportsTools: boolean;
    /** USD per token. */
    pricing: {
        prompt: number;
        completion: number;
        cacheRead?: number;
        cacheWrite?: number;
    };
}

type FetchLike = typeof fetch;

/** Model slug with an optional routing variant (`:nitro`, `:floor`). */
export function effectiveModelSlug(config: Pick<InferenceConfig, 'model' | 'routingVariant'>): string {
    if (!config.routingVariant) return config.model;
    const base = config.model.replace(/:(nitro|floor)$/, '');
    return `${base}:${config.routingVariant}`;
}

/**
 * Environment for the Claude Code process.
 * Keys mapped to `undefined` must be deleted from the target environment.
 */
export function buildClaudeInferenceEnv(params: {
    config: InferenceConfig;
    baseUrl: string;
    authToken: string;
}): Record<string, string | undefined> {
    const model = effectiveModelSlug(params.config);
    const small = params.config.smallFastModel ?? model;
    const env: Record<string, string | undefined> = {
        ANTHROPIC_BASE_URL: params.baseUrl,
        ANTHROPIC_AUTH_TOKEN: params.authToken,
        ANTHROPIC_API_KEY: '',
        ANTHROPIC_MODEL: model,
        ANTHROPIC_DEFAULT_OPUS_MODEL: model,
        ANTHROPIC_DEFAULT_SONNET_MODEL: model,
        ANTHROPIC_DEFAULT_HAIKU_MODEL: small,
        ANTHROPIC_SMALL_FAST_MODEL: small,
        CLAUDE_CODE_SUBAGENT_MODEL: model,
    };
    for (const key of COMPETING_AUTH_ENV_KEYS) {
        env[key] = undefined;
    }
    return env;
}

/** Apply an env patch produced by buildClaudeInferenceEnv in place. */
export function applyEnvPatch(target: NodeJS.ProcessEnv, patch: Record<string, string | undefined>): void {
    for (const [key, value] of Object.entries(patch)) {
        if (value === undefined) {
            delete target[key];
        } else {
            target[key] = value;
        }
    }
}

function perToken(value: unknown): number | undefined {
    const n = typeof value === 'string' ? Number(value) : typeof value === 'number' ? value : NaN;
    return Number.isFinite(n) && n >= 0 ? n : undefined;
}

/** Parse OpenRouter's `/api/v1/models` payload. Unknown fields are ignored. */
export function parseOpenRouterModels(payload: unknown): OpenRouterModel[] {
    const data = (payload as { data?: unknown })?.data;
    if (!Array.isArray(data)) return [];
    const models: OpenRouterModel[] = [];
    for (const raw of data) {
        if (!raw || typeof raw !== 'object') continue;
        const item = raw as Record<string, any>;
        if (typeof item.id !== 'string') continue;
        const params: unknown[] = Array.isArray(item.supported_parameters) ? item.supported_parameters : [];
        models.push({
            id: item.id,
            contextLength: typeof item.context_length === 'number' ? item.context_length : undefined,
            supportsTools: params.includes('tools'),
            pricing: {
                prompt: perToken(item.pricing?.prompt) ?? 0,
                completion: perToken(item.pricing?.completion) ?? 0,
                cacheRead: perToken(item.pricing?.input_cache_read),
                cacheWrite: perToken(item.pricing?.input_cache_write),
            },
        });
    }
    return models;
}

/** Strip routing suffixes so `x:nitro` finds `x` in the catalog. */
export function catalogModelId(slug: string): string {
    return slug.replace(/:(nitro|floor|online|free|thinking|extended)$/, '');
}

export async function fetchOpenRouterModels(fetchImpl: FetchLike = fetch, signal?: AbortSignal): Promise<OpenRouterModel[]> {
    const response = await fetchImpl(`${OPENROUTER_API_ORIGIN}/api/v1/models`, { signal });
    if (!response.ok) {
        throw new Error(`OpenRouter model list returned HTTP ${response.status}`);
    }
    return parseOpenRouterModels(await response.json());
}

/** Estimate a request's cost in USD from catalog pricing. */
export function estimateCostUsd(model: OpenRouterModel | undefined, usage: {
    input: number;
    output: number;
    cacheRead?: number;
    cacheWrite?: number;
}): number | undefined {
    if (!model) return undefined;
    const p = model.pricing;
    return usage.input * p.prompt
        + usage.output * p.completion
        + (usage.cacheRead ?? 0) * (p.cacheRead ?? p.prompt)
        + (usage.cacheWrite ?? 0) * (p.cacheWrite ?? p.prompt);
}

/** Server-tool entries appended to an Anthropic Messages request body. */
export function serverToolEntries(tools: readonly OpenRouterServerTool[] | undefined): { type: string }[] {
    return (tools ?? []).map((tool) => ({ type: `openrouter:${tool}` }));
}

function isNetworkError(error: unknown): boolean {
    return error instanceof TypeError || (error instanceof Error && /fetch failed|ENOTFOUND|ECONNREFUSED|ETIMEDOUT|EAI_AGAIN|abort/i.test(`${error.message} ${(error as any).cause?.code ?? ''}`));
}

/**
 * Verify a key, a model and (optionally) server tools from the execution host.
 * Never throws; every failure maps to an InferenceTestResult code.
 */
export async function testOpenRouterConnection(params: {
    apiKey: string;
    model: string;
    serverTools?: OpenRouterServerTool[];
    fetchImpl?: FetchLike;
    timeoutMs?: number;
}): Promise<InferenceTestResult> {
    const fetchImpl = params.fetchImpl ?? fetch;
    const signal = AbortSignal.timeout(params.timeoutMs ?? 15_000);
    const headers = { Authorization: `Bearer ${params.apiKey}` };

    let key: InferenceTestResult['key'];
    try {
        const response = await fetchImpl(`${OPENROUTER_API_ORIGIN}/api/v1/key`, { headers, signal });
        if (response.status === 401 || response.status === 403) {
            return { ok: false, code: 'AUTH_FAILED', message: 'OpenRouter rejected the API key on this host' };
        }
        if (!response.ok) {
            return { ok: false, code: 'PROVIDER_UNREACHABLE', message: `OpenRouter key check returned HTTP ${response.status}` };
        }
        const body = await response.json() as { data?: Record<string, unknown> };
        const data = body.data ?? {};
        key = {
            label: typeof data.label === 'string' ? data.label : undefined,
            limitUsd: typeof data.limit === 'number' ? data.limit : null,
            usageUsd: typeof data.usage === 'number' ? data.usage : undefined,
        };
    } catch (error) {
        return {
            ok: false,
            code: 'PROVIDER_UNREACHABLE',
            message: isNetworkError(error) ? 'This host could not reach openrouter.ai' : 'OpenRouter key check failed',
        };
    }

    let models: OpenRouterModel[];
    try {
        models = await fetchOpenRouterModels(fetchImpl, signal);
    } catch {
        return { ok: false, code: 'PROVIDER_UNREACHABLE', message: 'This host could not load the OpenRouter model list', key };
    }
    const found = models.find((m) => m.id === catalogModelId(params.model));
    if (!found) {
        return { ok: false, code: 'MODEL_NOT_FOUND', message: `OpenRouter does not list the model ${params.model}`, key };
    }
    const model = { id: found.id, contextLength: found.contextLength, supportsTools: found.supportsTools };
    if (!found.supportsTools) {
        return { ok: false, code: 'MODEL_NO_TOOLS', message: `${found.id} does not support tool calling, which Claude Code requires`, key, model };
    }

    const acceptedTools: OpenRouterServerTool[] = [];
    for (const tool of params.serverTools ?? []) {
        // A one-token Messages request proves OpenRouter accepts the tool
        // type on the Anthropic-compatible endpoint Claude Code uses.
        try {
            const response = await fetchImpl(`${OPENROUTER_ANTHROPIC_BASE_URL}/v1/messages`, {
                method: 'POST',
                headers: { ...headers, 'content-type': 'application/json', 'anthropic-version': '2023-06-01' },
                body: JSON.stringify({
                    model: params.model,
                    max_tokens: 1,
                    messages: [{ role: 'user', content: 'ok' }],
                    tools: serverToolEntries([tool]),
                }),
                signal,
            });
            if (!response.ok) {
                return {
                    ok: false,
                    code: 'SERVER_TOOL_REJECTED',
                    message: `OpenRouter rejected the ${tool} server tool for ${params.model} (HTTP ${response.status})`,
                    key,
                    model,
                    serverTools: acceptedTools,
                };
            }
            await response.body?.cancel();
            acceptedTools.push(tool);
        } catch {
            return { ok: false, code: 'PROVIDER_UNREACHABLE', message: 'This host could not reach openrouter.ai', key, model, serverTools: acceptedTools };
        }
    }

    return {
        ok: true,
        message: `Connected to OpenRouter${key?.label ? ` as ${key.label}` : ''}`,
        key,
        model,
        serverTools: acceptedTools,
    };
}
