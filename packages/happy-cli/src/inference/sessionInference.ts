/**
 * Session-side activation of a phone-selected inference provider.
 *
 * The daemon resolves the credential reference and hands this process two
 * environment variables: HAPPY_INFERENCE_CONFIG (JSON, no secrets) and
 * HAPPY_INFERENCE_KEY (the resolved key). This module removes both from the
 * environment before Claude Code starts, starts the loopback relay, points
 * Claude Code at it, and maintains the `inference` acknowledgement in
 * encrypted session metadata. The app only shows a provider as configured
 * from that acknowledgement.
 */

import {
    InferenceAckSchema,
    InferenceConfigSchema,
    type InferenceAck,
    type InferenceConfig,
} from '@slopus/happy-wire';
import { logger } from '@/ui/logger';
import { z } from 'zod';
import type { Metadata } from '@/api/types';
import {
    OPENROUTER_ANTHROPIC_BASE_URL,
    applyEnvPatch,
    buildClaudeInferenceEnv,
    catalogModelId,
    effectiveModelSlug,
    estimateCostUsd,
    fetchOpenRouterModels,
    type OpenRouterModel,
} from './openrouter';
import { startInferenceRelay, type InferenceRelay, type RelayUsage } from './relay';
import { ensureLocalProxyBypass } from '@/claude/utils/proxyBypass';

export const INFERENCE_CONFIG_ENV = 'HAPPY_INFERENCE_CONFIG';
export const INFERENCE_KEY_ENV = 'HAPPY_INFERENCE_KEY';
/**
 * Integration tests only: a loopback stand-in for OpenRouter. Anything that
 * is not http://127.0.0.1 or http://localhost is ignored, so this can never
 * send the key to another host.
 */
export const INFERENCE_UPSTREAM_ENV = 'HAPPY_INFERENCE_TEST_UPSTREAM';

function loopbackUpstream(value: string | undefined): string | undefined {
    if (!value) return undefined;
    try {
        const url = new URL(value);
        return url.protocol === 'http:' && (url.hostname === '127.0.0.1' || url.hostname === 'localhost') ? value : undefined;
    } catch {
        return undefined;
    }
}

/** Usage and spend carried over when the daemon resumes a session. */
export const INFERENCE_PRIOR_ENV = 'HAPPY_INFERENCE_PRIOR';

const METADATA_FLUSH_MS = 2_000;

const InferencePriorSchema = z.object({
    spentUsd: z.number().nonnegative(),
    usage: InferenceAckSchema.shape.usage.unwrap().nullable(),
}).optional();

type MetadataUpdater = (handler: (metadata: Metadata) => Metadata) => void;

export interface ActiveInference {
    config: InferenceConfig;
    /** Initial acknowledgement to embed in the session's first metadata. */
    ack: InferenceAck;
    /** Connect usage/budget reporting to the live session client. */
    attach(updateMetadata: MetadataUpdater): void;
    /** Record the model Claude Code reported in `system/init`. */
    setActiveModel(model: string | undefined): void;
    /** Estimate cost of SDK-reported usage with OpenRouter catalog pricing. */
    estimateCost(usage: { input: number; output: number; cacheRead?: number; cacheWrite?: number }, model?: string): number | undefined;
    close(): Promise<void>;
}

/**
 * Read and consume the daemon-provided inference config. Returns null when
 * this session was not launched with a provider selection.
 */
