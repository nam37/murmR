package dev.murmr.app.stt

import kotlinx.coroutines.flow.SharedFlow

/** Events a speech-to-text engine reports during one push-to-talk hold. */
sealed interface SttEvent {
    /** Microphone is open; the user can speak. */
    data object Ready : SttEvent

    /**
     * The running transcript so far, including any in-progress last phrase. Revised freely by
     * later events. This is the full text for the hold, not just the latest phrase, so the UI
     * can show it directly.
     */
    data class Partial(val text: String) : SttEvent

    /** Microphone level while listening, in the recogniser's dB scale (roughly -2 to 10). */
    data class Level(val rmsDb: Float) : SttEvent

    /** The complete transcript for the hold. Empty when nothing was recognised. */
    data class Final(val text: String) : SttEvent

    /** The hold ended without a result. */
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
 * Contract: [start] opens the microphone and begins one continuous dictation; [stop] closes the
 * microphone and lets the engine finish, after which exactly one [SttEvent.Final] or
 * [SttEvent.Error] ends the hold. Between start and that terminal event the engine emits any
 * number of [SttEvent.Partial]s carrying the whole running transcript. [cancel] abandons the
 * hold without a result.
 *
 * The engine owns continuity: one hold is one dictation even if the underlying recogniser wants
 * to stop at every pause. Callers do not restart it or stitch phrases together. Implementations
 * decide their own threading; the platform engine needs the main thread.
 */
interface SttEngine {
    val events: SharedFlow<SttEvent>

    fun start()

    fun stop()

    fun cancel()

    /** Releases native resources. The engine must not be used afterwards. */
    fun destroy()
}
