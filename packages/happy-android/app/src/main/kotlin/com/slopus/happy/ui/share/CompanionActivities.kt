package com.slopus.happy.ui.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.slopus.happy.AppGraph
import com.slopus.happy.HappyApplication
import com.slopus.happy.R
import com.slopus.happy.assist.CompanionInbox
import com.slopus.happy.assist.SessionShortcuts
import com.slopus.happy.localai.ExplainKind
import com.slopus.happy.localai.LocalAiError
import com.slopus.happy.localai.LocalAiException
import com.slopus.happy.localai.LocalAiState
import com.slopus.happy.localai.LocalAssistant
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.ui.components.Ionicon
import com.slopus.happy.ui.components.Item
import com.slopus.happy.ui.components.ItemGroup
import com.slopus.happy.ui.components.ItemList
import com.slopus.happy.ui.components.ItemSpec
import com.slopus.happy.ui.components.Screen
import com.slopus.happy.ui.components.StackHeader
import com.slopus.happy.ui.home.HomeServices
import com.slopus.happy.ui.layout.ProvideWindowLayout
import com.slopus.happy.ui.localai.localAiErrorText
import com.slopus.happy.ui.localai.localAiStateTitle
import com.slopus.happy.ui.settings.ItemIcon
import com.slopus.happy.ui.theme.HappyTheme
import com.slopus.happy.ui.theme.happyTextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ShareTags {
    const val NEW_TASK = "share.newTask"
    const val SESSION = "share.session"
}

/** What another app shared: text, pictures, or both. */
data class SharedContent(val text: String?, val images: List<Uri>) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && images.isEmpty()

    companion object {
        fun from(intent: Intent): SharedContent {
            val text = listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() },
            ).joinToString("\n").ifBlank { null }
            val images = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(stream(intent))
                Intent.ACTION_SEND_MULTIPLE -> streams(intent)
                else -> emptyList()
            }.takeIf { intent.type?.startsWith("image/") == true }.orEmpty()
            return SharedContent(text?.take(MAX_TEXT), images.take(MAX_IMAGES))
        }

        @Suppress("DEPRECATION")
        private fun stream(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun streams(intent: Intent): List<Uri> =
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

        const val MAX_TEXT = 20_000
        const val MAX_IMAGES = 4
    }
}

private data class ShareSession(val id: String, val label: String, val path: String?, val updatedAt: Long)

/** Keeps the app counted as in the foreground while one of these activities shows. */
private fun ComponentActivity.followForeground(graph: AppGraph) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            graph.setForeground(true)
            try {
                awaitCancellation()
            } finally {
                graph.setForeground(false)
            }
        }
    }
}

/**
 * The share sheet's "Happy" target. Text and pictures from another app go
 * to a session the person picks, as a draft in that session's composer (or
 * a new task's prompt). Nothing is sent from here: the chat opens with the
 * draft ready to review.
 */
class ShareTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val shared = SharedContent.from(intent)
        if (shared.isEmpty) {
            finish()
            return
        }
        val ready = (application as HappyApplication).graphReady
        setContent {
            HappyTheme {
                ProvideWindowLayout {
                    val graph by produceState<AppGraph?>(null) { value = ready.await().also { followForeground(it) } }
                    graph?.let { ShareRoute(it, shared) } ?: Box(Modifier)
                }
            }
        }
    }

    @Composable
    private fun ShareRoute(graph: AppGraph, shared: SharedContent) {
        val scope = rememberCoroutineScope()
        val signedIn = graph.account.value != null
        val sessions by produceState(emptyList<ShareSession>()) {
            value = withContext(Dispatchers.IO) {
                graph.store.sessions().map { record ->
                    val metadata = SessionMetadata.parse(record.metadataJson)
                    ShareSession(record.id, SessionShortcuts.label(metadata, record.id.take(8)), metadata?.path, record.updatedAt)
                }.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
            }
        }
        var busy by remember { mutableStateOf(false) }

        fun open(uri: Uri) {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        fun toSession(id: String) {
            if (busy) return
            busy = true
            scope.launch {
                val files = withContext(Dispatchers.IO) { shared.images.mapNotNull { graph.inbox.copyIn(this@ShareTargetActivity, it) } }
                withContext(Dispatchers.IO) { graph.inbox.put(id, shared.text, files, CompanionInbox.Source.Share) }
                open(SessionShortcuts.sessionUri(id))
            }
        }

        Screen(header = { StackHeader(stringResource(R.string.share_title), onBack = ::finish) }) {
            ItemList {
                ItemGroup(title = stringResource(R.string.share_content)) {
                    Item(
                        title = shared.text?.replace(Regex("\\s+"), " ")?.take(160) ?: stringResource(R.string.share_pictures_only),
                        subtitle = if (shared.images.isNotEmpty()) stringResource(R.string.share_pictures_count, shared.images.size) else null,
                        showChevron = false,
                    )
                }
                if (!signedIn) {
                    ItemGroup { Item(title = stringResource(R.string.share_sign_in_first), enabled = false, showChevron = false) }
                    return@ItemList
                }
                if (!shared.text.isNullOrBlank() && shared.images.isEmpty()) {
                    ItemGroup(footer = stringResource(R.string.share_new_task_footer)) {
                        Item(
                            title = stringResource(R.string.share_new_task),
                            icon = { ItemIcon(Ionicon.AddOutline, HappyTheme.colors.textLink) },
                            enabled = !busy,
                            onClick = {
                                HomeServices.of(graph).newSession(this@ShareTargetActivity).appendToInput(shared.text)
                                open(SessionShortcuts.newTaskUri())
                            },
                            modifier = Modifier.testTag(ShareTags.NEW_TASK),
                        )
                    }
                }
                ItemGroup(title = stringResource(R.string.share_sessions), footer = stringResource(R.string.share_sessions_footer)) {
                    if (sessions.isEmpty()) Item(title = stringResource(R.string.share_no_sessions), enabled = false, showChevron = false)
                    for (session in sessions) {
                        Item(
                            title = session.label,
                            subtitle = session.path,
                            icon = { ItemIcon(Ionicon.ChatbubblesOutline, HappyTheme.colors.textSecondary) },
                            enabled = !busy,
                            onClick = { toSession(session.id) },
                            modifier = Modifier.testTag(ShareTags.SESSION),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_SESSIONS = 40
    }
}

/**
 * "Explain with Happy" in the system's text-selection menu, inside Happy's
 * own chats and in other apps. Explains the selected text on the phone with
 * Gemini Nano; the explanation can be copied or added to a session's draft.
 * It never changes the selection (read-only `PROCESS_TEXT`).
 */
class ExplainTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val selection = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (selection.isEmpty()) {
            finish()
            return
        }
        val ready = (application as HappyApplication).graphReady
        setContent {
            HappyTheme {
                ProvideWindowLayout {
                    val graph by produceState<AppGraph?>(null) { value = ready.await().also { followForeground(it) } }
                    graph?.let { ExplainRoute(it, selection) } ?: Box(Modifier)
                }
            }
        }
    }

    @Composable
    private fun ExplainRoute(graph: AppGraph, selection: String) {
        val colors = HappyTheme.colors
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<LocalAiError?>(null) }
        var running by remember { mutableStateOf(true) }
        LaunchedEffect(selection) {
            graph.localAi.refresh()
            val state = graph.localAi.state.value
            if (state != LocalAiState.Ready) {
                running = false
                text = getString(localAiStateTitle(state))
                return@LaunchedEffect
            }
            try {
                val kind = if (Regex("(?i)error|exception|failed|traceback").containsMatchIn(selection)) ExplainKind.Error else ExplainKind.Message
                text = LocalAssistant(graph.localAi).explain(selection, kind) { partial -> text = partial }
            } catch (e: LocalAiException) {
                error = e.error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = LocalAiError.Unknown
            } finally {
                running = false
            }
        }
        Screen(header = { StackHeader(stringResource(R.string.localai_explain_title), onBack = ::finish) }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(stringResource(R.string.localai_disclaimer), style = happyTextStyle(fontSize = 12, lineHeight = 16), color = colors.textSecondary)
                Text(
                    selection.take(400),
                    style = ItemSpec.subtitle,
                    color = colors.textSecondary,
                    maxLines = 6,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp)) {
                    when {
                        error != null -> Text(stringResource(localAiErrorText(error!!)), style = ItemSpec.title, color = colors.textDestructive)
                        text.isEmpty() && running -> Text(stringResource(R.string.localai_working), style = ItemSpec.title, color = colors.textSecondary)
                        else -> Text(text, style = ItemSpec.title, color = colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                if (!running && error == null && text.isNotBlank()) {
                    TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Happy", text))) } }) {
                        Text(stringResource(R.string.common_copy))
                    }
                    TextButton(onClick = {
                        startActivity(
                            Intent(this@ExplainTextActivity, ShareTargetActivity::class.java)
                                .setAction(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text),
                        )
                        finish()
                    }) {
                        Text(stringResource(R.string.localai_add_to_session))
                    }
                }
            }
        }
    }
}
