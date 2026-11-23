package com.slopus.happy.ui.localai

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.ToolState
import com.slopus.happy.inference.BudgetLevel
import com.slopus.happy.inference.CostSource
import com.slopus.happy.inference.InferenceAck
import com.slopus.happy.inference.ProviderStatus
import com.slopus.happy.inference.SessionSpend
import com.slopus.happy.localai.DictationState
import com.slopus.happy.localai.ExplainKind
import com.slopus.happy.localai.LocalAiError
import com.slopus.happy.localai.LocalAiException
import com.slopus.happy.localai.LocalAiState
import com.slopus.happy.localai.LocalAssistant
import com.slopus.happy.localai.ScreenshotDraft
import com.slopus.happy.ui.components.Icon
import com.slopus.happy.ui.components.Ionicon
import com.slopus.happy.ui.components.ItemSpec
import com.slopus.happy.ui.inference.formatTokens
import com.slopus.happy.ui.inference.formatUsd
import com.slopus.happy.ui.inference.serverToolName
import com.slopus.happy.ui.theme.HappyTheme
import com.slopus.happy.ui.theme.happyTextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CompanionTags {
    const val PROVIDER = "companion.provider"
    const val BUDGET = "companion.budget"
    const val CATCH_UP = "companion.catchUp"
    const val IMPROVE = "companion.improve"
    const val EXPLAIN = "companion.explain"
    const val SCREENSHOT = "companion.screenshot"
    const val MIC = "companion.mic"
    const val SHEET = "companion.sheet"
    const val ACCEPT = "companion.accept"
}

/** What the open local sheet is doing. */
private sealed interface Panel {
    data class Result(val title: Int, val text: String, val running: Boolean, val error: LocalAiError?, val replaceDraft: Boolean) : Panel
    data class Improve(val original: String, val text: String, val running: Boolean, val error: LocalAiError?) : Panel
    data class ExplainPick(val candidates: List<ExplainCandidate>) : Panel
    data class Screenshot(val image: Bitmap) : Panel
    data object Provider : Panel
}

internal data class ExplainCandidate(val label: Int, val kind: ExplainKind, val text: String)

