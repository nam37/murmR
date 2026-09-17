package dev.murmr.app.service

import dev.murmr.app.hid.HidKeyboard

/** Push-to-talk state machine phases. */
enum class PttPhase {
    /** Nothing in progress. */
    IDLE,

    /** Button held; microphone open; partial transcripts arriving. */
    LISTENING,

    /** Button released; grace window and the recogniser's final result in flight. */
    FINISHING,

    /** Final result is being sent to the computer as keystrokes. */
    TYPING,

    /** Delivery just completed; a brief confirmation before returning to IDLE. */
    SENT,
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
    /** Characters of [partial] that have reached the computer so far, while TYPING. */
    val deliveredChars: Int = 0,
    val error: String? = null,
)
