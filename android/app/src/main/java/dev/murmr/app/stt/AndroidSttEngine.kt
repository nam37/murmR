package dev.murmr.app.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.murmr.app.diag.EventLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [SttEngine] backed by the platform SpeechRecognizer, which owns the microphone.
 *
 * This engine owns *continuity* for one hold. A single [start]..[stop] is one continuous
 * dictation even though the platform recogniser wants to end after every pause:
 *
 *  - On Android 13+ it asks for a segmented session, where one recogniser session survives
 *    pauses and returns each phrase through [RecognitionListener.onSegmentResults]. When the
 *    engine honours it, there are no mid-hold restarts, no start/stop earcons between phrases,
 *    and sentence-aware capitalisation.
 *  - When segmented mode is not honoured (older engines, or the extra is ignored), the engine
 *    falls back to restarting the recogniser after each result and stitching the phrases
 *    together. This is invisible to callers: either way, [Partial] carries the running
 *    transcript and exactly one [Final] (or [Error]) is emitted per hold.
 *
 * The restart fallback still costs a short gap and, on most engines, an earcon per restart.
 * [AudioSourceSttEngine] avoids both by owning the microphone itself.
 *
 * On Android 13+ it also asks for automatic punctuation and capitalisation ([enableFormatting]).
 * All methods must be called on the main thread (a SpeechRecognizer requirement).
 */
