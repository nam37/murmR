package dev.murmr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.murmr.app.MainActivity
import dev.murmr.app.MurmrApp
import dev.murmr.app.R
import dev.murmr.app.hid.HidKeyboard
import dev.murmr.app.hid.HostDevice
import dev.murmr.app.hid.TextTyper
import dev.murmr.app.stt.AndroidSttEngine
import dev.murmr.app.stt.OfflinePolicy
import dev.murmr.app.stt.SttEngine
import dev.murmr.app.stt.SttEvent
import dev.murmr.app.transport.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the moving parts and the push-to-talk state machine:
 *
 *     pttDown() -> LISTENING --pttUp()--> FINISHING -> TYPING -> SENT -> IDLE
 *
 * Continuity within a hold (spanning pauses, punctuation, etc.) belongs to the [SttEngine]; this
 * service only drives the gesture and types the result. Its own reliability rules (see
 * docs/architecture.md, "Reliability rules"):
 *
 *  - **Release grace window.** On release the microphone is kept open briefly so the last word
 *    is not clipped: it stays open until [GRACE_QUIET_MS] passes with no new partial, or the
 *    [GRACE_MAX_MS] cap is reached, and only then is the engine told to stop.
 *  - **Nothing is typed mid-hold.** The engine emits one Final on stop; that is what gets typed.
 *  - **Recogniser tones are muted** for the duration of a hold, so the platform's start/stop
 *    earcons (and any restart click) are silenced.
 *  - **Haptics mark the moments the eyes miss**: a tick when the microphone actually opens and a
 *    double tick when the text has reached the computer, because the user is usually looking at
 *    the computer, not the phone.
 *
 * The activity binds to it for [ui] state and user actions. Everything runs on the main thread:
 * SpeechRecognizer requires it, and the Bluetooth callbacks are delivered there too.
 */
