package com.slopus.happy.localai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** What AICore reports for the on-device model (ML Kit `FeatureStatus`). */
enum class ModelAvailability { Unavailable, Downloadable, Downloading, Available }

sealed interface ModelDownload {
    data object Started : ModelDownload
    data class Progress(val bytes: Long) : ModelDownload
    data object Completed : ModelDownload
    data class Failed(val error: LocalAiError) : ModelDownload
}

/** Why a local request failed, mapped from ML Kit's `GenAiException.ErrorCode`. */
enum class LocalAiError {
    /** AICore is serving another request; retried with backoff first. */
    Busy,
    /** AICore's per-app battery quota is used up for now. */
    Quota,
    /** The app wasn't the top foreground app; AICore refuses background inference. */
    Background,
    TooLarge,
    NotEnoughStorage,
    NeedsSystemUpdate,
    NotSupported,
    InvalidImage,
    Cancelled,
    Network,
    /** Nothing on the phone to work from yet (an empty session). */
    Empty,
    Unknown,
    ;

    companion object {
        fun of(e: GenAiException): LocalAiError = when (e.errorCode) {
            GenAiException.ErrorCode.BUSY -> Busy
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> Quota
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> Background
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> TooLarge
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> NotEnoughStorage
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE, GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> NeedsSystemUpdate
            GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED -> NotSupported
            GenAiException.ErrorCode.INVALID_INPUT_IMAGE -> InvalidImage
            GenAiException.ErrorCode.CANCELLED -> Cancelled
            else -> if (e.message?.contains("resolve host", ignoreCase = true) == true) Network else Unknown
        }
    }
}

class LocalAiException(val error: LocalAiError, val retryAfterMs: Long? = null) : Exception(error.name)

/**
 * The on-device model behind the local assistant. One implementation talks
 * to Gemini Nano through AICore (ML Kit GenAI Prompt API); tests use fakes.
 * Nothing here sends anything off the phone.
 */
interface LocalModel {
    suspend fun availability(): ModelAvailability
    fun download(): Flow<ModelDownload>
    /** The model's input window in tokens. */
    suspend fun tokenLimit(): Int
    suspend fun countTokens(prompt: LocalPrompt): Int
    /** Streams the response text as it grows; each item is the next chunk. */
    fun generate(prompt: LocalPrompt): Flow<String>
}

/** One request: fixed instructions, the user turn, and at most one picture. */
data class LocalPrompt(
    val system: String,
    val text: String,
    val image: Bitmap? = null,
    val maxOutputTokens: Int = 512,
    val temperature: Float = 0.2f,
)

/**
 * Gemini Nano through AICore. Created lazily; [close] releases AICore's
 * binding. ML Kit's init provider is removed from the manifest (it would run
 * at every cold start), so ML Kit is initialized here on first use.
 */
class MlKitLocalModel(private val context: android.content.Context) : LocalModel {
    private val model: GenerativeModel by lazy {
        com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context.applicationContext)
        Generation.getClient()
    }

    override suspend fun availability(): ModelAvailability = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> ModelAvailability.Available
            FeatureStatus.DOWNLOADING -> ModelAvailability.Downloading
            FeatureStatus.DOWNLOADABLE -> ModelAvailability.Downloadable
            else -> ModelAvailability.Unavailable
        }
    } catch (e: GenAiException) {
        ModelAvailability.Unavailable
    } catch (e: SecurityException) {
        ModelAvailability.Unavailable
    } catch (e: IllegalStateException) {
        // ML Kit or AICore couldn't start on this device.
        ModelAvailability.Unavailable
    }

    override fun download(): Flow<ModelDownload> = model.download()
        .map { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> ModelDownload.Started
                is DownloadStatus.DownloadProgress -> ModelDownload.Progress(status.totalBytesDownloaded)
                is DownloadStatus.DownloadCompleted -> ModelDownload.Completed
                is DownloadStatus.DownloadFailed -> ModelDownload.Failed(LocalAiError.of(status.e))
            }
        }
        .catch { e -> emit(ModelDownload.Failed(if (e is GenAiException) LocalAiError.of(e) else LocalAiError.Unknown)) }

    override suspend fun tokenLimit(): Int = guarded { model.getTokenLimit() }

    override suspend fun countTokens(prompt: LocalPrompt): Int = guarded { model.countTokens(request(prompt)).totalTokens }

    override fun generate(prompt: LocalPrompt): Flow<String> = model.generateContentStream(request(prompt))
        .mapNotNull { response -> response.candidates.firstOrNull()?.text?.takeIf { it.isNotEmpty() } }
        .catch { e -> throw if (e is GenAiException) LocalAiException(LocalAiError.of(e), e.retryDelay?.toMillis()) else e }

    fun close() = model.close()

    private fun request(prompt: LocalPrompt): GenerateContentRequest {
        val system = SystemInstruction(prompt.system)
        val text = TextPart(prompt.text)
        val builder = prompt.image?.let { GenerateContentRequest.Builder(system, ImagePart(it), text) }
            ?: GenerateContentRequest.Builder(system, text)
        builder.temperature = prompt.temperature
        builder.maxOutputTokens = prompt.maxOutputTokens
        return builder.build()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: GenAiException) {
        throw LocalAiException(LocalAiError.of(e), e.retryDelay?.toMillis())
    }
}