export async function activateSessionInference(env: NodeJS.ProcessEnv = process.env): Promise<ActiveInference | null> {
    const rawConfig = env[INFERENCE_CONFIG_ENV];
    const apiKey = env[INFERENCE_KEY_ENV];
    const rawPrior = env[INFERENCE_PRIOR_ENV];
    delete env[INFERENCE_CONFIG_ENV];
    delete env[INFERENCE_KEY_ENV];
    delete env[INFERENCE_PRIOR_ENV];
    if (!rawConfig) return null;

    let config: InferenceConfig;
    try {
        config = InferenceConfigSchema.parse(JSON.parse(rawConfig));
    } catch {
        throw new Error('Invalid inference configuration from the Happy daemon');
    }
    if (!apiKey) {
        throw new Error(`No credential resolved for ${config.credentialRef}`);
    }

    const ack: InferenceAck = {
        harness: 'claude-code',
        provider: config.provider,
        baseUrlHost: new URL(OPENROUTER_ANTHROPIC_BASE_URL).host,
        credentialRef: config.credentialRef,
        requestedModel: effectiveModelSlug(config),
        serverTools: [],
        reportedCost: false,
        budgetUsd: config.budgetUsd,
        spentUsd: 0,
        profileName: config.profileName,
        appliedAt: Date.now(),
    };

    const prior = InferencePriorSchema.safeParse((() => {
        try { return rawPrior ? JSON.parse(rawPrior) : undefined; } catch { return undefined; }
    })());
    if (prior.success && prior.data) {
        ack.spentUsd = prior.data.spentUsd;
        ack.usage = prior.data.usage ?? undefined;
    }

    let updateMetadata: MetadataUpdater | null = null;
    let flushTimer: NodeJS.Timeout | null = null;
    const seed = ack.usage;
    const totals = {
        requests: seed?.requests ?? 0,
        inputTokens: seed?.inputTokens ?? 0,
        outputTokens: seed?.outputTokens ?? 0,
        cacheReadTokens: seed?.cacheReadTokens ?? 0,
        cacheWriteTokens: seed?.cacheWriteTokens ?? 0,
        webSearchRequests: seed?.webSearchRequests ?? 0,
        costUsd: seed?.costUsd ?? 0,
        reported: seed?.costSource === 'reported' ? 1 : 0,
        estimated: seed?.costSource === 'estimated' ? 1 : 0,
        uncosted: seed?.costSource === 'unavailable' ? 1 : 0,
    };

    const flush = (): void => {
        flushTimer = null;
        updateMetadata?.((metadata) => ({ ...metadata, inference: { ...ack } }));
    };
    const scheduleFlush = (): void => {
        if (!updateMetadata || flushTimer) return;
        flushTimer = setTimeout(flush, METADATA_FLUSH_MS);
    };

    let catalog: OpenRouterModel[] = [];
    // Pricing is only needed for estimates; failure leaves cost unavailable.
    void fetchOpenRouterModels().then((models) => { catalog = models; }).catch(() => {
        logger.debug('[inference] OpenRouter model list unavailable for cost estimates');
    });
    const catalogModel = (slug: string | undefined): OpenRouterModel | undefined => {
        const id = catalogModelId(slug ?? ack.activeModel ?? ack.requestedModel);
        return catalog.find((m) => m.id === id);
    };

    const onUsage = (usage: RelayUsage): void => {
        totals.requests += 1;
        totals.inputTokens += usage.inputTokens;
        totals.outputTokens += usage.outputTokens;
        totals.cacheReadTokens += usage.cacheReadTokens;
        totals.cacheWriteTokens += usage.cacheWriteTokens;
        totals.webSearchRequests += usage.webSearchRequests ?? 0;
        if (usage.costUsd !== undefined) {
            totals.costUsd += usage.costUsd;
            totals.reported += 1;
        } else {
            const estimate = estimateCostUsd(catalogModel(usage.model), {
                input: usage.inputTokens,
                output: usage.outputTokens,
                cacheRead: usage.cacheReadTokens,
                cacheWrite: usage.cacheWriteTokens,
            });
            if (estimate === undefined) {
                totals.uncosted += 1;
            } else {
                totals.costUsd += estimate;
                totals.estimated += 1;
            }
        }
        // Reported only when every request carried OpenRouter's own cost;
        // any estimate or gap downgrades the label for the whole session.
        const costSource = totals.uncosted > 0 ? 'unavailable' : (totals.estimated > 0 ? 'estimated' : 'reported');
        ack.usage = {
            requests: totals.requests,
            inputTokens: totals.inputTokens,
            outputTokens: totals.outputTokens,
            cacheReadTokens: totals.cacheReadTokens,
            cacheWriteTokens: totals.cacheWriteTokens,
            webSearchRequests: totals.webSearchRequests || undefined,
            costUsd: costSource === 'unavailable' ? undefined : totals.costUsd,
            costSource,
            updatedAt: Date.now(),
        };
        ack.spentUsd = relay?.spentUsd() ?? ack.spentUsd;
        scheduleFlush();
    };

    let relay: InferenceRelay | null = null;
    try {
        relay = await startInferenceRelay({
            apiKey,
            upstream: loopbackUpstream(env[INFERENCE_UPSTREAM_ENV]),
            serverTools: config.serverTools,
            budgetUsd: config.budgetUsd,
            initialSpentUsd: ack.spentUsd,
            onUsage,
            onBudgetExceeded: (spent) => {
                ack.budgetExceeded = true;
                ack.spentUsd = spent;
                if (flushTimer) clearTimeout(flushTimer);
                flush();
            },
        });
        ack.reportedCost = true;
        ack.serverTools = config.serverTools ?? [];
        ack.baseUrlHost = new URL(OPENROUTER_ANTHROPIC_BASE_URL).host;
        applyEnvPatch(env, buildClaudeInferenceEnv({ config, baseUrl: relay.baseUrl, authToken: relay.authToken }));
        ensureLocalProxyBypass(env);
    } catch (error) {
        // Direct mode: Claude Code holds the key; no server tools, no
        // reported cost and no host budget. The acknowledgement says so.
        logger.debug('[inference] Relay failed to start, using direct OpenRouter connection');
        relay = null;
        ack.reportedCost = false;
        ack.serverTools = [];
        ack.budgetUsd = undefined;
        applyEnvPatch(env, buildClaudeInferenceEnv({ config, baseUrl: OPENROUTER_ANTHROPIC_BASE_URL, authToken: apiKey }));
    }

    return {
        config,
        ack,
        attach(updater) {
            updateMetadata = updater;
            flush();
        },
        setActiveModel(model) {
            if (!model || model === ack.activeModel) return;
            ack.activeModel = model;
            if (flushTimer) clearTimeout(flushTimer);
            flush();
        },
        estimateCost(usage, model) {
            return estimateCostUsd(catalogModel(model), usage);
        },
        async close() {
            if (flushTimer) {
                clearTimeout(flushTimer);
                flush();
            }
            await relay?.close();
        },
    };
}

/**
 * The provider activated for this process, if any. One Happy session runs
 * per process, so launchers read it here instead of threading it through
 * every loop option.
 */
export const sessionInference: { current: ActiveInference | null } = { current: null };
