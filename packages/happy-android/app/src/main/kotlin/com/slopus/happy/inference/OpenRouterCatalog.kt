package com.slopus.happy.inference

import androidx.compose.runtime.Immutable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One OpenRouter model as `GET /api/v1/models` describes it. Prices are USD
 * per token as published; [contextLength] and capabilities are what
 * OpenRouter reports, and absent when it reports nothing.
 */
@Immutable
data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Long?,
    val inputModalities: List<String>,
    val supportsTools: Boolean,
    val supportsReasoning: Boolean,
    val promptPrice: Double?,
    val completionPrice: Double?,
) {
    /** Vendor part of the slug: `anthropic` in `anthropic/claude-sonnet-5.5`. */
    val vendor: String get() = id.substringBefore('/')

    val acceptsImages: Boolean get() = "image" in inputModalities

    /** USD per million prompt tokens, as OpenRouter's site shows prices. */
    val promptPerMillion: Double? get() = promptPrice?.times(1_000_000)
    val completionPerMillion: Double? get() = completionPrice?.times(1_000_000)
}

sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Loaded(val models: List<OpenRouterModel>, val fetchedAt: Long, val stale: Boolean = false) : CatalogState
    /** Nothing cached and the fetch failed. */
    data class Failed(val offline: Boolean) : CatalogState
}

/**
 * OpenRouter's public model list (no key needed), cached on the phone so
 * search and the model picker work offline. This is metadata only; no
 * session content or credential is ever sent to OpenRouter from the phone.
 */
class OpenRouterCatalog(
    private val client: OkHttpClient,
    private val cacheFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()
    private val lock = Mutex()

    /** Loads the cache, then refreshes it if older than [maxAgeMs] (or when [force]). */
    suspend fun refresh(force: Boolean = false, maxAgeMs: Long = MAX_AGE_MS) = lock.withLock {
        if (mutableState.value !is CatalogState.Loaded) {
            readCache()?.let { mutableState.value = it }
        }
        val loaded = mutableState.value as? CatalogState.Loaded
        if (!force && loaded != null && now() - loaded.fetchedAt < maxAgeMs) return@withLock
        if (loaded == null) mutableState.value = CatalogState.Loading
        mutableState.value = try {
            val body = withContext(io) {
                client.newCall(Request.Builder().url(MODELS_URL).get().build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.string()
                }
            }
            val models = parse(body)
            withContext(io) { runCatching { cacheFile.writeText(Json.encodeToString(JsonObject.serializer(), cacheDocument(body, now()))) } }
            CatalogState.Loaded(models, now())
        } catch (e: IOException) {
            loaded?.copy(stale = true) ?: CatalogState.Failed(offline = true)
        } catch (e: IllegalArgumentException) {
            loaded?.copy(stale = true) ?: CatalogState.Failed(offline = false)
        }
    }

    private suspend fun readCache(): CatalogState.Loaded? = withContext(io) {
        runCatching {
            val doc = Json.parseToJsonElement(cacheFile.readText()).jsonObject
            val fetchedAt = (doc["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: return@runCatching null
            val body = (doc["body"] as? JsonPrimitive)?.content ?: return@runCatching null
            CatalogState.Loaded(parse(body), fetchedAt)
        }.getOrNull()
    }

    companion object {
        const val MODELS_URL = "https://openrouter.ai/api/v1/models"
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

        private fun cacheDocument(body: String, fetchedAt: Long) = JsonObject(mapOf("fetchedAt" to JsonPrimitive(fetchedAt), "body" to JsonPrimitive(body)))

        /** Parses the `/api/v1/models` document; entries without an id are skipped. */
        fun parse(body: String): List<OpenRouterModel> {
            val data = Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: throw IllegalArgumentException("no data")
            return data.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                val params = o.strings("supported_parameters")
                val architecture = o["architecture"] as? JsonObject
                val pricing = o["pricing"] as? JsonObject
                OpenRouterModel(
                    id = id,
                    name = o.str("name") ?: id,
                    contextLength = (o["context_length"] as? JsonPrimitive)?.longOrNull,
                    inputModalities = architecture?.strings("input_modalities").orEmpty(),
                    supportsTools = "tools" in params,
                    supportsReasoning = "reasoning" in params || "include_reasoning" in params,
                    promptPrice = pricing?.price("prompt"),
                    completionPrice = pricing?.price("completion"),
                )
            }
        }

        /**
         * Search over id and name, every word must match. Favourites first,
         * then tool-capable models (Claude Code needs tools), then by name.
         */
        fun search(models: List<OpenRouterModel>, query: String, favorites: Collection<String>, toolsOnly: Boolean = false): List<OpenRouterModel> {
            val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return models
                .asSequence()
                .filter { !toolsOnly || it.supportsTools }
                .filter { model ->
                    val haystack = "${model.id} ${model.name}".lowercase()
                    words.all { it in haystack }
                }
                .sortedWith(compareByDescending<OpenRouterModel> { it.id in favorites }.thenByDescending { it.supportsTools }.thenBy { it.name.lowercase() })
                .toList()
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        private fun JsonObject.price(key: String): Double? =
            (this[key] as? JsonPrimitive)?.let { it.content.toDoubleOrNull() ?: it.doubleOrNull }?.takeIf { it >= 0 }
    }
}