class AndroidSttEngine(
    private val context: Context,
    /** BCP-47 tag such as "en-US", or null for the device default. */
    private val language: String? = null,
    offlinePolicy: OfflinePolicy = OfflinePolicy.REQUIRED,
    /** Ask the Android 13+ engine for automatic punctuation and capitalisation. */
    private val enableFormatting: Boolean = Build.VERSION.SDK_INT >= 33,
    /** Ask the Android 13+ engine for a pause-surviving segmented session. */
    private val attemptSegmentedSession: Boolean = Build.VERSION.SDK_INT >= 33,
) : SttEngine {

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()

    @Volatile
    override var offlinePolicy: OfflinePolicy = offlinePolicy

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    // Per-hold state, reset in start().
    private val committed = StringBuilder()   // phrases finalised so far this hold
    private var partial = ""                  // the in-progress phrase
    private var stopping = false              // user released; deliver what we have
    private var finished = false              // Final/Error already emitted for this hold
    private var captureLive = false           // a recogniser session is currently listening
    private var sessionStartedAt = 0L
    private var restarts = 0
    private var segmentedEnabled = false      // segmented mode still believed to work this hold
    private var segmentSeen = false           // the engine actually delivered a segment result

    override fun start() {
        val recognizer = obtainRecognizer() ?: return
        committed.setLength(0)
        partial = ""
        stopping = false
        finished = false
        restarts = 0
        segmentedEnabled = attemptSegmentedSession
        segmentSeen = false
        beginSession(recognizer)
    }

    override fun stop() {
        stopping = true
        if (finished) return
        if (!captureLive) {
            // Nothing is listening (capture already ended); deliver immediately.
            finalizeNow()
            return
        }
        armStopTimeout()
        recognizer?.stopListening()
    }

    override fun cancel() {
        stopping = true
        finished = true
        cancelStopTimeout()
        recognizer?.cancel()
    }

    override fun destroy() {
        cancelStopTimeout()
        recognizer?.destroy()
        recognizer = null
    }

    // ---- Session lifecycle ------------------------------------------------------------------

    private fun beginSession(recognizer: SpeechRecognizer) {
        captureLive = true
        sessionStartedAt = SystemClock.elapsedRealtime()
        recognizer.startListening(buildIntent(segmentedEnabled))
    }

    /** Restarts the recogniser to keep capturing across a pause (the non-segmented path). */
    private fun restartSession() {
        partial = ""
        restarts++
        emit(SttEvent.Partial(committed.toString()))   // refresh UI without the stale partial
        // Post rather than restart inside the callback, to avoid recogniser re-entrancy.
        mainHandler.post {
            val r = recognizer
            if (stopping || finished || r == null) return@post
            beginSession(r)
        }
    }

    private fun buildIntent(segmented: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            // Tolerate long pauses; these are hints some engines ignore, which is why the engine
            // also stitches sessions together.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            if (Build.VERSION.SDK_INT >= 33) {
                if (enableFormatting) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                    // Deliver words as spoken; the profanity mask would otherwise star them out.
                    putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
                }
                if (segmented) {
                    // One session across pauses; each phrase arrives via onSegmentResults. The
                    // value names the extra that bounds a segment (our long silence length).
                    putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    )
                }
            }
        }

    // ---- Result plumbing --------------------------------------------------------------------

    private fun appendPhrase(text: String) = committed.appendPhrase(text)

    /** Committed phrases plus the current in-progress phrase. */
    private fun runningTranscript(): String = when {
        partial.isBlank() -> committed.toString()
        committed.isEmpty() -> partial
        else -> "$committed $partial"
    }

    private fun finalizeNow() {
        if (finished) return
        finished = true
        captureLive = false
        cancelStopTimeout()
        EventLog.log(TAG, "hold finalised: ${committed.length} chars, restarts=$restarts, segmentedHonoured=$segmentSeen")
        emit(SttEvent.Final(runningTranscript().trim()))
    }

    private val stopTimeoutRunnable = Runnable {
        if (finished) return@Runnable
        EventLog.log(TAG, "recogniser did not deliver a final within ${STOP_TIMEOUT_MS}ms; finalising")
        recognizer?.cancel()
        finalizeNow()
    }

    private fun armStopTimeout() {
        cancelStopTimeout()
        mainHandler.postDelayed(stopTimeoutRunnable, STOP_TIMEOUT_MS)
    }

    private fun cancelStopTimeout() = mainHandler.removeCallbacks(stopTimeoutRunnable)

    private fun isPauseError(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_NO_MATCH

    private fun obtainRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        return when (val result = createPlatformRecognizer(context, offlinePolicy, TAG)) {
            is RecognizerResult.Ready -> result.recognizer.also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
            is RecognizerResult.Unavailable -> {
                emit(SttEvent.Error(result.message))
                null
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = emit(SttEvent.Ready)
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = emit(SttEvent.Level(normalizeRecognizerRms(rmsdB)))
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestRecognitionText(partialResults)
            if (text.isNotEmpty()) {
                partial = text
                emit(SttEvent.Partial(runningTranscript()))
            }
        }

        // Non-segmented path: this session ended (a phrase, or the whole thing on stop).
        override fun onResults(results: Bundle?) {
            captureLive = false
            appendPhrase(bestRecognitionText(results))
            partial = ""
            if (stopping) finalizeNow() else restartSession()
        }

        // Segmented path (Android 13+): one phrase within a still-open session.
        override fun onSegmentResults(segmentResults: Bundle) {
            segmentSeen = true
            appendPhrase(bestRecognitionText(segmentResults))
            partial = ""
            emit(SttEvent.Partial(committed.toString()))
        }

        // Segmented path: the whole session ended.
        override fun onEndOfSegmentedSession() {
            captureLive = false
            if (stopping) finalizeNow() else restartSession()
        }

        override fun onError(error: Int) {
            captureLive = false
            if (stopping) {
                // The user has released; deliver whatever we captured rather than an error.
                finalizeNow()
                return
            }
            val sessionMs = SystemClock.elapsedRealtime() - sessionStartedAt
            val pause = isPauseError(error)
            when {
                pause && sessionMs >= MIN_SESSION_MS && restarts < MAX_RESTARTS -> {
                    // A pause the engine gave up on. If we asked for a segmented session and
                    // still got here, the engine is not honouring it; fall back to restarts.
                    if (segmentedEnabled) {
                        EventLog.log(TAG, "segmented session not honoured; using restart fallback")
                        segmentedEnabled = false
                    }
                    restartSession()
                }
                committed.isEmpty() && partial.isEmpty() -> {
                    // Nothing captured and we cannot usefully continue: surface the error.
                    EventLog.log(TAG, "STT error $error with nothing captured: ${describeSpeechError(error)}")
                    finished = true
                    emit(SttEvent.Error(describeSpeechError(error), error))
                }
                else -> {
                    // We have text but capture broke or is looping. Stop trying and keep the
                    // text; it will be delivered when the user releases (stop() finalises).
                    EventLog.log(TAG, "STT error $error; holding ${committed.length} chars until release")
                }
            }
        }
    }

    private fun emit(event: SttEvent) {
        // Level events are frequent and disposable; a dropped one is not worth a log line.
        if (!_events.tryEmit(event) && event !is SttEvent.Level) EventLog.log(TAG, "dropped event $event")
    }

    private companion object {
        const val TAG = "AndroidSttEngine"

        /** After the user releases, how long to wait for a final before forcing one. */
        const val STOP_TIMEOUT_MS = 4_000L

        /** Sessions that fail faster than this are not restarted (tight-loop guard). */
        const val MIN_SESSION_MS = 700L

        /** Hard cap on restarts within one hold, a backstop against a restart loop. */
        const val MAX_RESTARTS = 200
    }
}