/**
 * The strip over a session's composer:
 *
 * - The provider line: who serves the session (from the computer's
 *   acknowledgement only), its model, and spend with where the number came
 *   from. Tapping opens the details.
 * - A budget warning when the session nears or passes its limits.
 * - On-device actions (Catch me up, Improve, Explain, Screenshot) and
 *   push-to-talk. Each result is a draft in a sheet; only Accept puts it in
 *   the composer, and only Send sends it. Labelled "On device" because
 *   none of it leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionCompanion(
    graph: AppGraph,
    metadata: JsonObject?,
    messages: List<ChatMessage>,
    spend: SessionSpend?,
    draft: String,
    onDraftChange: (String) -> Unit,
) {
    val colors = HappyTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val ack = remember(metadata) { InferenceAck.from(metadata) }
    val status = ProviderStatus.of(ack)
    val aiState by graph.localAi.state.collectAsStateWithLifecycle()
    val dictation by graph.dictation.state.collectAsStateWithLifecycle()
    val assistant = remember(graph) { LocalAssistant(graph.localAi) }
    val latestMessages by rememberUpdatedState(messages)
    val latestDraft by rememberUpdatedState(draft)
    var panel by remember { mutableStateOf<Panel?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var needsDownload by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { graph.localAi.refresh() }

    // A subtle haptic once when the session crosses a budget line.
    var lastLevel by rememberSaveable { mutableStateOf(BudgetLevel.None) }
    LaunchedEffect(spend?.level) {
        val level = spend?.level ?: BudgetLevel.None
        if (level.ordinal > lastLevel.ordinal) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        }
        lastLevel = level
    }

    // Push-to-talk: a finished transcript joins the draft for editing.
    LaunchedEffect(dictation) {
        val done = dictation as? DictationState.Transcribed ?: return@LaunchedEffect
        onDraftChange(joinDraft(latestDraft, done.text))
        graph.dictation.consume()
    }

    fun close() {
        job?.cancel()
        job = null
        panel = null
    }

    fun ready(): Boolean {
        if (aiState == LocalAiState.Ready) return true
        needsDownload = true
        return false
    }

    fun runResult(title: Int, replaceDraft: Boolean, block: suspend ((String) -> Unit) -> String) {
        job?.cancel()
        panel = Panel.Result(title, "", running = true, error = null, replaceDraft = replaceDraft)
        job = scope.launch {
            try {
                val text = block { partial -> (panel as? Panel.Result)?.let { panel = it.copy(text = partial) } }
                (panel as? Panel.Result)?.let { panel = it.copy(text = text, running = false) }
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
            } catch (e: LocalAiException) {
                (panel as? Panel.Result)?.let { panel = it.copy(running = false, error = e.error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (panel as? Panel.Result)?.let { panel = it.copy(running = false, error = LocalAiError.Unknown) }
            }
        }
    }

    fun improve() {
        if (!ready()) return
        val original = latestDraft.trim()
        if (original.isEmpty()) return
        job?.cancel()
        panel = Panel.Improve(original, "", running = true, error = null)
        job = scope.launch {
            try {
                val text = assistant.improve(original, latestMessages) { partial -> (panel as? Panel.Improve)?.let { panel = it.copy(text = partial) } }
                (panel as? Panel.Improve)?.let { panel = it.copy(text = text, running = false) }
            } catch (e: LocalAiException) {
                (panel as? Panel.Improve)?.let { panel = it.copy(running = false, error = e.error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (panel as? Panel.Improve)?.let { panel = it.copy(running = false, error = LocalAiError.Unknown) }
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { loadScaled(context, uri, MAX_IMAGE_SIDE) }.getOrNull() }
                panel = if (bitmap != null) Panel.Screenshot(bitmap) else Panel.Result(R.string.localai_screenshot, "", false, LocalAiError.InvalidImage, false)
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ack != null || spend != null) ProviderLine(status, spend) { panel = Panel.Provider }
        if (spend != null && spend.level != BudgetLevel.None) BudgetBanner(spend)

        val aiShown = aiState != LocalAiState.Unsupported && aiState != LocalAiState.Checking
        val micShown = graph.dictation.available
        if (aiShown || micShown) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.localai_on_device), style = happyTextStyle(fontSize = 11, lineHeight = 14), color = colors.textSecondary)
                if (aiShown) {
                    Chip(Ionicon.TimeOutline, stringResource(R.string.localai_catch_up), CompanionTags.CATCH_UP) {
                        if (ready()) runResult(R.string.localai_catch_up, replaceDraft = false) { onPartial -> assistant.catchUp(latestMessages, onPartial) }
                    }
                    Chip(Ionicon.SparklesOutline, stringResource(R.string.localai_improve), CompanionTags.IMPROVE, enabled = draft.isNotBlank()) { improve() }
                    Chip(Ionicon.BulbOutline, stringResource(R.string.localai_explain), CompanionTags.EXPLAIN) {
                        if (ready()) panel = Panel.ExplainPick(explainCandidates(latestMessages))
                    }
                    Chip(Ionicon.ImageOutline, stringResource(R.string.localai_screenshot), CompanionTags.SCREENSHOT) {
                        if (ready()) pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                if (micShown) {
                    MicButton(
                        listening = dictation is DictationState.Listening,
                        onPress = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                graph.dictation.start()
                                true
                            } else {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                false
                            }
                        },
                        onRelease = { graph.dictation.stop() },
                    )
                }
            }
            when (val d = dictation) {
                is DictationState.Listening -> Text(
                    d.partial.ifEmpty { stringResource(R.string.localai_listening) },
                    style = ItemSpec.subtitle,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                is DictationState.Failed -> Text(
                    stringResource(dictationProblemText(d.reason)),
                    style = ItemSpec.subtitle,
                    color = colors.textDestructive,
                    modifier = Modifier.clickable { graph.dictation.consume() },
                )
                else -> Unit
            }
        }
    }

    if (needsDownload) {
        DownloadPrompt(aiState, onDownload = { graph.localAi.download() }, onDismiss = { needsDownload = false })
    }

    when (val p = panel) {
        null -> Unit
        is Panel.Result -> ResultSheet(
            title = stringResource(p.title),
            text = p.text,
            running = p.running,
            error = p.error,
            primary = stringResource(if (p.replaceDraft) R.string.localai_use_as_draft else R.string.localai_add_to_draft),
            onPrimary = {
                onDraftChange(if (p.replaceDraft) p.text else joinDraft(latestDraft, p.text))
                close()
            },
            onCopy = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Happy", p.text))) } },
            onDismiss = ::close,
        )
        is Panel.Improve -> ImproveSheet(
            panel = p,
            onAccept = { text ->
                onDraftChange(text)
                close()
            },
            onDismiss = ::close,
        )
        is Panel.ExplainPick -> ExplainPickSheet(
            candidates = p.candidates,
            onPick = { candidate ->
                runResult(R.string.localai_explain, replaceDraft = false) { onPartial -> assistant.explain(candidate.text, candidate.kind, onPartial) }
            },
            onPaste = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (text.isNotBlank()) runResult(R.string.localai_explain, replaceDraft = false) { onPartial -> assistant.explain(text, ExplainKind.Log, onPartial) }
                }
            },
            onDismiss = ::close,
        )
        is Panel.Screenshot -> ScreenshotSheet(
            image = p.image,
            onDraft = { kind, note ->
                runResult(R.string.localai_screenshot, replaceDraft = false) { onPartial -> assistant.screenshot(p.image, kind, note, onPartial) }
            },
            onDismiss = ::close,
        )
        Panel.Provider -> ProviderSheet(ack, status, spend, onDismiss = ::close)
    }
}

@Composable
private fun ProviderLine(status: ProviderStatus, spend: SessionSpend?, onClick: () -> Unit) {
    val colors = HappyTheme.colors
    val style = happyTextStyle(fontSize = 12, lineHeight = 16)
    val left = when (status) {
        ProviderStatus.HostDefault -> null
        is ProviderStatus.Confirmed -> "OpenRouter · ${status.ack.activeModel?.substringAfter('/')} · " + stringResource(R.string.inference_confirmed)
        is ProviderStatus.AwaitingModel -> "OpenRouter · ${status.ack.requestedModel.substringAfter('/')} · " + stringResource(R.string.inference_awaiting_model)
        is ProviderStatus.ModelMismatch -> "OpenRouter · " + stringResource(R.string.inference_model_mismatch, status.active)
    }
    val right = spend?.let { spendText(it) }
    val description = listOfNotNull(left, right).joinToString(". ")
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(CompanionTags.PROVIDER)
            .heightIn(min = 32.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (left != null) {
            val tint = if (status is ProviderStatus.ModelMismatch) colors.textDestructive else colors.textSecondary
            Icon(if (status is ProviderStatus.Confirmed) Ionicon.CheckmarkCircleOutline else Ionicon.InformationCircleOutline, 14.dp, tint)
            Text(left, style = style, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f))
        }
        if (right != null) Text(right, style = style, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun spendText(spend: SessionSpend): String {
    val tokens = spend.totalTokens?.let { stringResource(R.string.inference_tokens, formatTokens(it)) }
    val cost = spend.costUsd?.let { usd ->
        stringResource(
            when (spend.source) {
                CostSource.Reported -> R.string.inference_cost_reported
                CostSource.Estimated -> R.string.inference_cost_estimated
                CostSource.Unavailable -> R.string.inference_cost_unavailable
            },
            formatUsd(usd),
        )
    } ?: stringResource(R.string.inference_cost_none)
    val scope = if (spend.cumulative) null else stringResource(R.string.inference_last_reply)
    return listOfNotNull(scope, tokens, cost).joinToString(" · ")
}

@Composable
private fun BudgetBanner(spend: SessionSpend) {
    val colors = HappyTheme.colors
    val box = if (spend.level == BudgetLevel.Exceeded) colors.boxError else colors.boxWarning
    val text = when (spend.level) {
        BudgetLevel.Exceeded -> stringResource(R.string.inference_budget_exceeded, formatUsd(spend.costUsd ?: 0.0), formatUsd(spend.budgetUsd ?: spend.costUsd ?: 0.0))
        else -> stringResource(R.string.inference_budget_warning, formatUsd(spend.costUsd ?: 0.0), formatUsd(spend.budgetUsd ?: spend.warnAtUsd ?: 0.0))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(CompanionTags.BUDGET)
            .background(box.background, RoundedCornerShape(10.dp))
            .border(1.dp, box.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ionicon.WarningOutline, 16.dp, box.text)
        Text(text, style = ItemSpec.subtitle, color = box.text)
    }
}

@Composable
private fun Chip(icon: Ionicon, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = HappyTheme.colors
    Row(
        Modifier
            .testTag(tag)
            .heightIn(min = 40.dp)
            .background(colors.surfaceHigh, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, 16.dp, if (enabled) colors.text else colors.textSecondary)
        Text(label, style = ItemSpec.subtitle, color = if (enabled) colors.text else colors.textSecondary)
    }
}

/** Hold to talk; release to transcribe. TalkBack users double-tap to start and again to stop. */
@Composable
private fun MicButton(listening: Boolean, onPress: () -> Boolean, onRelease: () -> Unit) {
    val colors = HappyTheme.colors
    val label = stringResource(R.string.localai_hold_to_talk)
    val state = stringResource(if (listening) R.string.localai_listening else R.string.localai_mic_idle)
    val latestListening by rememberUpdatedState(listening)
    Box(
        Modifier
            .testTag(CompanionTags.MIC)
            .heightIn(min = 40.dp)
            .background(if (listening) colors.textLink else colors.surfaceHigh, RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (onPress()) {
                        tryAwaitRelease()
                        onRelease()
                    }
                })
            }
            .semantics {
                contentDescription = label
                stateDescription = state
                role = Role.Button
                onClick { if (latestListening) onRelease() else onPress(); true }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (listening) Ionicon.Mic else Ionicon.MicOutline, 18.dp, if (listening) colors.surface else colors.text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = HappyTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 12.dp).testTag(CompanionTags.SHEET)) {
            content()
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = happyTextStyle(fontSize = 18, lineHeight = 24), color = HappyTheme.colors.text, modifier = Modifier.padding(bottom = 4.dp))
    Text(stringResource(R.string.localai_disclaimer), style = happyTextStyle(fontSize = 12, lineHeight = 16), color = HappyTheme.colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun ResultSheet(
    title: String,
    text: String,
    running: Boolean,
    error: LocalAiError?,
    primary: String,
    onPrimary: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = HappyTheme.colors
    SheetFrame(onDismiss) {
        SheetTitle(title)
        Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 420.dp).verticalScroll(rememberScrollState())) {
            when {
                error != null -> Text(stringResource(localAiErrorText(error)), style = ItemSpec.title, color = colors.textDestructive)
                text.isEmpty() && running -> Text(stringResource(R.string.localai_working), style = ItemSpec.title, color = colors.textSecondary)
                else -> Text(text, style = ItemSpec.title, color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.localai_close)) }
            if (!running && error == null && text.isNotBlank()) {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.common_copy)) }
                TextButton(onClick = onPrimary, modifier = Modifier.testTag(CompanionTags.ACCEPT)) { Text(primary) }
            }
        }
    }
}