class MurmrService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: MurmrService get() = this@MurmrService
    }

    private val binder = LocalBinder()

    private lateinit var keyboard: HidKeyboard
    private lateinit var stt: SttEngine
    private lateinit var transport: Transport
    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Append a space after each utterance so consecutive dictations do not run together. */
    var appendTrailingSpace = true

    // State of the current hold. Reset in pttDown(). Timestamps feed the timing log/line.
    private var pressedAt = 0L
    private var readyAt = 0L
    private var releasedAt = 0L
    private var stoppedAt = 0L
    private var finalAt = 0L
    private var lastPartialAt = 0L
    private var graceJob: Job? = null
    private var sentReset: Job? = null
    private var tonesMuted = false

    private var started = false

    override fun onCreate() {
        super.onCreate()
        keyboard = HidKeyboard(this)
        stt = AndroidSttEngine(this, offlinePolicy = OfflinePolicy.REQUIRED)
        transport = TextTyper(keyboard)

        lifecycleScope.launch {
            keyboard.state.collect { hidState ->
                _ui.update {
                    it.copy(
                        hid = hidState,
                        lastHost = (hidState as? HidKeyboard.State.Connected)?.hostName ?: it.lastHost,
                    )
                }
                updateNotification()
            }
        }
        lifecycleScope.launch {
            stt.events.collect(::onSttEvent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground()
        if (!started) {
            started = true
            keyboard.start()
        }
        // No automatic restart: a microphone foreground service may only be started from the
        // foreground on Android 14+, so a system-initiated restart would crash.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        graceJob?.cancel()
        sentReset?.cancel()
        restoreTones()
        stt.destroy()
        keyboard.stop()
        super.onDestroy()
    }

    // ---- Push to talk -----------------------------------------------------------------------

    fun pttDown() {
        val phase = _ui.value.phase
        if (phase != PttPhase.IDLE && phase != PttPhase.SENT) return
        if (!transport.isReady) {
            // Same rule as the on-screen button, applied here so Volume Down cannot bypass it.
            _ui.update { it.copy(error = "Connect to a computer first") }
            return
        }
        sentReset?.cancel()
        val now = SystemClock.elapsedRealtime()
        pressedAt = now
        readyAt = 0L
        stoppedAt = 0L
        finalAt = 0L
        lastPartialAt = now
        muteTones()
        _ui.update {
            it.copy(phase = PttPhase.LISTENING, partial = "", level = 0f, deliveredChars = 0, error = null, timing = null)
        }
        stt.start()
    }

    fun pttUp() {
        if (_ui.value.phase != PttPhase.LISTENING) return
        releasedAt = SystemClock.elapsedRealtime()
        _ui.update { it.copy(phase = PttPhase.FINISHING) }
        // Capture tail: the recogniser keeps capturing for at least GRACE_MIN_MS after release,
        // because a pause between partial results is not evidence of acoustic silence. Beyond
        // that it keeps going while partials are still arriving, up to GRACE_MAX_MS.
        graceJob = lifecycleScope.launch {
            delay(GRACE_MIN_MS)
            val cap = releasedAt + GRACE_MAX_MS
            while (SystemClock.elapsedRealtime() < cap) {
                if (SystemClock.elapsedRealtime() - lastPartialAt >= GRACE_QUIET_MS) break
                delay(GRACE_STEP_MS)
            }
            stoppedAt = SystemClock.elapsedRealtime()
            Log.i(TAG_TIMING, "release-to-stop ${stoppedAt - releasedAt} ms")
            stt.stop()
        }
    }

    private fun onSttEvent(event: SttEvent) {
        when (event) {
            SttEvent.Ready -> {
                if (readyAt == 0L) {
                    readyAt = SystemClock.elapsedRealtime()
                    Log.i(TAG_TIMING, "press-to-ready ${readyAt - pressedAt} ms")
                }
                tick()
            }

            is SttEvent.Partial -> {
                lastPartialAt = SystemClock.elapsedRealtime()
                _ui.update { it.copy(partial = event.text) }
            }

            is SttEvent.Level -> {
                val phase = _ui.value.phase
                if (phase == PttPhase.LISTENING || phase == PttPhase.FINISHING) {
                    _ui.update { it.copy(level = normalizeLevel(event.rmsDb)) }
                }
            }

            is SttEvent.Final -> {
                finalAt = SystemClock.elapsedRealtime()
                Log.i(
                    TAG_TIMING,
                    "final: release-to-final ${finalAt - releasedAt} ms, " +
                        "stop-to-final ${if (stoppedAt > 0) finalAt - stoppedAt else -1} ms, " +
                        "hold ${releasedAt - pressedAt} ms, ${event.text.length} chars",
                )
                finishWith(event.text)
            }

            is SttEvent.Error -> {
                Log.w(TAG, "STT error ${event.code}: ${event.message}")
                graceJob?.cancel()
                restoreTones()
                _ui.update { it.copy(phase = PttPhase.IDLE, partial = "", level = 0f, error = event.message) }
            }
        }
    }

    /** Types the hold's transcript, or reports why nothing was typed. */
    private fun finishWith(text: String) {
        graceJob?.cancel()
        restoreTones()

        if (text.isBlank()) {
            _ui.update { it.copy(phase = PttPhase.IDLE, partial = "", level = 0f, error = null) }
            return
        }
        if (!transport.isReady) {
            _ui.update {
                it.copy(
                    phase = PttPhase.IDLE,
                    partial = "",
                    level = 0f,
                    lastTyped = text,
                    error = "Not connected to a computer; nothing was typed",
                )
            }
            return
        }

        _ui.update { it.copy(phase = PttPhase.TYPING, partial = text, level = 0f, deliveredChars = 0, error = null) }
        lifecycleScope.launch {
            val result = transport.sendText(if (appendTrailingSpace) "$text " else text) { delivered ->
                _ui.update { it.copy(deliveredChars = delivered) }
            }
            val typedAt = SystemClock.elapsedRealtime()
            Log.i(TAG_TIMING, "release-to-typed ${typedAt - releasedAt} ms, ${result.delivered.length} chars")
            // Shown under the transcript so the numbers are readable without logcat.
            val timing = buildString {
                append("Ready ").append(if (readyAt > 0) "${readyAt - pressedAt} ms" else "n/a")
                append(" · tail ").append(if (stoppedAt > 0) "${stoppedAt - releasedAt} ms" else "n/a")
                append(" · final +").append(if (stoppedAt > 0 && finalAt > 0) "${finalAt - stoppedAt} ms" else "n/a")
                append(" · typed ${typedAt - releasedAt} ms after release")
            }
            val notes = buildList {
                if (result.aborted) add("Connection dropped while typing")
                if (result.dropped.isNotEmpty()) {
                    add("Dropped, no US-layout key: ${result.dropped}")
                } else if (result.adjusted) {
                    add("Some characters were adjusted to fit the US layout")
                }
            }
            _ui.update {
                it.copy(
                    phase = PttPhase.SENT,
                    partial = "",
                    deliveredChars = 0,
                    lastTyped = result.delivered.trimEnd(),
                    error = notes.takeIf { n -> n.isNotEmpty() }?.joinToString("\n"),
                    timing = timing,
                )
            }
            doubleTick()
            sentReset = launch {
                delay(SENT_HOLD_MS)
                _ui.update { if (it.phase == PttPhase.SENT) it.copy(phase = PttPhase.IDLE) else it }
            }
        }
    }

    /** Maps the recogniser's dB scale (about -2 to 10) onto 0..1 for the waveform. */
    private fun normalizeLevel(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

    // ---- Haptics ----------------------------------------------------------------------------

    private fun tick() {
        vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun doubleTick() {
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 12, 70, 12), -1))
    }

    // ---- Recogniser tones -------------------------------------------------------------------

    /**
     * Mutes the media stream, where the platform recogniser plays its start/stop earcons, for the
     * duration of a hold. Best effort: it silences the click and chime, at the cost of muting any
     * media playback until [restoreTones]. Balanced mute/unmute calls keep the stream's state.
     */
    private fun muteTones() {
        if (tonesMuted) return
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }.onFailure { Log.w(TAG, "could not mute recogniser tones", it) }
        tonesMuted = true
    }

    private fun restoreTones() {
        if (!tonesMuted) return
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }.onFailure { Log.w(TAG, "could not restore audio", it) }
        tonesMuted = false
    }

    // ---- Hosts ------------------------------------------------------------------------------

    fun hosts(): List<HostDevice> = keyboard.pairedHosts()

    fun connect(address: String) {
        if (!keyboard.connect(address)) {
            _ui.update { it.copy(error = "Could not start connecting to that device") }
        }
    }

    fun disconnect() = keyboard.disconnect()

    // ---- Notification -----------------------------------------------------------------------

    private fun goForeground() {
        val notification = buildNotification()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!started) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (val s = _ui.value.hid) {
            is HidKeyboard.State.Connected -> "Connected to ${s.hostName}"
            is HidKeyboard.State.Connecting -> "Connecting to ${s.hostName}"
            HidKeyboard.State.Registered -> "Keyboard ready, not connected"
            is HidKeyboard.State.Unavailable -> s.reason
            HidKeyboard.State.Starting, HidKeyboard.State.Unregistered -> "Starting"
        }
        return Notification.Builder(this, MurmrApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "MurmrService"

        /** Dictation timing lines: `adb logcat -s MurmrTiming`. */
        const val TAG_TIMING = "MurmrTiming"
        const val NOTIFICATION_ID = 1

        /** After release, always keep capturing at least this long: the tail of the last word. */
        const val GRACE_MIN_MS = 600L

        /** Beyond the minimum, stop once this long passes with no new partial. */
        const val GRACE_QUIET_MS = 350L

        /** Hard cap on the tail, so a noisy room cannot hold the mic open forever. */
        const val GRACE_MAX_MS = 1_500L

        /** How often the grace window re-checks for quiet. */
        const val GRACE_STEP_MS = 75L

        /** How long the "Typed" confirmation stays before the phase returns to IDLE. */
        const val SENT_HOLD_MS = 1_800L
    }
}
