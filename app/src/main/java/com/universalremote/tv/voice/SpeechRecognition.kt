package com.universalremote.tv.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable state of the speech-recognition fallback. */
sealed interface SpeechState {
    data object Idle : SpeechState
    data object Listening : SpeechState
    data class Partial(val text: String) : SpeechState
    data class Final(val text: String) : SpeechState
    data class Error(val message: String) : SpeechState
}

/**
 * On-device speech recognition used when the TV does not offer its native voice channel.
 *
 * This is the platform `SpeechRecognizer` (normally backed by Google's recogniser). It converts
 * speech to text; the text is then typed into the TV. It is **not** Google Assistant and does not
 * give the TV the assistant's own capabilities — the TV only receives typed text.
 */
class SpeechRecognition(context: Context) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(languageTag: String) {
        if (!isAvailable) {
            _state.value = SpeechState.Error("Speech recognition is not available on this device.")
            return
        }
        stop()
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = SpeechState.Listening
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                _state.value = SpeechState.Error(describeError(error))
                stop()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                _state.value = if (text.isBlank()) {
                    SpeechState.Error("Nothing was recognised.")
                } else {
                    SpeechState.Final(text)
                }
                stop()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { _state.value = SpeechState.Partial(it) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        try {
            created.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start speech recognition", e)
            _state.value = SpeechState.Error("Speech recognition could not start: ${e.message}")
            stop()
        }
    }

    fun stop() {
        val current = recognizer ?: return
        recognizer = null
        try {
            current.stopListening()
        } catch (_: Exception) {
        }
        try {
            current.cancel()
        } catch (_: Exception) {
        }
        try {
            current.destroy()
        } catch (_: Exception) {
        }
        if (_state.value is SpeechState.Listening || _state.value is SpeechState.Partial) {
            _state.value = SpeechState.Idle
        }
    }

    fun clear() {
        stop()
        _state.value = SpeechState.Idle
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was interrupted."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK -> "The recogniser needs a network connection."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The recogniser timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognised."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Try again."
        SpeechRecognizer.ERROR_SERVER -> "The recogniser reported a server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
        else -> "Speech recognition failed (error $error)."
    }

    companion object {
        private const val TAG = "SpeechRecognition"
    }
}
