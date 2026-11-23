package com.slopus.happy.ui.localai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.localai.LanguageSupport
import com.slopus.happy.localai.LocalAiError
import com.slopus.happy.localai.LocalAiState
import com.slopus.happy.ui.components.Ionicon
import com.slopus.happy.ui.components.Item
import com.slopus.happy.ui.components.ItemGroup
import com.slopus.happy.ui.components.ItemList
import com.slopus.happy.ui.components.Screen
import com.slopus.happy.ui.components.StackHeader
import com.slopus.happy.ui.page
import com.slopus.happy.ui.settings.ItemIcon
import com.slopus.happy.ui.theme.HappyTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object LocalAiSettingsRoute

object LocalAiTags {
    const val STATUS = "localai.status"
    const val DOWNLOAD = "localai.download"
    const val DICTATION = "localai.dictation"
}

fun NavGraphBuilder.localAiRoutes(graph: AppGraph, nav: NavHostController) {
    page<LocalAiSettingsRoute> {
        val state by graph.localAi.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var language by remember { mutableStateOf<LanguageSupport?>(null) }
        LaunchedEffect(Unit) {
            graph.localAi.refresh()
            language = graph.dictation.languageSupport()
        }
        LocalAiSettingsScreen(
            state = state,
            dictationAvailable = graph.dictation.available,
            language = language,
            onDownload = graph.localAi::download,
            onCancelDownload = graph.localAi::cancelDownload,
            onRecheck = { scope.launch { graph.localAi.refresh(); language = graph.dictation.languageSupport() } },
            onDownloadLanguage = {
                graph.dictation.downloadLanguage()
                language = LanguageSupport.Downloading
            },
            onBack = { nav.popBackStack() },
        )
    }
}

/**
 * Settings › AI providers › On-device AI: whether this phone has Gemini
 * Nano through AICore, its download, and on-device dictation. Everything
 * here runs on the phone; nothing is uploaded, and there is no cloud
 * fallback to switch on by accident.
 */
@Composable
fun LocalAiSettingsScreen(
    state: LocalAiState,
    dictationAvailable: Boolean,
    language: LanguageSupport?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRecheck: () -> Unit,
    onDownloadLanguage: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = HappyTheme.colors
    Screen(header = { StackHeader(stringResource(R.string.localai_settings_title), onBack) }) {
        ItemList {
            ItemGroup(title = stringResource(R.string.localai_model), footer = stringResource(R.string.localai_model_footer)) {
                Item(
                    title = stringResource(localAiStateTitle(state)),
                    subtitle = localAiStateDetail(state),
                    icon = { ItemIcon(Ionicon.PhonePortraitOutline, if (state == LocalAiState.Ready) colors.success else colors.textSecondary) },
                    showChevron = false,
                    modifier = Modifier.testTag(LocalAiTags.STATUS),
                )
                when (state) {
                    LocalAiState.NeedsDownload, is LocalAiState.DownloadFailed -> Item(
                        title = stringResource(R.string.localai_download),
                        subtitle = stringResource(R.string.localai_download_subtitle),
                        icon = { ItemIcon(Ionicon.DownloadOutline, colors.textLink) },
                        onClick = onDownload,
                        showChevron = false,
                        modifier = Modifier.testTag(LocalAiTags.DOWNLOAD),
                    )
                    is LocalAiState.Downloading -> Item(
                        title = stringResource(R.string.common_cancel),
                        onClick = onCancelDownload,
                        showChevron = false,
                    )
                    else -> Unit
                }
                Item(
                    title = stringResource(R.string.localai_recheck),
                    icon = { ItemIcon(Ionicon.RefreshOutline, colors.textLink) },
                    onClick = onRecheck,
                    showChevron = false,
                )
            }
            ItemGroup(title = stringResource(R.string.localai_dictation), footer = stringResource(R.string.localai_dictation_footer)) {
                Item(
                    title = stringResource(
                        when {
                            !dictationAvailable -> R.string.localai_dictation_unavailable
                            language == LanguageSupport.Installed -> R.string.localai_dictation_ready
                            language == LanguageSupport.Downloading -> R.string.localai_dictation_language_downloading
                            language == LanguageSupport.Downloadable -> R.string.localai_dictation_language_missing
                            language == LanguageSupport.Unsupported -> R.string.localai_dictation_language_unsupported
                            else -> R.string.localai_dictation_available
                        },
                    ),
                    icon = { ItemIcon(Ionicon.MicOutline, if (dictationAvailable) colors.textLink else colors.textSecondary) },
                    showChevron = false,
                    modifier = Modifier.testTag(LocalAiTags.DICTATION),
                )
                if (dictationAvailable && language == LanguageSupport.Downloadable) {
                    Item(
                        title = stringResource(R.string.localai_download_language),
                        icon = { ItemIcon(Ionicon.DownloadOutline, colors.textLink) },
                        onClick = onDownloadLanguage,
                        showChevron = false,
                    )
                }
            }
        }
    }
}

internal fun localAiStateTitle(state: LocalAiState): Int = when (state) {
    LocalAiState.Checking -> R.string.localai_checking
    LocalAiState.Unsupported -> R.string.localai_unsupported
    LocalAiState.NeedsDownload -> R.string.localai_needs_download
    is LocalAiState.Downloading -> R.string.localai_downloading
    LocalAiState.Ready -> R.string.localai_ready
    is LocalAiState.DownloadFailed -> R.string.localai_download_failed
}

@Composable
internal fun localAiStateDetail(state: LocalAiState): String? = when (state) {
    is LocalAiState.Downloading -> if (state.bytes > 0) stringResource(R.string.localai_downloaded_mb, (state.bytes / 1_048_576).toInt()) else null
    is LocalAiState.DownloadFailed -> stringResource(localAiErrorText(state.error))
    LocalAiState.Unsupported -> stringResource(R.string.localai_unsupported_detail)
    else -> null
}

internal fun localAiErrorText(error: LocalAiError): Int = when (error) {
    LocalAiError.Busy -> R.string.localai_error_busy
    LocalAiError.Quota -> R.string.localai_error_quota
    LocalAiError.Background -> R.string.localai_error_background
    LocalAiError.TooLarge -> R.string.localai_error_too_large
    LocalAiError.NotEnoughStorage -> R.string.localai_error_storage
    LocalAiError.NeedsSystemUpdate -> R.string.localai_error_update
    LocalAiError.NotSupported -> R.string.localai_unsupported
    LocalAiError.InvalidImage -> R.string.localai_error_image
    LocalAiError.Cancelled -> R.string.localai_error_cancelled
    LocalAiError.Network -> R.string.localai_error_network
    LocalAiError.Empty -> R.string.localai_error_empty
    LocalAiError.Unknown -> R.string.localai_error_unknown
}
