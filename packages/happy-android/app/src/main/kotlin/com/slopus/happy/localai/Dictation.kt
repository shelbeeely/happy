package com.slopus.happy.localai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import java.util.Locale
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Push-to-talk's state as the composer shows it. */
sealed interface DictationState {
    data object Idle : DictationState
    data class Listening(val partial: String) : DictationState
    /** Finished; [text] goes into the draft for editing, never straight to the agent. */
    data class Transcribed(val text: String) : DictationState
    data class Failed(val reason: DictationProblem) : DictationState
}

enum class DictationProblem {
    /** No on-device recognizer on this phone (or Android older than 12). */
    Unavailable,
    /** The language's on-device model isn't installed; a download was requested. */
    LanguageNotInstalled,
    LanguageUnsupported,
    NoMatch,
    Permission,
    Busy,
    Other,
}

/** Whether the language can be recognized on the phone now. */
enum class LanguageSupport { Installed, Downloadable, Downloading, Unsupported, Unknown }

/**
 * On-device speech recognition for drafting instructions. It only uses
 * the on-device recognizer (`createOnDeviceSpeechRecognizer`): audio never
 * goes to a cloud recognizer, so where none exists dictation is
 * unavailable rather than quietly online. Results become an editable
 * draft; nothing is sent without the person pressing Send.
 */
class Dictation(private val context: Context, private val mainExecutor: Executor) {
    private val mutableState = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = mutableState.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Checks the language's on-device model (Android 13+); older versions say Unknown. */
    suspend fun languageSupport(locale: Locale = Locale.getDefault()): LanguageSupport {
        if (!available) return LanguageSupport.Unsupported
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return LanguageSupport.Unknown
        return checkSupport(locale)
    }

    /** Asks the system to download the language's on-device model (Android 13+). */
    @MainThread
    fun downloadLanguage(locale: Locale = Locale.getDefault()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !available) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            r.triggerModelDownload(intent(locale))
        } finally {
            r.destroy()
        }
    }

    @MainThread
    fun start(locale: Locale = Locale.getDefault()) {
        if (!available) {
            mutableState.value = DictationState.Failed(DictationProblem.Unavailable)
            return
        }
        cancel()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(Listener())
        mutableState.value = DictationState.Listening("")
        r.startListening(intent(locale))
    }

    /** Release of push-to-talk: finish and transcribe what was said. */
    @MainThread
    fun stop() {
        recognizer?.stopListening()
    }

    @MainThread
    fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (mutableState.value is DictationState.Listening) mutableState.value = DictationState.Idle
    }

    /** The transcript has been taken into the draft. */
    fun consume() {
        if (mutableState.value !is DictationState.Listening) mutableState.value = DictationState.Idle
    }

    private fun intent(locale: Locale) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkSupport(locale: Locale): LanguageSupport = suspendCancellableCoroutine { continuation ->
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val tag = locale.toLanguageTag()
        fun matches(list: List<String>) = list.any { it.equals(tag, ignoreCase = true) || it.substringBefore('-').equals(locale.language, ignoreCase = true) }
        r.checkRecognitionSupport(intent(locale), mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                r.destroy()
                if (!continuation.isActive) return
                continuation.resume(
                    when {
                        matches(support.installedOnDeviceLanguages) -> LanguageSupport.Installed
                        matches(support.pendingOnDeviceLanguages) -> LanguageSupport.Downloading
                        matches(support.supportedOnDeviceLanguages) -> LanguageSupport.Downloadable
                        else -> LanguageSupport.Unsupported
                    },
                )
            }

            override fun onError(error: Int) {
                r.destroy()
                if (continuation.isActive) continuation.resume(LanguageSupport.Unknown)
            }
        })
        continuation.invokeOnCancellation { mainExecutor.execute { r.destroy() } }
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (mutableState.value is DictationState.Listening) mutableState.value = DictationState.Listening(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            mutableState.value = if (text.isEmpty()) DictationState.Failed(DictationProblem.NoMatch) else DictationState.Transcribed(text)
            release()
        }

        override fun onError(error: Int) {
            mutableState.value = DictationState.Failed(problemOf(error))
            release()
        }

        private fun release() {
            recognizer?.destroy()
            recognizer = null
        }
    }

    companion object {
        fun problemOf(error: Int): DictationProblem = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> DictationProblem.NoMatch
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> DictationProblem.Permission
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> DictationProblem.Busy
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> DictationProblem.LanguageUnsupported
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> DictationProblem.LanguageNotInstalled
            else -> DictationProblem.Other
        }
    }
}
