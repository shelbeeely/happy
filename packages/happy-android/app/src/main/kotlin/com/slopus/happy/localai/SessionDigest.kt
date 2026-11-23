package com.slopus.happy.localai

import com.slopus.happy.chat.ChatMessage
import com.slopus.happy.chat.PermissionStatus
import com.slopus.happy.chat.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A compact, text-only view of a session for the local assistant, built
 * from what is already on the phone. Oldest first. Tool calls keep their
 * name, the file they touched, and errors; long outputs are clipped.
 */
object SessionDigest {
    private val FILE_KEYS = listOf("file_path", "path", "filePath", "notebook_path")
    private val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit", "apply_patch", "edit", "write")

    data class Digest(
        val text: String,
        val changedFiles: List<String>,
        val errors: Int,
        val pendingApprovals: Int,
    )

    fun of(messages: List<ChatMessage>, maxCharsPerItem: Int = 600): Digest {
        val changed = LinkedHashSet<String>()
        var errors = 0
        var pending = 0
        val lines = ArrayList<String>()
        for (message in messages.sortedBy { it.sortKey }) {
            when (message) {
                is ChatMessage.UserText -> lines += "USER: " + clip(message.displayText ?: message.text, maxCharsPerItem)
                is ChatMessage.AgentText -> if (!message.isThinking) lines += "AGENT: " + clip(message.text, maxCharsPerItem)
                is ChatMessage.ToolCallMessage -> {
                    val tool = message.tool
                    val file = (tool.input as? JsonObject)?.let { input -> FILE_KEYS.firstNotNullOfOrNull { (input[it] as? JsonPrimitive)?.content } }
                    if (file != null && tool.name in EDIT_TOOLS && tool.state != ToolState.Error) changed += file
                    val status = when {
                        tool.permission?.status == PermissionStatus.Pending -> "WAITING FOR APPROVAL".also { pending++ }
                        tool.state == ToolState.Error -> "FAILED".also { errors++ }
                        tool.state == ToolState.Running -> "running"
                        else -> "done"
                    }
                    val subject = file ?: (tool.title ?: tool.description ?: commandOf(tool.input))?.let { clip(it, 160) }
                    val result = if (tool.state == ToolState.Error) " → " + clip(textOf(tool.result), maxCharsPerItem) else ""
                    lines += "TOOL ${tool.name}${subject?.let { " ($it)" } ?: ""}: $status$result"
                }
                is ChatMessage.Event -> Unit
                is ChatMessage.Unsupported -> Unit
            }
        }
        return Digest(lines.joinToString("\n"), changed.toList(), errors, pending)
    }

    private fun commandOf(input: JsonElement?): String? = ((input as? JsonObject)?.get("command") as? JsonPrimitive)?.content

    private fun textOf(element: JsonElement?): String = when (element) {
        null -> ""
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private fun clip(text: String, max: Int): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
    }
}

/**
 * The four local features, each bounded to the model's window. A long
 * session is summarised in slices (at most [MAX_CHUNKS], newest kept) and
 * the slice summaries summarised again.
 */
class LocalAssistant(private val ai: LocalAi) {
    suspend fun catchUp(messages: List<ChatMessage>, onPartial: (String) -> Unit = {}): String {
        val digest = SessionDigest.of(messages)
        if (digest.text.isBlank()) throw LocalAiException(LocalAiError.Empty)
        val budget = inputBudget()
        val whole = LocalPrompts.catchUp(digest.text)
        if (ai.countTokens(whole) <= budget + RESERVE) return stream(whole, onPartial)
        val chunks = LocalPrompts.chunk(digest.text, budget - 200, MAX_CHUNKS)
        val summaries = chunks.mapIndexed { index, chunk ->
            ai.complete(LocalPrompts.catchUpChunk(chunk, index, chunks.size))
        }
        val combined = buildString {
            if (digest.changedFiles.isNotEmpty()) appendLine("Files edited: " + digest.changedFiles.takeLast(30).joinToString(", "))
            appendLine("Failed tool calls: ${digest.errors}. Waiting for approval: ${digest.pendingApprovals}.")
            summaries.forEachIndexed { i, s -> appendLine("Part ${i + 1}:\n$s") }
        }
        return stream(LocalPrompts.catchUp(LocalPrompts.bound(combined, budget)), onPartial)
    }

    suspend fun improve(draft: String, recent: List<ChatMessage>, onPartial: (String) -> Unit = {}): String {
        val budget = inputBudget()
        val boundedDraft = LocalPrompts.bound(draft, budget / 2)
        val context = SessionDigest.of(recent.takeLast(12), maxCharsPerItem = 240).text
        var prompt = LocalPrompts.improveInstruction(boundedDraft, LocalPrompts.bound(context, budget / 3))
        if (ai.countTokens(prompt) > budget + RESERVE) prompt = LocalPrompts.improveInstruction(boundedDraft, null)
        return stream(prompt, onPartial)
    }

    suspend fun explain(selection: String, kind: ExplainKind, onPartial: (String) -> Unit = {}): String =
        stream(LocalPrompts.explain(LocalPrompts.bound(selection, inputBudget() - 150), kind), onPartial)

    suspend fun screenshot(image: android.graphics.Bitmap, kind: ScreenshotDraft, note: String, onPartial: (String) -> Unit = {}): String =
        stream(LocalPrompts.screenshot(image, kind, LocalPrompts.bound(note, 400)), onPartial)

    private suspend fun inputBudget(): Int = ai.tokenLimit() - OUTPUT_RESERVE

    private suspend fun stream(prompt: LocalPrompt, onPartial: (String) -> Unit): String {
        var result: String? = null
        var error: LocalAiError? = null
        ai.run(prompt).collect { step ->
            when (step) {
                is LocalRun.Partial -> onPartial(step.text)
                is LocalRun.Done -> result = step.text
                is LocalRun.Failed -> error = step.error
            }
        }
        return result?.takeIf { it.isNotBlank() } ?: throw LocalAiException(error ?: LocalAiError.Unknown)
    }

    companion object {
        const val MAX_CHUNKS = 6
        private const val OUTPUT_RESERVE = 500
        private const val RESERVE = 0
    }
}
