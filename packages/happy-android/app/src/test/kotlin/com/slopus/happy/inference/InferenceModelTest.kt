package com.slopus.happy.inference

import com.slopus.happy.chat.ComposerModeResolver
import com.slopus.happy.model.AgentType
import com.slopus.happy.model.NewSessionLabels
import com.slopus.happy.model.Settings
import com.slopus.happy.model.openRouterSection
import com.slopus.happy.sync.ApiUsageCost
import com.slopus.happy.sync.ApiUsageTokens
import com.slopus.happy.sync.MachineOps
import com.slopus.happy.sync.SessionUsage
import com.slopus.happy.sync.SpawnSessionOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenRouter profiles never carry a key, survive the settings round trip
 * leniently, reach the spawn request as happy-wire's InferenceConfig, and
 * the app trusts only the computer's acknowledgement for what a session
 * runs and costs.
 */
class InferenceModelTest {
    private val profile = InferenceProfile(
        id = "p1",
        name = "Work",
        credentialRef = CredentialRefs.ENV_DEFAULT,
        model = "anthropic/claude-sonnet-5.5",
        routingVariant = RoutingVariant.Nitro,
        serverTools = listOf(ServerTool.WebSearch),
        budgetUsd = 5.0,
        warnAtUsd = 3.0,
    )

    @Test
    fun configIsHappyWireShapedAndHoldsNoSecret() {
        val config = profile.toConfig()
        assertEquals("openrouter", (config["provider"] as JsonPrimitive).content)
        assertEquals("env:OPENROUTER_API_KEY", (config["credentialRef"] as JsonPrimitive).content)
        assertEquals("anthropic/claude-sonnet-5.5", (config["model"] as JsonPrimitive).content)
        assertEquals("nitro", (config["routingVariant"] as JsonPrimitive).content)
        assertEquals(5.0, (config["budgetUsd"] as JsonPrimitive).content.toDouble(), 0.0)
        assertFalse(config.keys.any { it.contains("key", ignoreCase = true) && it != "credentialRef" })
        assertEquals("anthropic/claude-sonnet-5.5:nitro", profile.effectiveModel)
        assertEquals("anthropic/claude-sonnet-5.5:floor", profile.copy(model = "anthropic/claude-sonnet-5.5:nitro", routingVariant = RoutingVariant.Floor).effectiveModel)
    }

    @Test
    fun spawnRequestCarriesTheConfig() {
        val request = MachineOps.spawnRequest(
            SpawnSessionOptions(machineId = "m", directory = "/w", agent = "claude", modelMode = profile.effectiveModel, inference = profile.toConfig()),
        )
        assertEquals(profile.toConfig(), request["inference"])
        assertEquals("anthropic/claude-sonnet-5.5:nitro", (request["modelMode"] as JsonPrimitive).content)
        val plain = MachineOps.spawnRequest(SpawnSessionOptions(machineId = "m", directory = "/w", agent = "claude"))
        assertFalse("inference" in plain)
    }

    @Test
    fun settingsRoundTripAndLenientParsing() {
        val settings = Settings.Defaults.withInference(InferenceSettings(listOf(profile), listOf("x/y")))
        val parsed = Settings.parse(settings.toJson())
        assertEquals(listOf(profile), parsed.inference.profiles)
        assertEquals(listOf("x/y"), parsed.inference.favorites)

        // A malformed profile is dropped; the rest of the settings survive.
        val json = buildJsonObject {
            put("viewInline", true)
            putJsonObject(InferenceSettings.SETTINGS_KEY) {
                put("profiles", buildJsonArray {
                    add(profile.toJson())
                    add(buildJsonObject { put("id", "bad"); put("name", "Bad"); put("model", "no-slash") })
                    add(JsonPrimitive(3))
                })
                put("favorites", JsonPrimitive("not an array"))
            }
        }
        val lenient = Settings.parse(json)
        assertTrue(lenient.viewInline)
        assertEquals(listOf("p1"), lenient.inference.profiles.map { it.id })
        assertTrue(lenient.inference.favorites.isEmpty())
    }

