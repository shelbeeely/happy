import * as z from 'zod';

/**
 * Inference provider configuration shared by the apps and the CLI.
 *
 * The execution harness (Claude Code through happy-cli, or Happy Agent) is
 * chosen separately by the session's agent type. This schema only describes
 * which inference provider the harness should talk to.
 *
 * Secrets never travel in these objects. `credentialRef` names where the
 * execution host keeps the key:
 * - `env:NAME`   — an environment variable visible to the daemon
 * - `happy:openrouter` — the file written by `happy auth openrouter`
 *   (or the `inference-store-credential` machine RPC)
 */

export const INFERENCE_PROVIDER_OPENROUTER = 'openrouter' as const;

export const CredentialRefSchema = z.string().regex(/^(env:[A-Za-z_][A-Za-z0-9_]{0,127}|happy:openrouter)$/);
export type CredentialRef = z.infer<typeof CredentialRefSchema>;

/** OpenRouter server tools the host may append to Messages requests. */
export const OpenRouterServerToolSchema = z.enum(['web_search', 'web_fetch', 'datetime']);
export type OpenRouterServerTool = z.infer<typeof OpenRouterServerToolSchema>;

/** OpenRouter routing shortcuts expressed as model-slug suffixes. */
export const OpenRouterRoutingVariantSchema = z.enum(['nitro', 'floor']);
export type OpenRouterRoutingVariant = z.infer<typeof OpenRouterRoutingVariantSchema>;

export const InferenceConfigSchema = z.object({
  provider: z.literal(INFERENCE_PROVIDER_OPENROUTER),
  credentialRef: CredentialRefSchema,
  /** OpenRouter model slug, e.g. `anthropic/claude-sonnet-4.5`. */
  model: z.string().min(1).max(200),
  /** Optional model for Claude Code's small/fast background calls. */
  smallFastModel: z.string().min(1).max(200).optional(),
  routingVariant: OpenRouterRoutingVariantSchema.optional(),
  serverTools: z.array(OpenRouterServerToolSchema).max(8).optional(),
  /** Host-enforced spend cap for the session in USD. */
  budgetUsd: z.number().positive().max(100_000).optional(),
  /** Display name of the phone-side profile, for the acknowledgement only. */
  profileName: z.string().max(80).optional(),
});
export type InferenceConfig = z.infer<typeof InferenceConfigSchema>;

/**
 * What the spawned session actually applied, written to session metadata by
 * the host. Clients show a provider as configured only from this object.
 */
export const InferenceAckSchema = z.object({
  harness: z.string(),
  provider: z.literal(INFERENCE_PROVIDER_OPENROUTER),
  baseUrlHost: z.string(),
  credentialRef: CredentialRefSchema,
  requestedModel: z.string(),
  /** Model reported by the harness itself (Claude Code `system/init`). */
  activeModel: z.string().optional(),
  serverTools: z.array(OpenRouterServerToolSchema).optional(),
  /** True when the host relay captures OpenRouter-reported cost. */
  reportedCost: z.boolean(),
  budgetUsd: z.number().optional(),
  /** Cumulative spend the host has counted against the budget. */
  spentUsd: z.number().optional(),
  budgetExceeded: z.boolean().optional(),
  /**
   * Cumulative session usage measured on the host. Lives in encrypted
   * session metadata so cost labels survive app restarts.
   */
  usage: z.object({
    requests: z.number(),
    inputTokens: z.number(),
    outputTokens: z.number(),
    cacheReadTokens: z.number(),
    cacheWriteTokens: z.number(),
    webSearchRequests: z.number().optional(),
    costUsd: z.number().optional(),
    costSource: z.enum(['reported', 'estimated', 'unavailable']),
    updatedAt: z.number(),
  }).optional(),
  profileName: z.string().optional(),
  appliedAt: z.number(),
});
export type InferenceAck = z.infer<typeof InferenceAckSchema>;

/** Advertised in machine metadata by hosts that understand InferenceConfig. */
export const InferenceCapabilitySchema = z.object({
  version: z.literal(1),
  providers: z.array(z.literal(INFERENCE_PROVIDER_OPENROUTER)),
  harnesses: z.array(z.string()),
  serverTools: z.boolean(),
  budgetEnforcement: z.boolean(),
});
export type InferenceCapability = z.infer<typeof InferenceCapabilitySchema>;

export const InferenceTestErrorCodeSchema = z.enum([
  'MISSING_CREDENTIAL',
  'AUTH_FAILED',
  'MODEL_NOT_FOUND',
  'MODEL_NO_TOOLS',
  'SERVER_TOOL_REJECTED',
  'PROVIDER_UNREACHABLE',
  'INVALID_REQUEST',
]);
export type InferenceTestErrorCode = z.infer<typeof InferenceTestErrorCodeSchema>;

export const InferenceTestResultSchema = z.object({
  ok: z.boolean(),
  code: InferenceTestErrorCodeSchema.optional(),
  message: z.string(),
  model: z.object({
    id: z.string(),
    contextLength: z.number().optional(),
    supportsTools: z.boolean(),
  }).optional(),
  key: z.object({
    label: z.string().optional(),
    limitUsd: z.number().nullable().optional(),
    usageUsd: z.number().optional(),
  }).optional(),
  /** Server tools OpenRouter accepted on the Messages API in this probe. */
  serverTools: z.array(OpenRouterServerToolSchema).optional(),
});
export type InferenceTestResult = z.infer<typeof InferenceTestResultSchema>;

export const InferenceStatusSchema = z.object({
  capability: InferenceCapabilitySchema,
  credentials: z.array(z.object({
    ref: CredentialRefSchema,
    present: z.boolean(),
  })),
  ori: z.object({
    installed: z.boolean(),
    version: z.string().optional(),
    defaultModel: z.string().optional(),
  }),
});
export type InferenceStatus = z.infer<typeof InferenceStatusSchema>;

/** Where a reported cost number came from. */
export const CostSourceSchema = z.enum(['reported', 'estimated', 'unavailable']);
export type CostSource = z.infer<typeof CostSourceSchema>;
