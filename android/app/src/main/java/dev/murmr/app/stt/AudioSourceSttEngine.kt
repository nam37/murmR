package dev.murmr.app.stt

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dev.murmr.app.diag.EventLog
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * [SttEngine] that owns the microphone and feeds the platform recogniser an audio stream.
 *
 * The platform recogniser, left to itself, ends a session at every pause of a second or two,
 * which costs a restart gap and an earcon. Here the app records audio with [AudioRecord] and
 * hands the recogniser the read end of a pipe (Android 13's `EXTRA_AUDIO_SOURCE`) in segmented
 * mode. The recogniser then transcribes whatever arrives until the stream ends, which happens
 * exactly when [stop] closes the pipe after the capture tail. No endpointing, no restarts, and
 * no tones, because the recogniser never opens a microphone session of its own.
 *
 * Experimental: whether the on-device engine accepts a supplied stream is up to that engine.
 * If it refuses, [start] reports an error and the user can switch back in Settings.
 *
 * Owning the audio path also makes a pre-roll buffer possible later (keep recording while idle,
 * replay the last second at press), which is the only fix for a clipped first word.
 *
 * Callers must hold RECORD_AUDIO; MainActivity checks it before the service starts, hence the
 * MissingPermission suppression. Public methods run on the main thread; the writer is a thread.
 */
@RequiresApi(33)
@SuppressLint("MissingPermission")
class AudioSourceSttEngine(
    private val context: Context,
    /** BCP-47 tag such as "en-US", or null for the device default. */
    private val language: String? = null,
    offlinePolicy: OfflinePolicy = OfflinePolicy.REQUIRED,
    private val enableFormatting: Boolean = true,
) : SttEngine {

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()

    @Volatile
    override var offlinePolicy: OfflinePolicy = offlinePolicy

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    // Per-hold state, reset in start().
    private val committed = StringBuilder()
    private var partial = ""
    private var stopping = false
    private var finished = false
    private var readEnd: ParcelFileDescriptor? = null

    /** Cleared to end capture; the writer thread then closes the pipe, which ends the session. */
    @Volatile
    private var capturing = false

    override fun start() {
        val recognizer = obtainRecognizer() ?: return
        committed.setLength(0)
        partial = ""
        stopping = false
        finished = false

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            emit(SttEvent.Error("Could not open an audio pipe: ${e.message}"))
            return
        }
        val read = pipe[0]
        val write = pipe[1]

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer, SAMPLE_RATE * BYTES_PER_SAMPLE),   // one second of headroom
            )
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            runCatching { read.close() }
            runCatching { write.close() }
            emit(SttEvent.Error("Microphone unavailable"))
            return
        }

        recorder.startRecording()
        capturing = true
        readEnd = read
        // The microphone is open from this instant; everything said from now on is buffered in
        // the pipe even if the recogniser takes a moment to start reading.
        emit(SttEvent.Ready)

        thread(name = "murmr-audio") {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (capturing) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        EventLog.log(TAG, "AudioRecord read error $n")
                        break
                    }
                    if (n == 0) continue
                    emit(SttEvent.Level(levelOf(buffer, n)))
                    out.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                // The recogniser closed its end (finished, cancelled, or rejected the stream).
                EventLog.log(TAG, "audio pipe closed by reader: ${e.message}")
            } finally {
                runCatching { out.close() }   // EOF: the recogniser finalises the last segment
                runCatching { recorder.stop() }
                recorder.release()
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, ENCODING)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            // Segmented over the supplied stream: phrases arrive as segments and the session
            // lasts until the stream ends.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            if (enableFormatting) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            }
        }
        recognizer.startListening(intent)
    }

    override fun stop() {
        stopping = true
        if (finished) return
        if (!capturing) {
            // Capture already ended (an error mid-hold); deliver what we have.
            finalizeNow()
            return
        }
        armStopTimeout()
        capturing = false   // writer closes the pipe -> EOF -> final segment -> end of session
    }

    override fun cancel() {
        stopping = true
        finished = true
        capturing = false
        cancelStopTimeout()
        recognizer?.cancel()
        closeReadEnd()
    }

    override fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    // ---- Result plumbing --------------------------------------------------------------------

    private fun appendPhrase(text: String) = committed.appendPhrase(text)

    private fun runningTranscript(): String = when {
        partial.isBlank() -> committed.toString()
        committed.isEmpty() -> partial
        else -> "$committed $partial"
    }

    private fun finalizeNow() {
        if (finished) return
        finished = true
        capturing = false
        cancelStopTimeout()
        closeReadEnd()
        EventLog.log(TAG, "hold finalised: ${committed.length} chars (audio-source engine)")
        emit(SttEvent.Final(runningTranscript().trim()))
    }

    private fun closeReadEnd() {
        readEnd?.let { runCatching { it.close() } }
        readEnd = null
    }

    private val stopTimeoutRunnable = Runnable {
        if (finished) return@Runnable
        EventLog.log(TAG, "recogniser did not finish within ${STOP_TIMEOUT_MS}ms of EOF; finalising")
        recognizer?.cancel()
        finalizeNow()
    }

    private fun armStopTimeout() {
        cancelStopTimeout()
        mainHandler.postDelayed(stopTimeoutRunnable, STOP_TIMEOUT_MS)
    }

    private fun cancelStopTimeout() = mainHandler.removeCallbacks(stopTimeoutRunnable)

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
        override fun onReadyForSpeech(params: Bundle?) = Unit   // Ready was emitted when the mic opened
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit         // level comes from our own samples
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

        override fun onSegmentResults(segmentResults: Bundle) {
            appendPhrase(bestRecognitionText(segmentResults))
            partial = ""
            emit(SttEvent.Partial(committed.toString()))
        }

        override fun onEndOfSegmentedSession() {
            if (stopping) finalizeNow() else holdText("session ended before release")
        }

        override fun onResults(results: Bundle?) {
            // A non-segmented engine: the whole stream came back as one result.
            appendPhrase(bestRecognitionText(results))
            partial = ""
            if (stopping) finalizeNow() else holdText("results arrived before release")
        }

        override fun onError(error: Int) {
            if (stopping) {
                finalizeNow()
                return
            }
            if (committed.isEmpty() && partial.isEmpty()) {
                capturing = false
                finished = true
                closeReadEnd()
                EventLog.log(TAG, "STT error $error with nothing captured: ${describeSpeechError(error)}")
                emit(SttEvent.Error(describeSpeechError(error), error))
            } else {
                holdText("STT error $error")
            }
        }
    }

    /**
     * The recogniser gave up mid-hold. Stop capturing but keep the text; nothing is typed
     * until the user releases, when [stop] finalises.
     */
    private fun holdText(reason: String) {
        capturing = false
        EventLog.log(TAG, "$reason; holding ${committed.length} chars until release")
    }

    private fun emit(event: SttEvent) {
        if (!_events.tryEmit(event) && event !is SttEvent.Level) EventLog.log(TAG, "dropped event $event")
    }

    /** RMS of a PCM16 chunk mapped to 0..1: -50 dBFS is silence, -10 dBFS is loud speech. */
    private fun levelOf(buffer: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / 32768.0
        val db = 20.0 * log10(rms + 1e-9)
        return ((db + 50.0) / 40.0).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "AudioSourceSttEngine"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        /** 100 ms of audio per write; also the waveform's update rate. */
        const val CHUNK_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 10

        /** After the stream ends, how long to wait for the recogniser's last result. */
        const val STOP_TIMEOUT_MS = 4_000L
    }
}
