package dev.murmr.app.hid

import dev.murmr.app.transport.Capabilities
import dev.murmr.app.transport.DeliveryResult
import dev.murmr.app.transport.Transport
import kotlinx.coroutines.delay
import java.text.Normalizer

/**
 * The Bluetooth HID [Transport]: turns text into press/release key reports on a [HidKeyboard].
 *
 * Each character is sent as two reports (key down, all up) with a short pause after each so
 * hosts do not drop or reorder keys. [keyDelayMs] of 8 gives roughly 60 characters per second.
 *
 * Fidelity rules, so the phone can show exactly what the computer received:
 *  - typographic quotes and dashes are straightened (same meaning, has a US key);
 *  - accented letters are folded to their base letter (é -> e) and reported as "adjusted";
 *  - anything still without a US-layout key is dropped and reported;
 *  - host Caps Lock is compensated for, so letter case survives.
 */
class TextTyper(
    private val keyboard: HidKeyboard,
    private val keyDelayMs: Long = 8,
) : Transport {

    override val capabilities = Capabilities(text = true)

    override val isReady: Boolean get() = keyboard.isConnected

    /** Types [text]. Stops early (aborted = true) if the host connection drops mid-way. */
    override suspend fun sendText(text: String): DeliveryResult {
        val straightened = straighten(text)
        val folded = foldAccents(straightened)
        val adjusted = folded != straightened

        val delivered = StringBuilder()
        val dropped = StringBuilder()
        for (ch in folded) {
            val stroke = KeyMap.lookup(ch)
            if (stroke == null) {
                dropped.append(ch)
                continue
            }
            if (!press(compensateCapsLock(stroke))) {
                return DeliveryResult(delivered.toString(), dropped.toString(), adjusted, aborted = true)
            }
            delivered.append(ch)
        }
        return DeliveryResult(delivered.toString(), dropped.toString(), adjusted)
    }

    /** Presses and releases one key. Returns false if a report could not be sent. */
    suspend fun press(stroke: KeyStroke): Boolean {
        if (!keyboard.sendKeyReport(stroke.modifiers, intArrayOf(stroke.usage))) return false
        delay(keyDelayMs)
        if (!keyboard.sendKeyReport(0, IntArray(0))) return false
        delay(keyDelayMs)
        return true
    }

    suspend fun backspace(count: Int): Boolean {
        repeat(count) { if (!press(KeyMap.BACKSPACE)) return false }
        return true
    }

    /** With host Caps Lock on, Shift means lowercase for letters; invert it so case is preserved. */
    private fun compensateCapsLock(stroke: KeyStroke): KeyStroke =
        if (keyboard.capsLockOn && stroke.isLetter) {
            stroke.copy(modifiers = stroke.modifiers xor KeyMap.MOD_LEFT_SHIFT)
        } else {
            stroke
        }

    companion object {
        private val COMBINING_MARKS = Regex("\\p{M}+")

        /** Replaces typographic punctuation with the ASCII equivalents that have a US key. */
        fun straighten(text: String): String = text
            .replace('‘', '\'').replace('’', '\'')   // curly single quotes
            .replace('“', '"').replace('”', '"')     // curly double quotes
            .replace('–', '-').replace('—', '-')     // en/em dash
            .replace("…", "...")                          // ellipsis
            .replace(' ', ' ')                            // non-breaking space

        /** Strips diacritics: "café" -> "cafe", "naïve" -> "naive". */
        fun foldAccents(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    }
}