/** Improve instruction: the rewrite, editable, beside nothing being sent. Accept replaces the draft. */
@Composable
private fun ImproveSheet(panel: Panel.Improve, onAccept: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = HappyTheme.colors
    var editing by remember { mutableStateOf(false) }
    var edited by remember(panel.running) { mutableStateOf(panel.text) }
    SheetFrame(onDismiss) {
        SheetTitle(stringResource(R.string.localai_improve_title))
        Text(stringResource(R.string.localai_original), style = ItemSpec.subtitle, color = colors.textSecondary)
        Text(panel.original, style = ItemSpec.subtitle, color = colors.textSecondary, maxLines = 4, modifier = Modifier.padding(bottom = 10.dp))
        Text(stringResource(R.string.localai_suggestion), style = ItemSpec.subtitle, color = colors.textSecondary)
        Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp).verticalScroll(rememberScrollState())) {
            when {
                panel.error != null -> Text(stringResource(localAiErrorText(panel.error)), style = ItemSpec.title, color = colors.textDestructive)
                panel.running -> Text(panel.text.ifEmpty { stringResource(R.string.localai_working) }, style = ItemSpec.title, color = colors.textSecondary)
                editing -> BasicTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    textStyle = ItemSpec.title.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.textLink),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "" },
                )
                else -> Text(edited, style = ItemSpec.title, color = colors.text)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            if (!panel.running && panel.error == null) {
                if (!editing) TextButton(onClick = { editing = true }) { Text(stringResource(R.string.localai_edit)) }
                TextButton(onClick = { onAccept(edited) }, enabled = edited.isNotBlank(), modifier = Modifier.testTag(CompanionTags.ACCEPT)) { Text(stringResource(R.string.localai_accept)) }
            }
        }
    }
}

