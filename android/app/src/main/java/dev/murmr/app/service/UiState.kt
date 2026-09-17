package dev.murmr.app.service

import dev.murmr.app.hid.HidKeyboard

/** Push-to-talk state machine phases. */
enum class PttPhase {
    /** Nothing in progress. */
    IDLE,

    /** Button held; microphone open; partial transcripts arriving. */
    LISTENING,

    /** Button released; capture tail and the recogniser's final result in flight. */
    FINISHING,

    /** Final result is being sent to the computer as keystrokes. */
    TYPING,

    /** Delivery just completed; a brief confirmation before returning to IDLE. */
    SENT,

    /** The last delivered text is being erased with backspaces. */
    ERASING,
}

/** Everything the screen needs to render. Published by [MurmrService]. */
data class UiState(
    val hid: HidKeyboard.State = HidKeyboard.State.Starting,
    /** Name of the most recently connected computer, kept for display while disconnected. */
    val lastHost: String? = null,
    val phase: PttPhase = PttPhase.IDLE,
    /** Live transcript while listening, or the text being typed. */
    val partial: String = "",
    /** Most recent completed utterance, exactly as delivered. */
    val lastTyped: String = "",
    /** Microphone level 0..1 while listening; 0 otherwise. */
    val level: Float = 0f,
    /** Characters delivered so far while TYPING, or erased so far while ERASING. */
    val deliveredChars: Int = 0,
    val error: String? = null,
    /** One-line timing summary of the last dictation (press-to-ready, tail, final, typed). */
    val timing: String? = null,
    /** True while the last delivered text can still be erased with backspaces. */
    val canErase: Boolean = false,
    /** Characters an erase would send; while ERASING, the total being erased. */
    val eraseCount: Int = 0,
)
