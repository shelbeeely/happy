package com.slopus.happy.inference

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** OpenRouter's public model list: parsed leniently, searched, and cached for offline use. */
class OpenRouterCatalogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val body = """
        {"data":[
          {"id":"anthropic/claude-sonnet-5.5","name":"Anthropic: Claude Sonnet 5.5","context_length":1000000,
           "architecture":{"input_modalities":["text","image"]},
           "supported_parameters":["tools","reasoning","max_tokens"],
           "pricing":{"prompt":"0.000003","completion":"0.000015"}},
          {"id":"tiny/no-tools","name":"Tiny","supported_parameters":[],"pricing":{"prompt":"0","completion":"0"}},
          {"name":"no id"}
        ]}
    """.trimIndent()

    @Test
    fun parsesCapabilitiesAndPrices() {
        val models = OpenRouterCatalog.parse(body)
        assertEquals(2, models.size)
        val sonnet = models.first()
        assertEquals(1_000_000L, sonnet.contextLength)
        assertTrue(sonnet.supportsTools && sonnet.supportsReasoning && sonnet.acceptsImages)
        assertEquals(3.0, sonnet.promptPerMillion!!, 1e-9)
        assertEquals(15.0, sonnet.completionPerMillion!!, 1e-9)
        assertEquals("anthropic", sonnet.vendor)
    }

    @Test
    fun searchRanksFavouritesAndToolsFirst() {
        val models = OpenRouterCatalog.parse(body)
        assertEquals(listOf("anthropic/claude-sonnet-5.5"), OpenRouterCatalog.search(models, "", emptyList(), toolsOnly = true).map { it.id })
        assertEquals(listOf("tiny/no-tools", "anthropic/claude-sonnet-5.5"), OpenRouterCatalog.search(models, "", listOf("tiny/no-tools")).map { it.id })
        assertEquals(listOf("anthropic/claude-sonnet-5.5"), OpenRouterCatalog.search(models, "claude sonnet", emptyList()).map { it.id })
        assertTrue(OpenRouterCatalog.search(models, "nothing-matches", emptyList()).isEmpty())
    }

    @Test
    fun fetchesCachesAndFallsBackWhenOffline() = runTest {
        val server = MockWebServer()
        server.start()
        val cache = folder.root.resolve("models.json")
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/api/v1/models")).build())
            }.build()
            server.enqueue(MockResponse.Builder().code(200).body(body).build())
            var now = 1_000L
            val catalog = OpenRouterCatalog(client, cache, kotlinx.coroutines.Dispatchers.Unconfined) { now }
            catalog.refresh()
            val loaded = catalog.state.value as CatalogState.Loaded
            assertEquals(2, loaded.models.size)
            assertTrue(cache.exists())

            // A new instance reads the cache, and a failed refresh keeps it, marked stale.
            server.enqueue(MockResponse.Builder().code(500).build())
            now += OpenRouterCatalog.MAX_AGE_MS + 1
            val offline = OpenRouterCatalog(client, cache, kotlinx.coroutines.Dispatchers.Unconfined) { now }
            offline.refresh()
            val stale = offline.state.value as CatalogState.Loaded
            assertTrue(stale.stale)
            assertEquals(2, stale.models.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun failsCleanlyWithoutCache() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/api/v1/models")).build())
            }.build()
            server.enqueue(MockResponse.Builder().code(503).build())
            val catalog = OpenRouterCatalog(client, folder.root.resolve("none.json"), kotlinx.coroutines.Dispatchers.Unconfined)
            catalog.refresh()
            assertEquals(CatalogState.Failed(offline = true), catalog.state.value)
        } finally {
            server.close()
        }
    }
}