@Composable
private fun ExplainPickSheet(candidates: List<ExplainCandidate>, onPick: (ExplainCandidate) -> Unit, onPaste: () -> Unit, onDismiss: () -> Unit) {
    val colors = HappyTheme.colors
    SheetFrame(onDismiss) {
        SheetTitle(stringResource(R.string.localai_explain_title))
        if (candidates.isEmpty()) Text(stringResource(R.string.localai_explain_nothing), style = ItemSpec.subtitle, color = colors.textSecondary)
        for (candidate in candidates) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(candidate) }.padding(vertical = 8.dp),
            ) {
                Text(stringResource(candidate.label), style = ItemSpec.title, color = colors.text)
                Text(candidate.text.replace(Regex("\\s+"), " ").take(140), style = ItemSpec.subtitle, color = colors.textSecondary, maxLines = 2)
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onPaste).padding(vertical = 8.dp)) {
            Text(stringResource(R.string.localai_explain_clipboard), style = ItemSpec.title, color = colors.text)
            Text(stringResource(R.string.localai_explain_clipboard_hint), style = ItemSpec.subtitle, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ScreenshotSheet(image: Bitmap, onDraft: (ScreenshotDraft, String) -> Unit, onDismiss: () -> Unit) {
    val colors = HappyTheme.colors
    var kind by rememberSaveable { mutableStateOf(ScreenshotDraft.BugReport) }
    var note by rememberSaveable { mutableStateOf("") }
    SheetFrame(onDismiss) {
        SheetTitle(stringResource(R.string.localai_screenshot_title))
        androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(R.string.localai_screenshot_selected),
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in ScreenshotDraft.entries) {
                val label = stringResource(if (option == ScreenshotDraft.BugReport) R.string.localai_bug_report else R.string.localai_ui_change)
                val selected = option == kind
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .background(if (selected) colors.textLink else colors.surfaceHigh, RoundedCornerShape(20.dp))
                        .clickable(role = Role.RadioButton) { kind = option }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(label, style = ItemSpec.subtitle, color = if (selected) colors.surface else colors.text)
                }
            }
        }
        val noteLabel = stringResource(R.string.localai_screenshot_note)
        Box(Modifier.fillMaxWidth().background(colors.surfaceHigh, RoundedCornerShape(10.dp)).padding(12.dp)) {
            if (note.isEmpty()) Text(noteLabel, style = ItemSpec.subtitle, color = colors.input.placeholder)
            BasicTextField(
                value = note,
                onValueChange = { note = it.take(600) },
                textStyle = ItemSpec.subtitle.copy(color = colors.text),
                cursorBrush = SolidColor(colors.textLink),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = noteLabel },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = { onDraft(kind, note) }, modifier = Modifier.testTag(CompanionTags.ACCEPT)) { Text(stringResource(R.string.localai_draft)) }
        }
    }
}

