package com.slopus.happy.localai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Whether the local assistant can run, as the UI shows it. */
sealed interface LocalAiState {
    data object Checking : LocalAiState
    /** AICore reports no on-device model for this phone (or AICore is missing). */
    data object Unsupported : LocalAiState
    data object NeedsDownload : LocalAiState
    data class Downloading(val bytes: Long) : LocalAiState
    data object Ready : LocalAiState
    data class DownloadFailed(val error: LocalAiError) : LocalAiState
}

/** One local request's progress. */
sealed interface LocalRun {
    /** The text so far; grows with each item. */
    data class Partial(val text: String) : LocalRun
    data class Done(val text: String) : LocalRun
    data class Failed(val error: LocalAiError) : LocalRun
}

/**
 * The phone's on-device assistant: availability, model download and one
 * request at a time.
 *
 * - Availability comes from AICore at runtime, never from a device list.
 * - Inference runs only while the app is in the foreground ([foreground]);
 *   a request is refused in the background and cancelled when the app
 *   leaves the foreground. There is no foreground service: AICore refuses
 *   those too, and this must never look like a way around that.
 * - AICore's BUSY is retried with backoff; quota and background refusals
 *   are reported, not retried.
 * - Inputs and outputs are never logged and never leave the phone.
 */
class LocalAi(
    private val model: LocalModel,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val backoffMs: List<Long> = listOf(800, 2_000, 5_000),
) {
    private val mutableState = MutableStateFlow<LocalAiState>(LocalAiState.Checking)
    val state: StateFlow<LocalAiState> = mutableState.asStateFlow()
    private val running = Mutex()
    private var downloadJob: Job? = null

    /** Asks AICore again; cheap, and safe to call whenever a surface opens. */
    suspend fun refresh() {
        if (mutableState.value is LocalAiState.Downloading && downloadJob?.isActive == true) return
        // Anything AICore or ML Kit throws means "not here": a chat must never fail over it.
        val availability = try {
            model.availability()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelAvailability.Unavailable
        }
        mutableState.value = when (availability) {
            ModelAvailability.Available -> LocalAiState.Ready
            ModelAvailability.Downloadable -> LocalAiState.NeedsDownload
            ModelAvailability.Downloading -> LocalAiState.Downloading(0)
            ModelAvailability.Unavailable -> LocalAiState.Unsupported
        }
    }

    /** Starts (or rejoins) the model download. AICore keeps downloading if the app goes away. */
    fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            mutableState.value = LocalAiState.Downloading(0)
            model.download().collect { event ->
                mutableState.value = when (event) {
                    ModelDownload.Started -> LocalAiState.Downloading(0)
                    is ModelDownload.Progress -> LocalAiState.Downloading(event.bytes)
                    ModelDownload.Completed -> LocalAiState.Ready
                    is ModelDownload.Failed -> LocalAiState.DownloadFailed(event.error)
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        scope.launch { refresh() }
    }

    suspend fun tokenLimit(): Int = runCatching { model.tokenLimit() }.getOrDefault(DEFAULT_TOKEN_LIMIT).coerceAtLeast(1024)

    /** Token count from the model, or a conservative estimate if counting fails. */
    suspend fun countTokens(prompt: LocalPrompt): Int = try {
        model.countTokens(prompt)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LocalPrompts.estimateTokens(prompt.system) + LocalPrompts.estimateTokens(prompt.text) + if (prompt.image != null) 300 else 0
    }

    /**
     * Runs one request. Only one runs at a time; a second while one runs
     * fails with Busy rather than queueing behind it.
     */
    fun run(prompt: LocalPrompt): Flow<LocalRun> = channelFlow {
        if (!foreground.value) {
            send(LocalRun.Failed(LocalAiError.Background))
            return@channelFlow
        }
        if (!running.tryLock()) {
            send(LocalRun.Failed(LocalAiError.Busy))
            return@channelFlow
        }
        val work = launch {
            try {
                var attempt = 0
                while (true) {
                    val text = StringBuilder()
                    try {
                        model.generate(prompt).collect { chunk ->
                            text.append(chunk)
                            send(LocalRun.Partial(text.toString()))
                        }
                        send(LocalRun.Done(text.toString().trim()))
                        break
                    } catch (e: LocalAiException) {
                        if (e.error == LocalAiError.Busy && attempt < backoffMs.size && text.isEmpty()) {
                            delay(e.retryAfterMs ?: backoffMs[attempt])
                            attempt++
                            continue
                        }
                        send(LocalRun.Failed(e.error))
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(LocalRun.Failed(LocalAiError.Unknown))
            } finally {
                running.unlock()
            }
        }
        // Leaving the foreground ends the request; AICore would refuse it anyway.
        val watcher = launch {
            foreground.first { !it }
            work.cancel()
            send(LocalRun.Failed(LocalAiError.Background))
        }
        work.join()
        watcher.cancel()
    }

    /** Runs [prompt] and returns its final text, throwing [LocalAiException] on failure. */
    suspend fun complete(prompt: LocalPrompt): String {
        var result: String? = null
        var error: LocalAiError? = null
        run(prompt).collect { step ->
            when (step) {
                is LocalRun.Done -> result = step.text
                is LocalRun.Failed -> error = step.error
                is LocalRun.Partial -> Unit
            }
        }
        return result ?: throw LocalAiException(error ?: LocalAiError.Unknown)
    }

    companion object {
        /** Gemini Nano's documented input window, used when AICore doesn't say. */
        const val DEFAULT_TOKEN_LIMIT = 4_000
    }
}
