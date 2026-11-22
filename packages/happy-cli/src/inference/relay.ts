/**
 * Loopback relay between Claude Code and OpenRouter.
 *
 * Claude Code cannot add fields to its own request bodies, and it should
 * never hold the user's OpenRouter key. The relay:
 * - binds to 127.0.0.1 on a random port and requires a per-session token,
 * - swaps that token for the real key on the way upstream,
 * - appends the profile's OpenRouter server tools to Messages requests,
 * - reads OpenRouter's `usage` (including `usage.cost`) from responses so
 *   the session can report real spend, and
 * - refuses new Messages requests once a host-enforced budget is reached.
 *
 * Request and response bodies are never logged.
 */

import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import type { OpenRouterServerTool } from '@slopus/happy-wire';
import { OPENROUTER_ANTHROPIC_BASE_URL, serverToolEntries } from './openrouter';

export interface RelayUsage {
    model?: string;
    inputTokens: number;
    outputTokens: number;
    cacheReadTokens: number;
    cacheWriteTokens: number;
    /** USD as reported by OpenRouter; undefined when the response had none. */
    costUsd?: number;
    webSearchRequests?: number;
}

export interface InferenceRelay {
    baseUrl: string;
    authToken: string;
    /** Sum of OpenRouter-reported cost seen by this relay. */
    spentUsd(): number;
    close(): Promise<void>;
}

export interface InferenceRelayOptions {
    apiKey: string;
    serverTools?: readonly OpenRouterServerTool[];
    budgetUsd?: number;
    /** Starting spend, e.g. carried over when a session process restarts. */
    initialSpentUsd?: number;
    upstream?: string;
    fetchImpl?: typeof fetch;
    onUsage?: (usage: RelayUsage) => void;
    onBudgetExceeded?: (spentUsd: number) => void;
}

const MAX_REQUEST_BYTES = 64 * 1024 * 1024;
const MESSAGES_PATH = /^\/v1\/messages(?:\?|$)/;
const HOP_HEADERS = new Set(['host', 'connection', 'content-length', 'authorization', 'x-api-key', 'accept-encoding', 'keep-alive', 'transfer-encoding', 'proxy-authorization']);
const RESPONSE_SKIP_HEADERS = new Set(['content-encoding', 'content-length', 'transfer-encoding', 'connection', 'keep-alive']);

function tokenMatches(expected: Buffer, provided: string | undefined): boolean {
    if (!provided) return false;
    const candidate = Buffer.from(provided);
    return candidate.length === expected.length && timingSafeEqual(candidate, expected);
}

function presentedToken(req: IncomingMessage): string | undefined {
    const auth = req.headers.authorization;
    if (typeof auth === 'string' && auth.startsWith('Bearer ')) return auth.slice('Bearer '.length);
    const apiKey = req.headers['x-api-key'];
    return typeof apiKey === 'string' ? apiKey : undefined;
}

function anthropicError(res: ServerResponse, status: number, type: string, message: string): void {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ type: 'error', error: { type, message } }));
}

async function readBody(req: IncomingMessage): Promise<Buffer> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req) {
        size += (chunk as Buffer).length;
        if (size > MAX_REQUEST_BYTES) throw new Error('request too large');
        chunks.push(chunk as Buffer);
    }
    return Buffer.concat(chunks);
}

/** Append server tools to a Messages body, skipping ones already present. */
export function withServerTools(body: Buffer, tools: readonly OpenRouterServerTool[] | undefined): Buffer {
    if (!tools || tools.length === 0 || body.length === 0) return body;
    let parsed: Record<string, unknown>;
    try {
        parsed = JSON.parse(body.toString('utf8'));
    } catch {
        return body;
    }
    const existing = Array.isArray(parsed.tools) ? parsed.tools as { type?: unknown }[] : [];
    const present = new Set(existing.map((t) => t?.type));
    const additions = serverToolEntries(tools).filter((t) => !present.has(t.type));
    if (additions.length === 0) return body;
    parsed.tools = [...existing, ...additions];
    return Buffer.from(JSON.stringify(parsed));
}

/**
 * Incremental extractor for Anthropic-format usage, from either an SSE
 * stream or a JSON body. OpenRouter adds `usage.cost` (USD) and
 * `usage.server_tool_use` to the Anthropic usage object.
 */
export class UsageCollector {
    private pending = '';
    private readonly usage: RelayUsage = { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 };
    private sawUsage = false;

    constructor(private readonly streaming: boolean) {}

    feed(chunk: string): void {
        this.pending += chunk;
        if (!this.streaming) return;
        let newline: number;
        while ((newline = this.pending.indexOf('\n')) >= 0) {
            const line = this.pending.slice(0, newline).trim();
            this.pending = this.pending.slice(newline + 1);
            if (line.startsWith('data:')) this.consumeEvent(line.slice(5).trim());
        }
    }

    finish(): RelayUsage | undefined {
        if (this.streaming) {
            const tail = this.pending.trim();
            if (tail.startsWith('data:')) this.consumeEvent(tail.slice(5).trim());
        } else {
            this.consumeEvent(this.pending);
        }
        this.pending = '';
        return this.sawUsage ? this.usage : undefined;
    }