@Composable
private fun ProviderSheet(ack: InferenceAck?, status: ProviderStatus, spend: SessionSpend?, onDismiss: () -> Unit) {
    val colors = HappyTheme.colors
    SheetFrame(onDismiss) {
        Text(stringResource(R.string.inference_session_provider), style = happyTextStyle(fontSize = 18, lineHeight = 24), color = colors.text, modifier = Modifier.padding(bottom = 12.dp))
        @Composable
        fun Line(label: Int, value: String) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
                Text(stringResource(label), style = ItemSpec.subtitle, color = colors.textSecondary, modifier = Modifier.weight(0.4f))
                Text(value, style = ItemSpec.subtitle, color = colors.text, modifier = Modifier.weight(0.6f))
            }
        }
        if (ack == null) {
            Line(R.string.inference_harness, "Claude Code")
            Line(R.string.inference_provider, stringResource(R.string.inference_host_default))
        } else {
            Line(R.string.inference_harness, if (ack.harness == "claude-code") "Claude Code" else ack.harness)
            Line(R.string.inference_provider, "OpenRouter (${ack.baseUrlHost})")
            ack.profileName?.let { Line(R.string.inference_profile, it) }
            Line(R.string.inference_requested_model, ack.requestedModel)
            Line(R.string.inference_active_model, ack.activeModel ?: stringResource(R.string.inference_awaiting_model))
            Line(R.string.inference_credential, ack.credentialRef)
            if (ack.serverTools.isNotEmpty()) Line(R.string.inference_server_tools, ack.serverTools.map { stringResource(serverToolName(it)) }.joinToString(", "))
            Line(R.string.inference_hard_limit, ack.budgetUsd?.let { formatUsd(it) } ?: stringResource(R.string.inference_none))
            Line(R.string.inference_status, stringResource(
                when (status) {
                    is ProviderStatus.Confirmed -> R.string.inference_confirmed
                    is ProviderStatus.ModelMismatch -> R.string.inference_model_mismatch_short
                    else -> R.string.inference_awaiting_model
                },
            ))
        }
        if (spend != null) Line(R.string.inference_spend, spendText(spend))
        if (spend != null && spend.webSearches > 0) Line(R.string.inference_tool_web_search, spend.webSearches.toString())
        Text(
            stringResource(if (ack != null) R.string.inference_switch_note else R.string.inference_host_default_note),
            style = happyTextStyle(fontSize = 12, lineHeight = 16),
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun DownloadPrompt(state: LocalAiState, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.localai_settings_title)
    val message = when (state) {
        is LocalAiState.Downloading -> stringResource(R.string.localai_downloading)
        LocalAiState.NeedsDownload, is LocalAiState.DownloadFailed -> stringResource(R.string.localai_download_subtitle)
        else -> stringResource(localAiStateTitle(state))
    }
    val buttons = buildList {
        add(com.slopus.happy.ui.components.AlertButton(stringResource(R.string.common_cancel)))
        if (state == LocalAiState.NeedsDownload || state is LocalAiState.DownloadFailed) {
            add(com.slopus.happy.ui.components.AlertButton(stringResource(R.string.localai_download), onClick = onDownload))
        }
    }
    com.slopus.happy.ui.components.HappyAlert(com.slopus.happy.ui.components.Alert(title, message, buttons), onDismiss = onDismiss)
}

