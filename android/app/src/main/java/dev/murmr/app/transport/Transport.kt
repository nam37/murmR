package dev.murmr.app.transport

/** What a transport can carry to the computer. Drives which features the UI offers. */
data class Capabilities(
    val text: Boolean,
    /** Individual key chords (shortcuts, Enter, arrows), beyond typed text. */
    val keys: Boolean = false,
    val images: Boolean = false,
    val clipboardPaste: Boolean = false,
)

/**
 * One key press with modifiers, in HID terms: a usage code from Usage Page 0x07 and the
 * modifier bitmask (Ctrl 0x01, Shift 0x02, Alt 0x04, GUI 0x08). HID codes are the common
 * language here; a non-HID transport would translate them.
 */
data class KeyChord(val usage: Int, val modifiers: Int = 0)

/** Outcome of delivering text. [delivered] is exactly what reached the computer. */
data class DeliveryResult(
    val delivered: String,
    /** Characters this transport could not represent; they were dropped. */
    val dropped: String = "",
    /** True when characters were altered to fit the transport (for example accents folded). */
    val adjusted: Boolean = false,
    /** True when the connection failed part-way; [delivered] holds what got through. */
    val aborted: Boolean = false,
)

/**
 * A way to get dictated text into the computer.
 *
 * Bluetooth HID typing ([dev.murmr.app.hid.TextTyper]) is the first implementation and claims
 * only text. A host companion over Wi-Fi or RFCOMM is planned and will add images, unicode and
 * clipboard paste (docs/architecture.md, "Companion transport"). The service and UI depend on
 * this interface, never on a concrete transport.
 */
interface Transport {
    val capabilities: Capabilities

    /** True when text sent now would reach the computer. */
    val isReady: Boolean

    /**
     * Delivers [text]. [onProgress] is called with the number of characters actually delivered
     * so far, driven by real send progress, never by elapsed time, so the UI's typing cursor
     * reflects what has reached the computer.
     */
    suspend fun sendText(text: String, onProgress: (deliveredChars: Int) -> Unit = {}): DeliveryResult

    /**
     * Erases [count] characters before the computer's cursor (Backspace, [count] times). Returns
     * how many were sent; fewer than [count] means the connection dropped part-way. Correct only
     * while the cursor still sits right after text this transport delivered; the caller owns
     * that judgement.
     */
    suspend fun eraseChars(count: Int, onProgress: (erased: Int) -> Unit = {}): Int

    /** Presses and releases one chord (modifiers held with the key). False if it could not be sent. */
    suspend fun sendKey(chord: KeyChord): Boolean
}
