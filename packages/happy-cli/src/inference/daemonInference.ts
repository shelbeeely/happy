/**
 * Daemon-side handling of phone-selected inference providers.
 *
 * - Validates an InferenceConfig from `spawn-happy-session` and resolves its
 *   credential reference on this host.
 * - Serves the `inference-status`, `inference-test` and
 *   `inference-store-credential` machine RPCs. Their params and results are
 *   end-to-end encrypted by the RPC layer like every other machine RPC.
 *
 * Nothing here logs or returns credential values.
 */

import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import {
    InferenceAckSchema,
    type InferenceConfig,
    CredentialRefSchema,
    InferenceConfigSchema,
    OpenRouterServerToolSchema,
    type InferenceCapability,
    type InferenceStatus,
    type InferenceTestResult,
} from '@slopus/happy-wire';
import { z } from 'zod';
import { CredentialError, listCredentialPresence, resolveCredential, storeOpenRouterKey } from './credentials';
import { effectiveModelSlug, testOpenRouterConnection } from './openrouter';
import { INFERENCE_CONFIG_ENV, INFERENCE_KEY_ENV, INFERENCE_PRIOR_ENV } from './sessionInference';

const execFileAsync = promisify(execFile);

/** Advertised in machine metadata so apps can tell this host is capable. */
export const INFERENCE_CAPABILITY: InferenceCapability = {
    version: 1,
    providers: ['openrouter'],
    harnesses: ['claude'],
    serverTools: true,
    budgetEnforcement: true,
};

export type SpawnInferencePreparation =
    | { type: 'none' }
    | { type: 'ok'; env: Record<string, string>; model: string }
    | { type: 'error'; errorMessage: string };

/**
 * Turn a spawn request's `inference` field into child environment.
 * The child (sessionInference.ts) consumes and deletes both variables.
 */
export async function prepareSpawnInference(
    raw: unknown,
    agent: string | undefined,
    ambientEnv: NodeJS.ProcessEnv,
): Promise<SpawnInferencePreparation> {
    if (raw === undefined || raw === null) return { type: 'none' };
    const parsed = InferenceConfigSchema.safeParse(raw);
    if (!parsed.success) {
        return { type: 'error', errorMessage: 'The provider configuration sent by the app is not valid for this Happy CLI version.' };
    }
    if (agent !== undefined && agent !== 'claude') {
        return { type: 'error', errorMessage: `OpenRouter profiles are supported for Claude Code sessions only (requested: ${agent}).` };
    }
    let key: string;
    try {
        key = await resolveCredential(parsed.data.credentialRef, ambientEnv);
    } catch (error) {
        const message = error instanceof CredentialError ? error.message : 'Could not read the provider credential on this host';
        return { type: 'error', errorMessage: message };
    }
    return {
        type: 'ok',
        model: effectiveModelSlug(parsed.data),
        env: {
            [INFERENCE_CONFIG_ENV]: JSON.stringify(parsed.data),
            [INFERENCE_KEY_ENV]: key,
        },
    };
}

async function detectOri(env: NodeJS.ProcessEnv): Promise<InferenceStatus['ori']> {
    try {
        const { stdout } = await execFileAsync('ori', ['--version'], { timeout: 3_000, env });
        return {
            installed: true,
            version: stdout.trim().split(/\s+/).pop() || undefined,
            defaultModel: env.ORI_MODEL || undefined,
        };
    } catch {
        return { installed: false };
    }
}

export async function inferenceStatus(ambientEnv: NodeJS.ProcessEnv): Promise<InferenceStatus> {
    const [credentials, ori] = await Promise.all([listCredentialPresence(ambientEnv), detectOri(ambientEnv)]);
    return { capability: INFERENCE_CAPABILITY, credentials, ori };
}

const InferenceTestParamsSchema = z.object({
    provider: z.literal('openrouter'),
    credentialRef: CredentialRefSchema,
    model: z.string().min(1).max(200),
    serverTools: z.array(OpenRouterServerToolSchema).max(8).optional(),
});

export async function inferenceTest(params: unknown, ambientEnv: NodeJS.ProcessEnv): Promise<InferenceTestResult> {
    const parsed = InferenceTestParamsSchema.safeParse(params);
    if (!parsed.success) {
        return { ok: false, code: 'INVALID_REQUEST', message: 'The connection test request is not valid' };
    }
    let apiKey: string;
    try {
        apiKey = await resolveCredential(parsed.data.credentialRef, ambientEnv);
    } catch (error) {
        return {
            ok: false,
            code: error instanceof CredentialError ? error.code : 'MISSING_CREDENTIAL',
            message: error instanceof CredentialError ? error.message : 'Could not read the provider credential on this host',
        };
    }
    return testOpenRouterConnection({ apiKey, model: parsed.data.model, serverTools: parsed.data.serverTools });
}

const StoreCredentialParamsSchema = z.object({
    provider: z.literal('openrouter'),
    apiKey: z.string(),
});

export async function inferenceStoreCredential(params: unknown): Promise<{ ok: boolean; ref?: 'happy:openrouter'; message: string }> {
    const parsed = StoreCredentialParamsSchema.safeParse(params);
    if (!parsed.success) {
        return { ok: false, message: 'The credential request is not valid' };
    }
    try {
        await storeOpenRouterKey(parsed.data.apiKey);
        return { ok: true, ref: 'happy:openrouter', message: 'Stored on this host' };
    } catch (error) {
        return { ok: false, message: error instanceof CredentialError ? error.message : 'Could not store the key on this host' };
    }
}

/**
 * Rebuild a session's provider selection from its acknowledgement so a
 * resumed session keeps the provider, model, tools and budget it started
 * with instead of silently falling back to the host's default. Prior usage
 * and spend carry over so the budget keeps counting.
 */
export async function prepareResumeInference(
    rawAck: unknown,
    ambientEnv: NodeJS.ProcessEnv,
): Promise<SpawnInferencePreparation> {
    if (rawAck === undefined || rawAck === null) return { type: 'none' };
    const ack = InferenceAckSchema.safeParse(rawAck);
    if (!ack.success) return { type: 'none' };
    const config: InferenceConfig = {
        provider: ack.data.provider,
        credentialRef: ack.data.credentialRef,
        model: ack.data.requestedModel,
        serverTools: ack.data.serverTools?.length ? ack.data.serverTools : undefined,
        budgetUsd: ack.data.budgetUsd,
        profileName: ack.data.profileName,
    };
    const prep = await prepareSpawnInference(config, 'claude', ambientEnv);
    if (prep.type !== 'ok') return prep;
    return {
        ...prep,
        env: {
            ...prep.env,
            [INFERENCE_PRIOR_ENV]: JSON.stringify({ spentUsd: ack.data.spentUsd ?? 0, usage: ack.data.usage ?? null }),
        },
    };
}