internal fun dictationProblemText(problem: com.slopus.happy.localai.DictationProblem): Int = when (problem) {
    com.slopus.happy.localai.DictationProblem.Unavailable -> R.string.localai_dictation_unavailable
    com.slopus.happy.localai.DictationProblem.LanguageNotInstalled -> R.string.localai_dictation_language_missing
    com.slopus.happy.localai.DictationProblem.LanguageUnsupported -> R.string.localai_dictation_language_unsupported
    com.slopus.happy.localai.DictationProblem.NoMatch -> R.string.localai_dictation_no_match
    com.slopus.happy.localai.DictationProblem.Permission -> R.string.localai_dictation_permission
    com.slopus.happy.localai.DictationProblem.Busy -> R.string.localai_error_busy
    com.slopus.happy.localai.DictationProblem.Other -> R.string.localai_error_unknown
}

/** Appends [addition] to the draft as its own paragraph. */
internal fun joinDraft(draft: String, addition: String): String =
    if (draft.isBlank()) addition.trim() else draft.trimEnd() + "\n\n" + addition.trim()

/** What Explain offers from the session: the last error, the last tool output and the last reply. */
internal fun explainCandidates(messages: List<ChatMessage>): List<ExplainCandidate> {
    val sorted = messages.sortedByDescending { it.sortKey }
    val tools = sorted.filterIsInstance<ChatMessage.ToolCallMessage>()
    val error = tools.firstOrNull { it.tool.state == ToolState.Error }?.let { resultText(it.tool.result) }?.takeIf { it.isNotBlank() }
    val output = tools.firstOrNull { it.tool.state == ToolState.Completed && resultText(it.tool.result).isNotBlank() }
    val reply = sorted.filterIsInstance<ChatMessage.AgentText>().firstOrNull { !it.isThinking }?.text
    return buildList {
        error?.let { add(ExplainCandidate(R.string.localai_explain_last_error, ExplainKind.Error, it)) }
        output?.let {
            val kind = if (it.tool.name in setOf("Edit", "MultiEdit", "apply_patch")) ExplainKind.Diff else ExplainKind.Log
            add(ExplainCandidate(R.string.localai_explain_last_output, kind, resultText(it.tool.result)))
        }
        reply?.let { add(ExplainCandidate(R.string.localai_explain_last_reply, ExplainKind.Message, it)) }
    }
}

private fun resultText(element: JsonElement?): String = when (element) {
    null -> ""
    is JsonPrimitive -> element.content
    is JsonObject -> listOf("stdout", "output", "content", "error", "stderr").firstNotNullOfOrNull { (element[it] as? JsonPrimitive)?.content } ?: element.toString()
    else -> element.toString()
}

private const val MAX_IMAGE_SIDE = 1024

/** Decodes a picked picture no larger than [maxSide] on its long edge (it stays on the phone). */
internal fun loadScaled(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        ?: throw java.io.IOException("unreadable image")
}
