package com.slopus.happy.inference

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * OpenRouter profiles: which inference provider a Claude Code session on a
 * paired computer should use. The harness (Claude Code through Happy CLI)
 * is chosen separately; a profile only names the provider, the model and
 * where on the computer the key lives.
 *
 * A profile never holds a key. [credentialRef] names a key the computer
 * keeps (happy-wire `CredentialRefSchema`), and the computer resolves it at
 * launch. Profiles sync with the account's encrypted settings.
 */
@Immutable
data class InferenceProfile(
    val id: String,
    val name: String,
    val credentialRef: String = CredentialRefs.HAPPY_STORED,
    /** OpenRouter model slug, e.g. `anthropic/claude-sonnet-5.5`. */
    val model: String,
    /** Claude Code's background model; null uses [model]. */
    val smallFastModel: String? = null,
    val routingVariant: RoutingVariant? = null,
    val serverTools: List<ServerTool> = emptyList(),
    /** A spend cap the computer enforces; null means none. */
    val budgetUsd: Double? = null,
    /** Warn in the chat once the session has spent this much. */
    val warnAtUsd: Double? = null,
) {
    /** The key the new-session model picker uses for this profile. */
    val modelKey: String get() = MODEL_KEY_PREFIX + id

    /** The slug the computer will request, routing suffix included. */
    val effectiveModel: String
        get() = routingVariant?.let { model.replace(ROUTING_SUFFIX, "") + ":" + it.wire } ?: model

    /** happy-wire `InferenceConfig` for `spawn-happy-session`. Contains no secret. */
    fun toConfig(): JsonObject = buildJsonObject {
        put("provider", PROVIDER_OPENROUTER)
        put("credentialRef", credentialRef)
        put("model", model)
        smallFastModel?.let { put("smallFastModel", it) }
        routingVariant?.let { put("routingVariant", it.wire) }
        if (serverTools.isNotEmpty()) put("serverTools", buildJsonArray { serverTools.forEach { add(JsonPrimitive(it.wire)) } })
        budgetUsd?.let { put("budgetUsd", it) }
        put("profileName", name.take(80))
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("credentialRef", credentialRef)
        put("model", model)
        smallFastModel?.let { put("smallFastModel", it) }
        routingVariant?.let { put("routingVariant", it.wire) }
        put("serverTools", buildJsonArray { serverTools.forEach { add(JsonPrimitive(it.wire)) } })
        budgetUsd?.let { put("budgetUsd", it) }
        warnAtUsd?.let { put("warnAtUsd", it) }
    }

    /** What the editor must fix before the profile can be saved. */
    fun problems(): Set<ProfileProblem> = buildSet {
        if (name.isBlank()) add(ProfileProblem.Name)
        if (!MODEL_SLUG.matches(model)) add(ProfileProblem.Model)
        if (smallFastModel != null && !MODEL_SLUG.matches(smallFastModel)) add(ProfileProblem.Model)
        if (!CredentialRefs.isValid(credentialRef)) add(ProfileProblem.Credential)
        if (budgetUsd != null && (budgetUsd <= 0 || budgetUsd > 100_000)) add(ProfileProblem.Budget)
        if (warnAtUsd != null && warnAtUsd <= 0) add(ProfileProblem.Budget)
    }

    companion object {
        const val PROVIDER_OPENROUTER = "openrouter"
        const val MODEL_KEY_PREFIX = "openrouter:"
        private val ROUTING_SUFFIX = Regex(":(nitro|floor)$")
        private val MODEL_SLUG = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._:@+-]+$")

        /** Lenient: a malformed profile is dropped, never thrown, so one bad entry can't reset settings. */
        fun parse(element: JsonElement): InferenceProfile? {
            val o = element as? JsonObject ?: return null
            fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            val profile = InferenceProfile(
                id = string("id") ?: return null,
                name = string("name") ?: return null,
                credentialRef = string("credentialRef") ?: CredentialRefs.HAPPY_STORED,
                model = string("model") ?: return null,
                smallFastModel = string("smallFastModel"),
                routingVariant = RoutingVariant.fromWire(string("routingVariant")),
                serverTools = (o["serverTools"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.content?.let(ServerTool::fromWire) }
                    .distinct(),
                budgetUsd = number("budgetUsd"),
                warnAtUsd = number("warnAtUsd"),
            )
            return profile.takeIf { it.problems().isEmpty() }
        }
    }
}

enum class ProfileProblem { Name, Model, Credential, Budget }

enum class RoutingVariant(val wire: String) {
    /** Prefer the fastest providers. */
    Nitro("nitro"),
    /** Prefer the cheapest providers. */
    Floor("floor"),
    ;

    companion object {
        fun fromWire(wire: String?) = entries.find { it.wire == wire }
    }
}

/** OpenRouter server tools the computer may add to requests (happy-wire `OpenRouterServerToolSchema`). */
enum class ServerTool(val wire: String) {
    WebSearch("web_search"),
    WebFetch("web_fetch"),
    Datetime("datetime"),
    ;

    companion object {
        fun fromWire(wire: String?) = entries.find { it.wire == wire }
    }
}

/** Credential references the computer understands (happy-wire `CredentialRefSchema`). */
object CredentialRefs {
    /** The key stored by `happy auth openrouter` or by this app's "Store key on computer". */
    const val HAPPY_STORED = "happy:openrouter"
    const val ENV_DEFAULT = "env:OPENROUTER_API_KEY"
    private val ENV = Regex("^env:[A-Za-z_][A-Za-z0-9_]{0,127}$")

    fun isValid(ref: String) = ref == HAPPY_STORED || ENV.matches(ref)

    /** The variable name of an `env:` reference, else null. */
    fun envName(ref: String) = ref.removePrefix("env:").takeIf { ref.startsWith("env:") }
}

/**
 * The account's provider settings: its profiles and favourite OpenRouter
 * models. Stored under the settings key `inferenceProfiles`.
 */
@Immutable
data class InferenceSettings(
    val profiles: List<InferenceProfile> = emptyList(),
    val favorites: List<String> = emptyList(),
) {
    fun profile(id: String?) = id?.let { wanted -> profiles.find { it.id == wanted } }

    /** The profile a model picker key names, if it names one. */
    fun profileForModelKey(key: String?): InferenceProfile? =
        key?.takeIf { it.startsWith(InferenceProfile.MODEL_KEY_PREFIX) }?.let { profile(it.removePrefix(InferenceProfile.MODEL_KEY_PREFIX)) }

    fun upsert(profile: InferenceProfile) = copy(profiles = profiles.filterNot { it.id == profile.id } + profile)

    fun remove(id: String) = copy(profiles = profiles.filterNot { it.id == id })

    fun toggleFavorite(model: String) = copy(favorites = if (model in favorites) favorites - model else favorites + model)

    fun toJson(): JsonObject = buildJsonObject {
        put("version", 1)
        put("profiles", JsonArray(profiles.map { it.toJson() }))
        put("favorites", JsonArray(favorites.map(::JsonPrimitive)))
    }

    companion object {
        const val SETTINGS_KEY = "inferenceProfiles"

        /** Never throws: anything unreadable is dropped. */
        fun parse(element: JsonElement?): InferenceSettings {
            val o = element as? JsonObject ?: return InferenceSettings()
            val profiles = (o["profiles"] as? JsonArray).orEmpty().mapNotNull(InferenceProfile::parse).distinctBy { it.id }
            val favorites = (o["favorites"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .distinct()
                .take(200)
            return InferenceSettings(profiles, favorites)
        }
    }
}
