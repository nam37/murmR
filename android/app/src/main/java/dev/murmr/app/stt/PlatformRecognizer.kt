package dev.murmr.app.stt

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log

/** Outcome of asking the platform for a recogniser under an [OfflinePolicy]. */
sealed interface RecognizerResult {
    data class Ready(val recognizer: SpeechRecognizer) : RecognizerResult
    data class Unavailable(val message: String) : RecognizerResult
}

/**
 * Creates a platform SpeechRecognizer honouring [policy]: the on-device recogniser when
 * Android 12+ offers one, otherwise the default engine only if the policy allows it. Shared by
 * the engines so the privacy rule lives in one place.
 */
fun createPlatformRecognizer(context: Context, policy: OfflinePolicy, tag: String): RecognizerResult {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        return RecognizerResult.Unavailable("No speech recognition service on this phone")
    }
    return if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    ) {
        Log.i(tag, "using on-device recognizer")
        RecognizerResult.Ready(SpeechRecognizer.createOnDeviceSpeechRecognizer(context))
    } else if (policy == OfflinePolicy.REQUIRED) {
        RecognizerResult.Unavailable(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "On-device speech recognition is not available. Install the offline language pack " +
                    "in system speech settings, or allow online recognition."
            } else {
                "Android 9 to 11 cannot guarantee on-device recognition. Allow online recognition " +
                    "to use dictation on this phone."
            },
        )
    } else {
        Log.i(tag, "using default recognizer; offline preferred but not guaranteed")
        RecognizerResult.Ready(SpeechRecognizer.createSpeechRecognizer(context))
    }
}

/** Best hypothesis from a results bundle, or empty. */
fun bestRecognitionText(bundle: Bundle?): String =
    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

/** The recogniser's RMS callback runs roughly -2..10 dB; map it to 0..1 for the waveform. */
fun normalizeRecognizerRms(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

fun describeSpeechError(code: Int): String = when (code) {
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
