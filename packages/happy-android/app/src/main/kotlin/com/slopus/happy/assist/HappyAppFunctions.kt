package com.slopus.happy.assist

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.slopus.happy.HappyApplication
import com.slopus.happy.MainActivity
import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.ui.home.HomeServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Happy coding session on this phone, as an assistant sees it.
 *
 * @property id The session's identifier, used to open it or draft into it.
 * @property title The session's summary or folder name.
 * @property folder The folder on the computer the session works in.
 * @property active Whether the session's computer is connected right now.
 * @property updatedAtMillis When the session last changed, in milliseconds since the epoch.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class HappySession(
    val id: String,
    val title: String,
    val folder: String?,
    val active: Boolean,
    val updatedAtMillis: Long,
)

/**
 * App functions for assistants such as Gemini (Android 16+).
 *
 * They can list sessions, open one, and put a drafted instruction into a
 * session's composer or a new task's prompt. None of them sends anything
 * to a coding agent or answers a permission request: a draft waits in the
 * app until the person reviews it and presses Send themselves.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "HappyAppFunctionService",
    appFunctionXmlFileName = "happy_app_functions",
)
abstract class HappyAppFunctions : AppFunctionService() {
    private suspend fun graph() = (application as HappyApplication).graphReady.await()

    /**
     * Lists the user's most recently active Happy coding sessions on this phone.
     *
     * @param limit How many sessions to return, from 1 to 20.
     * @return The sessions, most recently changed first.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listRecentSessions(limit: Int): List<HappySession> = withContext(Dispatchers.IO) {
        val graph = graph()
        if (graph.account.value == null) throw AppFunctionElementNotFoundException("Not signed in to Happy")
        graph.store.sessions()
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(1, 20))
            .map { record ->
                val metadata = SessionMetadata.parse(record.metadataJson)
                HappySession(
                    id = record.id,
                    title = SessionShortcuts.label(metadata, record.id.take(8)),
                    folder = metadata?.path,
                    active = record.active,
                    updatedAtMillis = record.updatedAt,
                )
            }
    }

    /**
     * Opens a Happy coding session so the user can read it.
     *
     * @param sessionId The session to open, from listRecentSessions.
     * @return An intent that opens the session in Happy.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun openSession(sessionId: String): PendingIntent = withContext(Dispatchers.IO) {
        val graph = graph()
        if (graph.store.session(sessionId) == null) throw AppFunctionElementNotFoundException("No such session")
        open(SessionShortcuts.sessionUri(sessionId).toString(), sessionId.hashCode())
    }

    /**
     * Drafts an instruction for a coding agent without sending it. The draft
     * is placed in the session's composer (or a new task's prompt when no
     * session is given) and Happy opens so the user can review, edit and
     * send it themselves.
     *
     * @param instruction The instruction to draft, in plain text.
     * @param sessionId The session to draft into, from listRecentSessions; empty for a new task.
     * @return An intent that opens Happy at the draft for review.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun draftTask(instruction: String, sessionId: String?): PendingIntent = withContext(Dispatchers.IO) {
        val text = instruction.trim()
        if (text.isEmpty() || text.length > 20_000) throw AppFunctionInvalidArgumentException("The instruction must be 1 to 20000 characters")
        val graph = graph()
        if (graph.account.value == null) throw AppFunctionElementNotFoundException("Not signed in to Happy")
        val target = sessionId?.takeIf { it.isNotBlank() }
        if (target != null) {
            if (graph.store.session(target) == null) throw AppFunctionElementNotFoundException("No such session")
            graph.inbox.put(target, text, emptyList(), CompanionInbox.Source.Assistant)
            open(SessionShortcuts.sessionUri(target).toString(), target.hashCode())
        } else {
            withContext(Dispatchers.Main) { HomeServices.of(graph).newSession(this@HappyAppFunctions).appendToInput(text) }
            open(SessionShortcuts.newTaskUri().toString(), 0)
        }
    }

    private fun open(uri: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri), this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
