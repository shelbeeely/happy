package com.slopus.happy.ui.inference

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.inference.CatalogState
import com.slopus.happy.inference.ConnectionProblem
import com.slopus.happy.inference.ConnectionTest
import com.slopus.happy.inference.CredentialRefs
import com.slopus.happy.inference.HostCredentials
import com.slopus.happy.inference.InferenceProfile
import com.slopus.happy.inference.OpenRouterModel
import com.slopus.happy.localai.LanguageSupport
import com.slopus.happy.localai.LocalAiError
import com.slopus.happy.localai.LocalAiState
import com.slopus.happy.ui.PHONE
import com.slopus.happy.ui.PHONE_WIDTH
import com.slopus.happy.ui.bounds
import com.slopus.happy.ui.localai.LocalAiSettingsScreen
import com.slopus.happy.ui.localai.LocalAiTags
import com.slopus.happy.ui.node
import com.slopus.happy.ui.setHappyContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The companion's settings screens on a phone: Settings › AI providers (profiles,
 * computers with their key status and Store key), the profile editor (Save and
 * Test held back until the profile is valid, the test's result in words), the
 * OpenRouter model browser, and On-device AI. Rows stay inside the phone's width
 * and at least 48 dp tall for touch, also at the largest font scale.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionScreensLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private val profile = InferenceProfile(id = "p", name = "Work", model = "anthropic/claude-sonnet-5.5")
    private val machine = ProviderMachine(
        id = "m",
        name = "studio",
        online = true,
        capable = true,
        credentials = HostCredentials(mapOf(CredentialRefs.HAPPY_STORED to true, CredentialRefs.ENV_DEFAULT to false), oriInstalled = true, oriVersion = "0.17.0", oriDefaultModel = null),
    )

    @Test
    fun providersListProfilesAndComputers() {
        var stored: ProviderMachine? = null
        rule.setHappyContent {
            ProvidersScreen(
                profiles = listOf(profile),
                machines = listOf(machine, machine.copy(id = "old", name = "old-laptop", capable = false)),
                onAdd = {},
                onEdit = {},
                onStoreKey = { stored = it },
                onCheck = {},
                onLocalAi = {},
                onBack = {},
            )
        }
        rule.node(ProviderTags.PROFILE).assertIsDisplayed()
        rule.onNodeWithText("Key stored by Happy: yes", substring = true).assertExists()
        rule.onNodeWithText("Update Happy CLI on this computer to use OpenRouter").assertExists()
        // Only the capable computer offers Store key.
        assertEquals(1, rule.onAllNodesWithTag(ProviderTags.STORE_KEY, useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.node(ProviderTags.STORE_KEY).performScrollTo().performClick()
        assertEquals("m", stored?.id)
        val row = rule.node(ProviderTags.PROFILE).bounds
        assertTrue(row.right.value <= PHONE_WIDTH + 0.5f)
        assertTrue("touch target", (row.bottom - row.top).value >= 48f)
    }

    @Test
    fun editorHoldsBackAnInvalidProfile() {
        rule.setHappyContent {
            ProfileEditorScreen(
                initial = null,
                pickedModel = null,
                onPickedModelConsumed = {},
                catalogModel = { null },
                testMachines = listOf(machine),
                testing = false,
                testResult = null,
                onBrowseModels = {},
                onTest = { _, _ -> },
                onSave = {},
                onDelete = null,
                onBack = {},
            )
        }
        rule.node(ProviderTags.SAVE).performScrollTo().assertIsNotEnabled()
        rule.node(ProviderTags.TEST).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun editorSavesAValidProfileAndShowsTheTestResult() {
        var saved: InferenceProfile? = null
        rule.setHappyContent {
            ProfileEditorScreen(
                initial = profile,
                pickedModel = null,
                onPickedModelConsumed = {},
                catalogModel = { OpenRouterModel(it, "Claude Sonnet 5.5", 1_000_000, listOf("text", "image"), true, true, 0.000003, 0.000015) },
                testMachines = listOf(machine),
                testing = false,
                testResult = ConnectionTest.Failed(ConnectionProblem.AuthFailed, "OpenRouter rejected the API key on this host"),
                onBrowseModels = {},
                onTest = { _, _ -> },
                onSave = { saved = it },
                onDelete = {},
                onBack = {},
            )
        }
        rule.node(ProviderTags.TEST_RESULT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OpenRouter rejected the key").assertExists()
        rule.node(ProviderTags.TEST).performScrollTo().assertIsEnabled()
        rule.node(ProviderTags.SAVE).performScrollTo().assertIsEnabled().performClick()
        assertEquals(profile, saved)
    }

    @Test
    fun modelBrowserShowsFacts() {
        val models = listOf(
            OpenRouterModel("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", 1_000_000, listOf("text"), true, true, 0.000003, 0.000015),
            OpenRouterModel("tiny/no-tools", "Tiny", null, emptyList(), false, false, 0.0, 0.0),
        )
        rule.setHappyContent {
            ModelBrowserScreen(CatalogState.Loaded(models, 1), favorites = emptyList(), onToggleFavorite = {}, onPick = {}, onRetry = {}, onBack = {})
        }
        rule.onNodeWithText("Claude Sonnet 5.5").assertIsDisplayed()
        rule.onNodeWithText("1M context", substring = true).assertExists()
        // Tool calling only is on by default: the tool-less model is hidden.
        rule.onNodeWithText("Tiny").assertDoesNotExist()
    }

    @Test
    fun localAiSettingsStates() {
        rule.setHappyContent {
            LocalAiSettingsScreen(
                state = LocalAiState.DownloadFailed(LocalAiError.NotEnoughStorage),
                dictationAvailable = true,
                language = LanguageSupport.Downloadable,
                onDownload = {},
                onCancelDownload = {},
                onRecheck = {},
                onDownloadLanguage = {},
                onBack = {},
            )
        }
        rule.node(LocalAiTags.STATUS).assertIsDisplayed()
        rule.onNodeWithText("Not enough storage for the on-device model.").assertExists()
        rule.node(LocalAiTags.DOWNLOAD).assertIsDisplayed()
        rule.onNodeWithText("Your language needs a download").assertExists()
    }

    @Test
    @Config(qualifiers = PHONE, fontScale = 2.0f)
    fun largeTextStaysInside() {
        rule.setHappyContent {
            LocalAiSettingsScreen(LocalAiState.Unsupported, dictationAvailable = false, language = null, onDownload = {}, onCancelDownload = {}, onRecheck = {}, onDownloadLanguage = {}, onBack = {})
        }
        val status = rule.node(LocalAiTags.STATUS).bounds
        assertTrue(status.right.value <= PHONE_WIDTH + 0.5f)
        rule.onNodeWithText("Not available on this phone").assertExists()
    }
}
