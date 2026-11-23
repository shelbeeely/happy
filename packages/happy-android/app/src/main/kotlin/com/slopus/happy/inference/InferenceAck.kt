package com.slopus.happy.inference

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a session's computer says it applied (happy-wire `InferenceAck`,
 * session metadata `inference`). The app shows a provider as configured
 * only from this: a profile saved on the phone is a request, not a fact.
 */
@Immutable
data class InferenceAck(
    val harness: String,
    val provider: String,
    val baseUrlHost: String,
    val credentialRef: String,
    val requestedModel: String,
    /** The model Claude Code reported when it started; null until it has. */
    val activeModel: String?,
    val serverTools: List<ServerTool>,
    /** The computer reads OpenRouter's own cost for each request. */
    val reportedCost: Boolean,
    val budgetUsd: Double?,
    val spentUsd: Double?,
    val budgetExceeded: Boolean,
    val usage: InferenceUsage?,
    val profileName: String?,
    val appliedAt: Long,
) {
    companion object {
        /** From a session's raw metadata; null when absent or unreadable (never fails the whole metadata). */
        fun from(metadata: JsonObject?): InferenceAck? {
            val o = metadata?.get("inference") as? JsonObject ?: return null
            return InferenceAck(
                harness = o.string("harness") ?: return null,
                provider = o.string("provider") ?: return null,
                baseUrlHost = o.string("baseUrlHost") ?: return null,
                credentialRef = o.string("credentialRef") ?: return null,
                requestedModel = o.string("requestedModel") ?: return null,
                activeModel = o.string("activeModel"),
                serverTools = (o["serverTools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(ServerTool::fromWire) },
                reportedCost = o.boolean("reportedCost") ?: false,
                budgetUsd = o.number("budgetUsd"),
                spentUsd = o.number("spentUsd"),
                budgetExceeded = o.boolean("budgetExceeded") ?: false,
                usage = (o["usage"] as? JsonObject)?.let(InferenceUsage::from),
                profileName = o.string("profileName"),
                appliedAt = (o["appliedAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }
    }
}

/** Where a cost figure came from; shown with every figure. */
enum class CostSource(val wire: String) {
    /** OpenRouter's own number for every request. */
    Reported("reported"),
    /** Computed from published prices; may differ from the bill. */
    Estimated("estimated"),
    Unavailable("unavailable"),
    ;

    companion object {
        fun fromWire(wire: String?) = entries.find { it.wire == wire } ?: Unavailable
    }
}

@Immutable
data class InferenceUsage(
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val webSearchRequests: Long,
    val costUsd: Double?,
    val costSource: CostSource,
    val updatedAt: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens

    companion object {
        fun from(o: JsonObject) = InferenceUsage(
            requests = o.long("requests"),
            inputTokens = o.long("inputTokens"),
            outputTokens = o.long("outputTokens"),
            cacheReadTokens = o.long("cacheReadTokens"),
            cacheWriteTokens = o.long("cacheWriteTokens"),
            webSearchRequests = o.long("webSearchRequests"),
            costUsd = o.number("costUsd"),
            costSource = CostSource.fromWire(o.string("costSource")),
            updatedAt = o.long("updatedAt"),
        )
    }
}

/** Whether the provider shown for a session is the one its computer applied. */
sealed interface ProviderStatus {
    /** No provider was requested: the computer's own Claude Code configuration. */
    data object HostDefault : ProviderStatus

    /** The computer applied [ack] and Claude Code reports the requested model. */
    data class Confirmed(val ack: InferenceAck) : ProviderStatus

    /** Applied, but Claude Code hasn't reported its model yet. */
    data class AwaitingModel(val ack: InferenceAck) : ProviderStatus

    /** Claude Code reports a model other than the one requested. */
    data class ModelMismatch(val ack: InferenceAck, val active: String) : ProviderStatus

    companion object {
        fun of(ack: InferenceAck?): ProviderStatus = when {
            ack == null -> HostDefault
            ack.activeModel == null -> AwaitingModel(ack)
            modelsMatch(ack.requestedModel, ack.activeModel) -> Confirmed(ack)
            else -> ModelMismatch(ack, ack.activeModel)
        }

        /** Claude Code reports the slug it was given; routing suffixes don't change the model. */
        internal fun modelsMatch(requested: String, active: String): Boolean =
            requested == active || requested.substringBefore(':') == active.substringBefore(':')
    }
}

/**
 * The computer accepts provider profiles (happy-wire `InferenceCapability`,
 * machine metadata `inference`). Without it the computer would ignore a
 * profile, so the app doesn't offer one.
 */
@Immutable
data class InferenceCapability(
    val providers: List<String>,
    val harnesses: List<String>,
    val serverTools: Boolean,
    val budgetEnforcement: Boolean,
) {
    val openRouter: Boolean get() = InferenceProfile.PROVIDER_OPENROUTER in providers && "claude" in harnesses

    companion object {
        fun from(machineMetadata: JsonObject?): InferenceCapability? {
            val o = machineMetadata?.get("inference") as? JsonObject ?: return null
            if ((o["version"] as? JsonPrimitive)?.longOrNull != 1L) return null
            return InferenceCapability(
                providers = o.strings("providers"),
                harnesses = o.strings("harnesses"),
                serverTools = o.boolean("serverTools") ?: false,
                budgetEnforcement = o.boolean("budgetEnforcement") ?: false,
            )
        }
    }
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.long(key: String) = number(key)?.toLong() ?: 0L
private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

