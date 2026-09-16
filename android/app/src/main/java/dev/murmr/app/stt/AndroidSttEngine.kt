package dev.murmr.app.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [SttEngine] backed by the platform SpeechRecognizer.
 *
 * With [OfflinePolicy.REQUIRED] (the default) it only ever uses the on-device recogniser that
 * Android 12+ exposes through `createOnDeviceSpeechRecognizer`, and fails with a clear message
 * when that is unavailable. It never silently falls back to the default engine, because the
 * "prefer offline" extra is a hint the platform is allowed to ignore.
 *
 * All methods must be called on the main thread (a SpeechRecognizer requirement).
 */
class AndroidSttEngine(
    private val context: Context,
    /** BCP-47 tag such as "en-US", or null for the device default. */
    private val language: String? = null,
    private val offlinePolicy: OfflinePolicy = OfflinePolicy.REQUIRED,
) : SttEngine {

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null

    override fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            emit(SttEvent.Error("No speech recognition service on this phone"))
            return
        }
        val recognizer = obtainRecognizer() ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            // The user ends the utterance by releasing the button, so ask the recogniser to
            // tolerate long pauses. These are hints; some engines ignore them, which is why the
            // service keeps text across recogniser sessions within one hold.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        }
        recognizer.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    /** Returns the recogniser to use, or null after emitting an error when policy forbids one. */
    private fun obtainRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            Log.i(TAG, "using on-device recognizer")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else if (offlinePolicy == OfflinePolicy.REQUIRED) {
            emit(SttEvent.Error(onDeviceUnavailableMessage()))
            return null
        } else {
            Log.i(TAG, "using default recognizer; offline preferred but not guaranteed")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        created.setRecognitionListener(listener)
        recognizer = created
        return created
    }

    private fun onDeviceUnavailableMessage(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "On-device speech recognition is not available. Install the offline language pack " +
                "in system speech settings, or allow online recognition."
        } else {
            "Android 9 to 11 cannot guarantee on-device recognition. Allow online recognition " +
                "to use dictation on this phone."
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = emit(SttEvent.Ready)
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) = emit(SttEvent.Error(describe(error), error))

        override fun onResults(results: Bundle?) = emit(SttEvent.Final(bestText(results)))

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestText(partialResults)
            if (text.isNotEmpty()) emit(SttEvent.Partial(text))
        }
    }

    private fun bestText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun emit(event: SttEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG, "dropped event $event")
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_SERVER -> "Speech server error"
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
        SpeechRecognizer.ERROR_NO_MATCH -> "Could not make out any words"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
        // Constants below were added in API 31; literal values keep minSdk 28 lint quiet.
        10 -> "Too many requests"                       // ERROR_TOO_MANY_REQUESTS
        11 -> "Speech service disconnected"             // ERROR_SERVER_DISCONNECTED
        12 -> "Language not supported"                  // ERROR_LANGUAGE_NOT_SUPPORTED
        13 -> "Offline language pack not installed"     // ERROR_LANGUAGE_UNAVAILABLE
        else -> "Speech error $code"
    }

    private companion object {
        const val TAG = "AndroidSttEngine"
    }
}