    private consumeEvent(data: string): void {
        if (!data || data === '[DONE]') return;
        let event: any;
        try {
            event = JSON.parse(data);
        } catch {
            return;
        }
        if (typeof event?.model === 'string') this.usage.model = event.model;
        if (typeof event?.message?.model === 'string') this.usage.model = event.message.model;
        const usage = event?.usage ?? event?.message?.usage;
        if (!usage || typeof usage !== 'object') return;
        this.sawUsage = true;
        const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);
        // message_start carries input counts; message_delta carries the
        // cumulative output count (and, on OpenRouter, the final cost).
        this.usage.inputTokens = num(usage.input_tokens) ?? this.usage.inputTokens;
        this.usage.outputTokens = num(usage.output_tokens) ?? this.usage.outputTokens;
        this.usage.cacheReadTokens = num(usage.cache_read_input_tokens) ?? this.usage.cacheReadTokens;
        this.usage.cacheWriteTokens = num(usage.cache_creation_input_tokens) ?? this.usage.cacheWriteTokens;
        const cost = num(usage.cost);
        if (cost !== undefined) this.usage.costUsd = cost;
        const searches = num(usage.server_tool_use?.web_search_requests);
        if (searches !== undefined) this.usage.webSearchRequests = searches;
    }
}

export async function startInferenceRelay(options: InferenceRelayOptions): Promise<InferenceRelay> {
    const upstream = (options.upstream ?? OPENROUTER_ANTHROPIC_BASE_URL).replace(/\/$/, '');
    const fetchImpl = options.fetchImpl ?? fetch;
    const authToken = `happy-relay-${randomBytes(24).toString('base64url')}`;
    const expected = Buffer.from(authToken);
    let spent = options.initialSpentUsd ?? 0;
    let budgetNotified = false;

    const budgetReached = (): boolean => options.budgetUsd !== undefined && spent >= options.budgetUsd;

    const handle = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
        if (!tokenMatches(expected, presentedToken(req))) {
            anthropicError(res, 401, 'authentication_error', 'Invalid relay token');
            return;
        }
        const path = req.url ?? '/';
        if (!path.startsWith('/v1/')) {
            anthropicError(res, 404, 'not_found_error', 'Unknown path');
            return;
        }
        const isMessages = req.method === 'POST' && MESSAGES_PATH.test(path);
        if (isMessages && budgetReached()) {
            anthropicError(res, 400, 'invalid_request_error',
                `Happy session budget of $${options.budgetUsd!.toFixed(2)} reached ($${spent.toFixed(4)} spent, as reported by OpenRouter). Start a new session or raise the budget.`);
            return;
        }

        let body = await readBody(req);
        if (isMessages) body = withServerTools(body, options.serverTools);

        const headers: Record<string, string> = {};
        for (const [name, value] of Object.entries(req.headers)) {
            if (HOP_HEADERS.has(name) || value === undefined) continue;
            headers[name] = Array.isArray(value) ? value.join(', ') : value;
        }
        headers.authorization = `Bearer ${options.apiKey}`;

        const controller = new AbortController();
        res.on('close', () => controller.abort());
        const upstreamResponse = await fetchImpl(`${upstream}${path}`, {
            method: req.method,
            headers,
            body: body.length > 0 && req.method !== 'GET' && req.method !== 'HEAD' ? body : undefined,
            signal: controller.signal,
        });

        const responseHeaders: Record<string, string> = {};
        upstreamResponse.headers.forEach((value, name) => {
            if (!RESPONSE_SKIP_HEADERS.has(name)) responseHeaders[name] = value;
        });
        res.writeHead(upstreamResponse.status, responseHeaders);

        const contentType = upstreamResponse.headers.get('content-type') ?? '';
        const collector = isMessages && upstreamResponse.ok
            ? new UsageCollector(contentType.includes('text/event-stream'))
            : undefined;
        const decoder = new TextDecoder();
        if (upstreamResponse.body) {
            for await (const chunk of upstreamResponse.body as unknown as AsyncIterable<Uint8Array>) {
                res.write(chunk);
                collector?.feed(decoder.decode(chunk, { stream: true }));
            }
        }
        res.end();

        const usage = collector?.finish();
        if (usage) {
            if (usage.costUsd !== undefined) spent += usage.costUsd;
            options.onUsage?.(usage);
            if (budgetReached() && !budgetNotified) {
                budgetNotified = true;
                options.onBudgetExceeded?.(spent);
            }
        }
    };

    const server = createServer((req, res) => {
        handle(req, res).catch(() => {
            if (!res.headersSent) {
                anthropicError(res, 502, 'api_error', 'Happy relay could not reach OpenRouter from this host');
            } else {
                res.destroy();
            }
        });
    });

    await new Promise<void>((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', () => resolve());
    });
    const port = (server.address() as AddressInfo).port;

    return {
        baseUrl: `http://127.0.0.1:${port}`,
        authToken,
        spentUsd: () => spent,
        close: () => new Promise<void>((resolve) => {
            server.closeAllConnections?.();
            server.close(() => resolve());
        }),
    };
}