    @Test
    fun problemsGuardTheEditor() {
        assertTrue(profile.problems().isEmpty())
        assertTrue(ProfileProblem.Model in profile.copy(model = "claude").problems())
        assertTrue(ProfileProblem.Credential in profile.copy(credentialRef = "file:/etc/passwd").problems())
        assertTrue(ProfileProblem.Budget in profile.copy(budgetUsd = -1.0).problems())
        assertTrue(ProfileProblem.Name in profile.copy(name = " ").problems())
        assertTrue(CredentialRefs.isValid("env:MY_KEY_2"))
        assertFalse(CredentialRefs.isValid("env:2BAD"))
    }

    @Test
    fun modelKeysResolveProfiles() {
        val settings = InferenceSettings(listOf(profile))
        assertEquals(profile, settings.profileForModelKey("openrouter:p1"))
        assertNull(settings.profileForModelKey("openrouter:gone"))
        assertNull(settings.profileForModelKey("sonnet"))
        assertEquals(listOf("a"), settings.toggleFavorite("a").favorites)
        assertTrue(settings.toggleFavorite("a").toggleFavorite("a").favorites.isEmpty())
    }

    private fun ack(active: String? = "anthropic/claude-sonnet-5.5:nitro", usage: JsonObject? = null, exceeded: Boolean = false) = buildJsonObject {
        putJsonObject("inference") {
            put("harness", "claude-code")
            put("provider", "openrouter")
            put("baseUrlHost", "openrouter.ai")
            put("credentialRef", "happy:openrouter")
            put("requestedModel", "anthropic/claude-sonnet-5.5:nitro")
            active?.let { put("activeModel", it) }
            put("serverTools", buildJsonArray { add(JsonPrimitive("web_search")) })
            put("reportedCost", true)
            put("budgetUsd", 5.0)
            put("spentUsd", 4.2)
            put("budgetExceeded", exceeded)
            put("profileName", "Work")
            put("appliedAt", 1)
            usage?.let { put("usage", it) }
        }
    }

    @Test
    fun statusComesOnlyFromTheAcknowledgement() {
        assertEquals(ProviderStatus.HostDefault, ProviderStatus.of(InferenceAck.from(buildJsonObject { put("path", "/x") })))
        assertTrue(ProviderStatus.of(InferenceAck.from(ack(active = null))) is ProviderStatus.AwaitingModel)
        assertTrue(ProviderStatus.of(InferenceAck.from(ack())) is ProviderStatus.Confirmed)
        // Claude Code may report the slug without the routing suffix.
        assertTrue(ProviderStatus.of(InferenceAck.from(ack(active = "anthropic/claude-sonnet-5.5"))) is ProviderStatus.Confirmed)
        val mismatch = ProviderStatus.of(InferenceAck.from(ack(active = "openai/gpt-6")))
        assertTrue(mismatch is ProviderStatus.ModelMismatch)
        // Malformed acknowledgement: treated as none, never a crash.
        assertNull(InferenceAck.from(buildJsonObject { putJsonObject("inference") { put("provider", 3) } }))
    }

    @Test
    fun capabilityGatesProfiles() {
        val capable = buildJsonObject {
            putJsonObject("inference") {
                put("version", 1)
                put("providers", buildJsonArray { add(JsonPrimitive("openrouter")) })
                put("harnesses", buildJsonArray { add(JsonPrimitive("claude")) })
                put("serverTools", true)
                put("budgetEnforcement", true)
            }
        }
        assertTrue(InferenceCapability.from(capable)!!.openRouter)
        assertNull(InferenceCapability.from(buildJsonObject { put("host", "h") }))

        val labels = NewSessionLabels(inferenceUnsupported = "update")
        val settings = InferenceSettings(listOf(profile))
        val offered = openRouterSection(AgentType.Claude, settings, InferenceCapability.from(capable), labels).single()
        assertEquals("openrouter:p1", offered.options.single().key)
        assertFalse(offered.options.single().disabled)
        val old = openRouterSection(AgentType.Claude, settings, null, labels).single().options.single()
        assertTrue(old.disabled)
        assertEquals("update", old.description)
        assertTrue(openRouterSection(AgentType.Codex, settings, InferenceCapability.from(capable), labels).isEmpty())
    }

