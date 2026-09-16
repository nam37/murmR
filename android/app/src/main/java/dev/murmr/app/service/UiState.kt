package dev.murmr.app.service

import dev.murmr.app.hid.HidKeyboard

/** Push-to-talk state machine phases. */
enum class PttPhase {
    /** Nothing in progress. */
    IDLE,

    /** Button held; microphone open; partial transcripts arriving. */
    LISTENING,

    /** Button released; waiting for the recogniser's final result. */
    FINISHING,

    /** Final result is being sent to the computer as keystrokes. */
    TYPING,
}

/** Everything the screen needs to render. Published by [MurmrService]. */
data class UiState(
    val hid: HidKeyboard.State = HidKeyboard.State.Starting,
    val phase: PttPhase = PttPhase.IDLE,
    /** Live transcript while listening, or the text being typed. */
    val partial: String = "",
    /** Most recent completed utterance. */
    val lastTyped: String = "",
    val error: String? = null,
)
