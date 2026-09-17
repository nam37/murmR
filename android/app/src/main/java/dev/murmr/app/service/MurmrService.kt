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
import dev.murmr.app.diag.EventLog
import dev.murmr.app.feedback.SoundCues
import dev.murmr.app.hid.HidKeyboard
import dev.murmr.app.hid.HostDevice
import dev.murmr.app.hid.TextTyper
import dev.murmr.app.macros.MacroAction
import dev.murmr.app.macros.MacroStore
import dev.murmr.app.settings.Settings
import dev.murmr.app.settings.SettingsStore
import dev.murmr.app.stt.AndroidSttEngine
import dev.murmr.app.stt.AudioSourceSttEngine
import dev.murmr.app.stt.SttEngine
import dev.murmr.app.stt.SttEvent
import dev.murmr.app.transport.KeyChord
import dev.murmr.app.transport.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the moving parts and the push-to-talk state machine:
 *
 *     pttDown() -> LISTENING --pttUp()--> FINISHING -> TYPING -> SENT -> IDLE
 *                                                                  \-> ERASING -> IDLE
 *
 * Continuity within a hold (spanning pauses, punctuation, etc.) belongs to the [SttEngine]; this
 * service only drives the gesture and types the result. Its own reliability rules (see
 * docs/architecture.md, "Reliability rules"):
 *
 *  - **Capture tail.** On release the recogniser keeps capturing for at least the configured
 *    tail (a pause between partial results is not evidence of silence), then while partials are
 *    still arriving, up to [GRACE_MAX_MS], and only then is the engine told to stop.
 *  - **Nothing is typed mid-hold.** The engine emits one Final on stop; that is what gets typed.
 *  - **Recogniser tones are muted** for the duration of a hold, so the platform's start/stop
 *    earcons (and any restart click) are silenced.
 *  - **Erase last is conservative.** It sends exactly as many backspaces as characters were
 *    delivered, and only while the same computer is still connected and nothing else has been
 *    sent since. It cannot know what the computer did to the text in between.
 *  - **Haptics mark the moments the eyes miss**: a tick when the microphone actually opens and a
 *    double tick when the text has reached the computer.
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
    private var sttEvents: Job? = null
    private var pendingContinuous: Boolean? = null
    private lateinit var typer: TextTyper
    private val transport: Transport get() = typer
    private val settingsStore: SettingsStore by lazy { (application as MurmrApp).settings }
    private val macroStore: MacroStore by lazy { (application as MurmrApp).macros }
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
    private var autoClearJob: Job? = null
    private var tonesMuted = false
    private var tailMs = Settings().tailMs.toLong()
    private var soundCuesOn = Settings().soundCues
    private var hapticsOn = Settings().haptics
    private val soundCues by lazy { SoundCues() }

    /** The last text delivered to the computer, kept only while erasing it is still valid. */
    private var eraseable: String? = null

    private var started = false

    override fun onCreate() {
        super.onCreate()
        keyboard = HidKeyboard(this)
        installEngine(settingsStore.settings.value.continuousCapture)
        typer = TextTyper(keyboard, keyDelayMs = settingsStore.settings.value.typingSpeed.delayMs)

        lifecycleScope.launch {
            keyboard.state.collect { hidState ->
                val connected = hidState as? HidKeyboard.State.Connected
                val previousHost = _ui.value.lastHost
                _ui.update {
                    it.copy(
                        hid = hidState,
                        lastHost = connected?.hostName ?: it.lastHost,
                        hostAddress = connected?.address,
                        lastHostAddress = connected?.address ?: it.lastHostAddress,
                    )
                }
                // Erase last is only valid while the same computer is still connected.
                if (connected == null || (previousHost != null && previousHost != connected.hostName)) {
                    invalidateErase()
                }
                updateNotification()
            }
        }
        lifecycleScope.launch {
            settingsStore.settings.collect { s ->
                stt.offlinePolicy = s.offlinePolicy
                tailMs = s.tailMs.toLong()
                typer.keyDelayMs = s.typingSpeed.delayMs
                soundCuesOn = s.soundCues
                hapticsOn = s.haptics
                // Swapping engines mid-hold would lose the hold; defer to the next press.
                if (_ui.value.phase == PttPhase.IDLE || _ui.value.phase == PttPhase.SENT) {
                    installEngine(s.continuousCapture)
                } else {
                    pendingContinuous = s.continuousCapture
                }
            }
        }
    }

    /** Selects the speech engine for the continuous-capture setting; no-op if already right. */
    private fun installEngine(continuous: Boolean) {
        pendingContinuous = null
        val wantAudioSource = continuous && Build.VERSION.SDK_INT >= 33
        if (::stt.isInitialized && (stt is AudioSourceSttEngine) == wantAudioSource) return
        val policy = settingsStore.settings.value.offlinePolicy
        if (::stt.isInitialized) {
            sttEvents?.cancel()
            stt.destroy()
        }
        stt = if (wantAudioSource) {
            EventLog.log(TAG, "engine: audio-source (continuous capture)")
            AudioSourceSttEngine(this, offlinePolicy = policy)
        } else {
            EventLog.log(TAG, "engine: platform")
            AndroidSttEngine(this, offlinePolicy = policy)
        }
        val engine = stt
        sttEvents = lifecycleScope.launch { engine.events.collect(::onSttEvent) }
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
        autoClearJob?.cancel()
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
        autoClearJob?.cancel()
        pendingContinuous?.let { installEngine(it) }
        invalidateErase()   // a new dictation supersedes the last one
        EventLog.log(TAG, "press")
        val now = SystemClock.elapsedRealtime()
        pressedAt = now
        readyAt = 0L
        stoppedAt = 0L
        finalAt = 0L
        lastPartialAt = now
        muteTones()
        _ui.update {
            it.copy(
                phase = PttPhase.LISTENING, partial = "", level = 0f, deliveredChars = 0,
                error = null, timing = null, notice = null,
            )
        }
        stt.start()
    }

    fun pttUp() {
        if (_ui.value.phase != PttPhase.LISTENING) return
        releasedAt = SystemClock.elapsedRealtime()
        EventLog.log(TAG, "release after ${releasedAt - pressedAt} ms")
        _ui.update { it.copy(phase = PttPhase.FINISHING) }
        // Capture tail: the recogniser keeps capturing for at least tailMs after release,
        // because a pause between partial results is not evidence of acoustic silence. Beyond
        // that it keeps going while partials are still arriving, up to GRACE_MAX_MS.
        graceJob = lifecycleScope.launch {
            delay(tailMs)
            if (stt !is AudioSourceSttEngine) {
                // Platform engine: it is still capturing during the tail, so partials still
                // arriving mean speech is still being heard. The audio-source engine captures
                // on our clock; its late partials are the recogniser catching up on buffered
                // audio, not new speech, so the fixed tail is exact and extending it only adds
                // latency (measured: 800-1300 ms tails instead of 600).
                val cap = releasedAt + GRACE_MAX_MS
                while (SystemClock.elapsedRealtime() < cap) {
                    if (SystemClock.elapsedRealtime() - lastPartialAt >= GRACE_QUIET_MS) break
                    delay(GRACE_STEP_MS)
                }
            }
            stoppedAt = SystemClock.elapsedRealtime()
            EventLog.log(TAG_TIMING, "release-to-stop ${stoppedAt - releasedAt} ms")
            stt.stop()
        }
    }

    private fun onSttEvent(event: SttEvent) {
        when (event) {
            SttEvent.Ready -> {
                if (readyAt == 0L) {
                    readyAt = SystemClock.elapsedRealtime()
                    EventLog.log(TAG_TIMING, "press-to-ready ${readyAt - pressedAt} ms")
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
                    _ui.update { it.copy(level = event.level) }
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
                EventLog.log(TAG, "STT error ${event.code}: ${event.message}")
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
        lifecycleScope.launch {
            if (!transport.isReady) {
                // The link can flap for a moment; give it a chance to come back before giving up.
                EventLog.log(TAG, "final ready but link down; waiting up to $RECONNECT_WAIT_MS ms")
                _ui.update { it.copy(phase = PttPhase.FINISHING, partial = text, level = 0f) }
                val back = withTimeoutOrNull(RECONNECT_WAIT_MS) {
                    keyboard.state.first { it is HidKeyboard.State.Connected }
                } != null
                if (!back) {
                    EventLog.log(TAG, "link did not come back; nothing typed")
                    _ui.update {
                        it.copy(
                            phase = PttPhase.IDLE,
                            partial = "",
                            lastTyped = text,
                            error = "Not connected to a computer; nothing was typed",
                        )
                    }
                    return@launch
                }
                EventLog.log(TAG, "link back; typing")
            }
            _ui.update { it.copy(phase = PttPhase.TYPING, partial = text, level = 0f, deliveredChars = 0, error = null) }
            val result = transport.sendText(if (appendTrailingSpace) "$text " else text) { delivered ->
                _ui.update { it.copy(deliveredChars = delivered) }
            }
            val typedAt = SystemClock.elapsedRealtime()
            EventLog.log(TAG_TIMING, "release-to-typed ${typedAt - releasedAt} ms, ${result.delivered.length} chars")
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
            eraseable = result.delivered.takeIf { it.isNotEmpty() }
            val canErase = eraseable != null && transport.isReady
            _ui.update {
                it.copy(
                    phase = PttPhase.SENT,
                    partial = "",
                    deliveredChars = 0,
                    lastTyped = result.delivered.trimEnd(),
                    error = notes.takeIf { n -> n.isNotEmpty() }?.joinToString("\n"),
                    timing = timing,
                    canErase = canErase,
                    eraseCount = if (canErase) result.delivered.length else 0,
                )
            }
            doubleTick()
            sentReset = launch {
                delay(SENT_HOLD_MS)
                _ui.update { if (it.phase == PttPhase.SENT) it.copy(phase = PttPhase.IDLE) else it }
            }
            startAutoClear()
        }
    }

    // ---- Erase last and auto-clear ---------------------------------------------------------

    /**
     * Sends one backspace per delivered character of the last dictation. Valid only while the
     * cursor is still right after that text; the app cannot verify that, so the UI says so.
     */
    fun eraseLast() {
        val text = eraseable ?: return
        val phase = _ui.value.phase
        if (phase != PttPhase.IDLE && phase != PttPhase.SENT) return
        if (!transport.isReady) return
        sentReset?.cancel()
        autoClearJob?.cancel()
        eraseable = null
        _ui.update {
            it.copy(
                phase = PttPhase.ERASING,
                deliveredChars = 0,
                eraseCount = text.length,
                canErase = false,
                error = null,
                timing = null,
            )
        }
        lifecycleScope.launch {
            val erased = transport.eraseChars(text.length) { n -> _ui.update { it.copy(deliveredChars = n) } }
            Log.i(TAG, "erased $erased of ${text.length} chars")
            _ui.update {
                it.copy(
                    phase = PttPhase.IDLE,
                    partial = "",
                    lastTyped = "",
                    deliveredChars = 0,
                    eraseCount = 0,
                    error = if (erased < text.length) "Erase interrupted after $erased of ${text.length} characters" else null,
                )
            }
            actionClick()
        }
    }

    // ---- Keycaps (macros) ------------------------------------------------------------------

    private var macroPressedAt = 0L

    /**
     * Sends keycap [index]'s assignment for the connected computer. Chords go as one press;
     * text goes through the typer like a dictation. Any send invalidates Erase last, since the
     * cursor is no longer right after the last dictation.
     */
    fun pressMacro(index: Int) {
        val s = _ui.value
        if (s.phase != PttPhase.IDLE && s.phase != PttPhase.SENT) return
        if (!transport.isReady) return
        val address = s.hostAddress ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - macroPressedAt < MACRO_DEBOUNCE_MS) return   // one Enter per double tap, not two
        macroPressedAt = now

        val cfg = macroStore.configFor(address)
        val macro = cfg.macros.getOrNull(index) ?: return
        when (val action = macro.action) {
            MacroAction.None -> notice("No key assigned. Long-press to set one.")
            is MacroAction.Key -> sendChord(macro.caption, action.chord)
            is MacroAction.Shortcut -> {
                val os = cfg.os
                if (os == null) notice("Set this computer's OS (tap the computer name) to use ${macro.caption}.")
                else sendChord(macro.caption, action.preset.resolve(os))
            }
            is MacroAction.Text -> sendMacroText(macro.caption, action.text, action.enterAfter)
        }
    }

    private fun sendChord(caption: String, chord: KeyChord) {
        sentReset?.cancel()
        invalidateErase()
        lifecycleScope.launch {
            val ok = transport.sendKey(chord)
            actionClick()
            notice(if (ok) "$caption sent" else "$caption failed: connection dropped")
        }
    }

    private fun sendMacroText(caption: String, text: String, enterAfter: Boolean) {
        sentReset?.cancel()
        autoClearJob?.cancel()
        invalidateErase()
        _ui.update { it.copy(phase = PttPhase.TYPING, partial = text, deliveredChars = 0, error = null, notice = null) }
        lifecycleScope.launch {
            val result = transport.sendText(text) { n -> _ui.update { it.copy(deliveredChars = n) } }
            var ok = !result.aborted
            if (ok && enterAfter) ok = transport.sendKey(KeyChord(dev.murmr.app.macros.Keys.ENTER, 0))
            actionClick()
            _ui.update {
                it.copy(
                    phase = PttPhase.IDLE,
                    partial = "",
                    deliveredChars = 0,
                    lastTyped = result.delivered.trimEnd(),
                    notice = if (ok) "$caption sent" else "$caption interrupted: connection dropped",
                )
            }
        }
    }

    private fun notice(text: String) {
        _ui.update { it.copy(notice = text) }
    }

    /** The user touched the transcript: restart the auto-clear countdown if one is running. */
    fun touchTranscript() {
        if (autoClearJob?.isActive == true) startAutoClear()
    }

    private fun startAutoClear() {
        autoClearJob?.cancel()
        val seconds = settingsStore.settings.value.autoClear.seconds ?: return
        autoClearJob = lifecycleScope.launch {
            delay(seconds * 1_000L)
            val s = _ui.value
            // Never clear mid-dictation or over an unresolved error; those clear on the next hold.
            if ((s.phase == PttPhase.IDLE || s.phase == PttPhase.SENT) && s.error == null) {
                eraseable = null
                _ui.update { it.copy(timing = null, notice = null, canErase = false, eraseCount = 0) }
                // The reverse of the typing reveal: eat the text from the end, quickly, in a
                // fixed number of steps so long and short dictations clear in the same time.
                var text = _ui.value.lastTyped
                val step = maxOf(1, text.length / CLEAR_STEPS)
                while (text.isNotEmpty()) {
                    text = text.dropLast(step)
                    _ui.update { it.copy(lastTyped = text) }
                    delay(CLEAR_TICK_MS)
                }
                _ui.update { it.copy(partial = "") }
            }
        }
    }

    private fun invalidateErase() {
        eraseable = null
        _ui.update { if (it.canErase) it.copy(canErase = false, eraseCount = 0) else it }
    }

    // ---- Feedback cues ----------------------------------------------------------------------
    // The eyes are on the computer, so the moments that matter (mic open, text landed) get
    // cues the user can feel or hear. Both channels are opt-in settings.

    /** The microphone is open: speak. */
    private fun tick() {
        haptic(HAPTIC_TICK, 10)
        if (soundCuesOn) soundCues.ready()
    }

    /** The text has reached the computer. */
    private fun doubleTick() {
        haptic(HAPTIC_DOUBLE_CLICK, 12, longArrayOf(0, 12, 70, 12))
        if (soundCuesOn) soundCues.delivered()
    }

    /** A keycap or erase went out: a click, no sound. */
    private fun actionClick() = haptic(HAPTIC_CLICK, 12)

    private fun haptic(predefined: Int, fallbackMs: Long, fallbackPattern: LongArray? = null) {
        if (!hapticsOn) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(predefined))
        } else if (fallbackPattern != null) {
            v.vibrate(VibrationEffect.createWaveform(fallbackPattern, -1))
        } else {
            v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---- Recogniser tones -------------------------------------------------------------------

    /**
     * Mutes the streams the platform recogniser may play its start/stop earcons on (media, and
     * system sonification) for the duration of a hold. Best effort: it silences the click and
     * chime at the cost of muting media playback and touch sounds until [restoreTones].
     * Balanced mute/unmute calls keep each stream's state. The audio-source engine needs none
     * of this, since the recogniser never opens a microphone session of its own.
     */
    private fun muteTones() {
        if (tonesMuted) return
        for (stream in TONE_STREAMS) {
            runCatching { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }
                .onFailure { Log.w(TAG, "could not mute stream $stream", it) }
        }
        tonesMuted = true
    }

    private fun restoreTones() {
        if (!tonesMuted) return
        for (stream in TONE_STREAMS) {
            runCatching { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
                .onFailure { Log.w(TAG, "could not restore stream $stream", it) }
        }
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

        /** Beyond the configured tail, stop once this long passes with no new partial. */
        const val GRACE_QUIET_MS = 350L

        /** Hard cap on the tail, so a noisy room cannot hold the mic open forever. */
        const val GRACE_MAX_MS = 1_500L

        /** How often the tail re-checks for quiet. */
        const val GRACE_STEP_MS = 75L

        /** How long the "Typed" confirmation stays before the phase returns to IDLE. */
        const val SENT_HOLD_MS = 1_800L

        /** Keycap presses closer than this are one press: a double tap on Enter sends one Enter. */
        const val MACRO_DEBOUNCE_MS = 250L

        /** Auto-clear animation: the text is eaten from the end in this many steps... */
        const val CLEAR_STEPS = 40

        /** ...at this interval, so any length clears in about half a second. */
        const val CLEAR_TICK_MS = 12L

        /**
         * Streams muted during a hold. Only media: muting the system stream was tried and
         * coincided with a report of Bluetooth link flapping, so it stays out until the event
         * log clears or convicts it.
         */
        val TONE_STREAMS = intArrayOf(AudioManager.STREAM_MUSIC)

        /** How long a finished dictation waits for a dropped link to come back before giving up. */
        const val RECONNECT_WAIT_MS = 3_000L

        // VibrationEffect.EFFECT_* were added in API 29; literal values keep minSdk 28 lint quiet.
        const val HAPTIC_CLICK = 0          // EFFECT_CLICK
        const val HAPTIC_DOUBLE_CLICK = 1   // EFFECT_DOUBLE_CLICK
        const val HAPTIC_TICK = 2           // EFFECT_TICK
    }
}
