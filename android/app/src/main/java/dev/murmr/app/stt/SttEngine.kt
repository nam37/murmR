package dev.murmr.app.stt

import kotlinx.coroutines.flow.SharedFlow

/** Events a speech-to-text engine reports during one push-to-talk session. */
sealed interface SttEvent {
    /** Microphone is open; the user can speak. */
    data object Ready : SttEvent

    /** Best-guess transcript so far. May be revised by later partials or the final result. */
    data class Partial(val text: String) : SttEvent

    /** Final transcript for the session. Empty when nothing was recognised. */
    data class Final(val text: String) : SttEvent

    /** The session ended without a result. */
    data class Error(val message: String, val code: Int = -1) : SttEvent
}

/** How strictly recognition must stay on the phone. */
enum class OfflinePolicy {
    /**
     * Use only a recogniser that guarantees on-device processing; fail with a clear message
     * otherwise. This is the default: the app's privacy promise is that audio never leaves
     * the phone, and a mere "prefer offline" hint cannot enforce that.
     */
    REQUIRED,

    /** Prefer offline, but fall back to the platform default engine, which may use the network. */
    PREFERRED,
}

/**
 * A speech-to-text engine driven by push-to-talk.
 *
 * Contract: [start] opens the microphone and begins recognising; [stop] closes the microphone
 * and lets the engine finish, after which exactly one [SttEvent.Final] or [SttEvent.Error] is
 * emitted. [cancel] abandons the session without a result. Implementations decide their own
 * threading requirements; the platform engine needs the main thread.
 */
interface SttEngine {
    val events: SharedFlow<SttEvent>

    fun start()

    fun stop()

    fun cancel()

    /** Releases native resources. The engine must not be used afterwards. */
    fun destroy()
}
