package dev.murmr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.speech.SpeechRecognizer
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
 *     pttDown() -> LISTENING -> pttUp() -> FINISHING -> TYPING -> IDLE
 *
 * Reliability rules (see docs/architecture.md, "Reliability rules"):
 *  - One hold may span several recogniser sessions. If the recogniser ends on its own while the
 *    button is still held (silence, no match), the text so far is kept and a new session starts.
 *    Everything is typed once, on release.
 *  - After release, the recogniser gets [FINISH_TIMEOUT_MS] to deliver; then it is cancelled and
 *    whatever was captured is typed.
 *  - A session that fails within [MIN_SESSION_MS] is not restarted, so a broken microphone or
 *    speech service cannot cause a tight loop.
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

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Append a space after each utterance so consecutive dictations do not run together. */
    var appendTrailingSpace = true

    // State of the current hold. Reset in pttDown().
    private val segments = mutableListOf<String>()
    private var currentPartial = ""
    private var recognizing = false
    private var sessionStartedAt = 0L
    private var releasedAt = 0L
    private var holdError: String? = null
    private var finishTimeout: Job? = null

    private var started = false

    override fun onCreate() {
        super.onCreate()
        keyboard = HidKeyboard(this)
        stt = AndroidSttEngine(this, offlinePolicy = OfflinePolicy.REQUIRED)
        transport = TextTyper(keyboard)

        lifecycleScope.launch {
            keyboard.state.collect { hidState ->
                _ui.update { it.copy(hid = hidState) }
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
        stt.destroy()
        keyboard.stop()
        super.onDestroy()
    }

    // ---- Push to talk -----------------------------------------------------------------------

    fun pttDown() {
        if (_ui.value.phase != PttPhase.IDLE) return
        segments.clear()
        currentPartial = ""
        holdError = null
        _ui.update { it.copy(phase = PttPhase.LISTENING, partial = "", error = null) }
        startSession()
    }

    fun pttUp() {
        if (_ui.value.phase != PttPhase.LISTENING) return
        releasedAt = SystemClock.elapsedRealtime()
        _ui.update { it.copy(phase = PttPhase.FINISHING) }
        if (!recognizing) {
            // The recogniser already ended (or never started); nothing to wait for.
            finish()
            return
        }
        stt.stop()
        finishTimeout = lifecycleScope.launch {
            delay(FINISH_TIMEOUT_MS)
            Log.w(TAG, "recogniser did not finish within ${FINISH_TIMEOUT_MS}ms; cancelling")
            stt.cancel()
            recognizing = false
            holdError = "Recogniser did not finish in time; typed what was captured"
            finish()
        }
    }

    private fun startSession() {
        recognizing = true
        sessionStartedAt = SystemClock.elapsedRealtime()
        stt.start()
    }

    private fun onSttEvent(event: SttEvent) {
        when (event) {
            SttEvent.Ready -> Unit

            is SttEvent.Partial -> {
                currentPartial = event.text
                publishPartial()
            }

            is SttEvent.Final -> {
                recognizing = false
                currentPartial = ""
                if (event.text.isNotBlank()) segments += event.text.trim()
                publishPartial()
                when (_ui.value.phase) {
                    // Recogniser ended on its own while the button is still held: keep going.
                    PttPhase.LISTENING -> startSession()
                    PttPhase.FINISHING -> finish()
                    else -> Unit
                }
            }

            is SttEvent.Error -> {
                recognizing = false
                currentPartial = ""
                Log.w(TAG, "STT error ${event.code}: ${event.message}")
                when (_ui.value.phase) {
                    PttPhase.LISTENING -> {
                        val sessionMs = SystemClock.elapsedRealtime() - sessionStartedAt
                        if (isPauseError(event.code) && sessionMs >= MIN_SESSION_MS) {
                            // The user paused long enough for the recogniser to give up. Resume.
                            publishPartial()
                            startSession()
                        } else {
                            // Real failure: stop capturing, keep what we have, wait for release.
                            holdError = event.message
                            _ui.update { it.copy(error = event.message) }
                        }
                    }
                    PttPhase.FINISHING -> {
                        if (segments.isEmpty()) holdError = event.message
                        finish()
                    }
                    // IDLE or TYPING: no session is open, so this is a late or duplicate error.
                    // Changing phase here would interrupt typing or a new hold.
                    else -> Log.i(TAG, "ignoring STT error outside a hold")
                }
            }
        }
    }

    /** Ends the hold: types everything captured, or reports why nothing was typed. */
    private fun finish() {
        finishTimeout?.cancel()
        finishTimeout = null
        val text = segments.joinToString(" ")
        segments.clear()
        currentPartial = ""

        if (text.isBlank()) {
            _ui.update { it.copy(phase = PttPhase.IDLE, partial = "", error = holdError ?: "Nothing recognised") }
            return
        }
        if (!transport.isReady) {
            _ui.update {
                it.copy(
                    phase = PttPhase.IDLE,
                    partial = "",
                    lastTyped = text,
                    error = "Not connected to a computer; nothing was typed",
                )
            }
            return
        }

        _ui.update { it.copy(phase = PttPhase.TYPING, partial = text, error = null) }
        lifecycleScope.launch {
            val result = transport.sendText(if (appendTrailingSpace) "$text " else text)
            Log.i(TAG, "release-to-typed ${SystemClock.elapsedRealtime() - releasedAt} ms, ${result.delivered.length} chars")
            val notes = buildList {
                holdError?.let { add(it) }
                if (result.aborted) add("Connection dropped while typing")
                if (result.dropped.isNotEmpty()) {
                    add("Dropped, no US-layout key: ${result.dropped}")
                } else if (result.adjusted) {
                    add("Some characters were adjusted to fit the US layout")
                }
            }
            _ui.update {
                it.copy(
                    phase = PttPhase.IDLE,
                    partial = "",
                    lastTyped = result.delivered.trimEnd(),
                    error = notes.takeIf { n -> n.isNotEmpty() }?.joinToString("\n"),
                )
            }
        }
    }

    private fun publishPartial() {
        val shown = (segments + currentPartial).filter { it.isNotBlank() }.joinToString(" ")
        _ui.update { it.copy(partial = shown) }
    }

    /** Errors that mean "the user stopped talking", not "something is broken". */
    private fun isPauseError(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_NO_MATCH

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
        const val NOTIFICATION_ID = 1

        /** How long after release to wait for the recogniser's final result. */
        const val FINISH_TIMEOUT_MS = 5_000L

        /** Sessions that fail faster than this are not restarted (tight-loop guard). */
        const val MIN_SESSION_MS = 700L
    }
}
