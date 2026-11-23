package com.slopus.happy.assist

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.slopus.happy.R
import com.slopus.happy.model.SessionMetadata
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What was handed to a session from outside its chat (a share from another
 * app, an assistant's drafted task) and waits to join that session's
 * draft the next time its chat opens.
 *
 * Kept on disk in the no-backup directory so a share survives the process
 * being killed between the share sheet and the chat. Pictures are copied in
 * here because the sharing app's read grant ends with the share. Nothing in
 * the inbox is ever sent: it only becomes draft text and waiting pictures.
 */
class CompanionInbox(private val dir: File) {
    data class Item(val text: String?, val images: List<File>, val source: Source)

    enum class Source { Share, Assistant }

    @Synchronized
    fun put(sessionId: String, text: String?, images: List<File>, source: Source) {
        dir.mkdirs()
        val existing = read(sessionId)
        val merged = Item(
            text = listOfNotNull(existing?.text, text?.takeIf { it.isNotBlank() }).joinToString("\n\n").ifEmpty { null },
            images = existing?.images.orEmpty() + images,
            source = source,
        )
        file(sessionId).writeText(
            JsonObject(
                mapOf(
                    "text" to JsonPrimitive(merged.text),
                    "images" to JsonArray(merged.images.map { JsonPrimitive(it.name) }),
                    "source" to JsonPrimitive(merged.source.name),
                ),
            ).toString(),
        )
    }

    /** Takes the session's waiting item, removing it. Pictures stay until [release]. */
    @Synchronized
    fun take(sessionId: String): Item? {
        val item = read(sessionId) ?: return null
        file(sessionId).delete()
        return item
    }

    /** Whether anything waits for [sessionId], without taking it. */
    fun has(sessionId: String): Boolean = file(sessionId).exists()

    /** A picture's copy, once the composer has normalized it into its own cache. */
    fun release(images: List<File>) = images.forEach { it.delete() }

    /** Copies a shared picture in while the share's read grant lasts. */
    fun copyIn(context: Context, uri: Uri): File? = runCatching {
        dir.resolve(IMAGES).mkdirs()
        val target = dir.resolve(IMAGES).resolve(UUID.randomUUID().toString())
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, BUFFER) } } ?: return null
        if (target.length() > MAX_IMAGE_BYTES) {
            target.delete()
            return null
        }
        target
    }.getOrNull()

    private fun read(sessionId: String): Item? = runCatching {
        val o = Json.parseToJsonElement(file(sessionId).readText()).jsonObject
        Item(
            text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            images = (o["images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let { name -> dir.resolve(IMAGES).resolve(name) } }.filter { it.exists() },
            source = Source.entries.find { it.name == (o["source"] as? JsonPrimitive)?.content } ?: Source.Share,
        )
    }.getOrNull()

    private fun file(sessionId: String) = dir.resolve(sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")

    companion object {
        private const val IMAGES = "images"
        private const val BUFFER = 64 * 1024
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    }
}

/**
 * Launcher shortcuts: "New task" is static (res/xml/shortcuts.xml); the
 * sessions opened most recently are dynamic, pushed as they open. Each is a
 * `happy://` deep link the app already routes.
 */
object SessionShortcuts {
    private const val MAX_RECENT = 3

    fun sessionUri(sessionId: String): Uri = Uri.parse("happy://session/$sessionId")

    fun newTaskUri(): Uri = Uri.parse("happy://new")

    /** The label a session goes by outside the app. */
    fun label(metadata: SessionMetadata?, fallback: String): String =
        metadata?.summary?.text?.takeIf { it.isNotBlank() }
            ?: metadata?.name?.takeIf { it.isNotBlank() }
            ?: metadata?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: fallback

    fun pushRecent(context: Context, sessionId: String, label: String) {
        val app = context.applicationContext
        val shortLabel = label.take(24).ifEmpty { app.getString(R.string.share_session_fallback) }
        val shortcut = ShortcutInfoCompat.Builder(app, "session-$sessionId")
            .setShortLabel(shortLabel)
            .setLongLabel(label.take(48).ifEmpty { shortLabel })
            .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
            .setIntent(Intent(Intent.ACTION_VIEW, sessionUri(sessionId)).setPackage(app.packageName))
            .setRank(0)
            .build()
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
            val dynamic = ShortcutManagerCompat.getDynamicShortcuts(app).filter { it.id.startsWith("session-") }
            if (dynamic.size > MAX_RECENT) {
                ShortcutManagerCompat.removeDynamicShortcuts(app, dynamic.sortedBy { it.rank }.drop(MAX_RECENT).map { it.id })
            }
        }
    }

    /** Signing out: no session names stay on the launcher. */
    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context.applicationContext) }
    }
}