    @Test
    fun spendLabelsItsSource() {
        val usage = buildJsonObject {
            put("requests", 3); put("inputTokens", 1000); put("outputTokens", 200); put("cacheReadTokens", 0); put("cacheWriteTokens", 0)
            put("costUsd", 4.2); put("costSource", "reported"); put("updatedAt", 1); put("webSearchRequests", 2)
        }
        val spend = SessionSpend.of(InferenceAck.from(ack(usage = usage)), null, warnAtUsd = 3.0)!!
        assertEquals(CostSource.Reported, spend.source)
        assertEquals(1200L, spend.totalTokens)
        assertEquals(2L, spend.webSearches)
        assertEquals(BudgetLevel.Warning, spend.level)
        assertTrue(spend.cumulative)
        assertEquals(BudgetLevel.Exceeded, SessionSpend.of(InferenceAck.from(ack(usage = usage, exceeded = true)), null, null)!!.level)

        // Without an acknowledgement, Happy CLI's per-reply cost is an estimate from list prices.
        val last = SessionUsage("claude-session", 1, ApiUsageTokens(500.0, 400.0, 100.0, 0.0, 0.0), ApiUsageCost(0.01, 0.005, 0.005))
        val estimated = SessionSpend.of(null, last, null)!!
        assertEquals(CostSource.Estimated, estimated.source)
        assertFalse(estimated.cumulative)
        assertNull(SessionSpend.of(null, null, null))
        assertEquals(BudgetLevel.None, SessionSpend.level(1.0, 3.0, 5.0, false))
        assertEquals(BudgetLevel.Warning, SessionSpend.level(4.1, null, 5.0, false))
    }

    @Test
    fun composerOffersOpenRouterModelsForOpenRouterSessions() {
        val ackValue = InferenceAck.from(ack())!!
        val options = ComposerModeResolver.openRouterModelOptions(ackValue, "openai/gpt-6", InferenceSettings(listOf(profile.copy(id = "p2", model = "x/y", routingVariant = null)), listOf("z/w")))
        assertEquals(listOf("anthropic/claude-sonnet-5.5:nitro", "openai/gpt-6", "x/y", "z/w"), options.map { it.key })
        assertTrue(options.all { it.providerName == "OpenRouter" })
    }

    @Test
    fun hostAnswersParse() {
        val status = HostCredentials.from(Json.parseToJsonElement(
            """{"capability":{},"credentials":[{"ref":"happy:openrouter","present":true},{"ref":"env:OPENROUTER_API_KEY","present":false}],"ori":{"installed":true,"version":"0.17.0","defaultModel":"x/y"}}""",
        ))!!
        assertEquals(true, status.present[CredentialRefs.HAPPY_STORED])
        assertEquals(false, status.present[CredentialRefs.ENV_DEFAULT])
        assertTrue(status.oriInstalled)

        val failed = ConnectionTest.from(Json.parseToJsonElement("""{"ok":false,"code":"AUTH_FAILED","message":"OpenRouter rejected the API key on this host"}""").jsonObject)
        assertEquals(ConnectionTest.Failed(ConnectionProblem.AuthFailed, "OpenRouter rejected the API key on this host"), failed)
        val passed = ConnectionTest.from(Json.parseToJsonElement("""{"ok":true,"message":"ok","key":{"label":"k","limitUsd":10,"usageUsd":1.5},"model":{"id":"a/b","contextLength":200000,"supportsTools":true},"serverTools":["web_search"]}""").jsonObject)
        assertEquals(ConnectionTest.Passed("k", 10.0, 1.5, 200000L, listOf(ServerTool.WebSearch)), passed)
        assertEquals(ConnectionTest.Failed(ConnectionProblem.HostOutdated, null), ConnectionTest.from(null))
        assertTrue(isMissingMethod("Method not found"))
        assertFalse(isMissingMethod("RPC method not available"))
    }
}
