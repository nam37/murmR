package dev.murmr.app.transport

/** What a transport can carry to the computer. Drives which features the UI offers. */
data class Capabilities(
    val text: Boolean,
    val images: Boolean = false,
    val clipboardPaste: Boolean = false,
)

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
}
