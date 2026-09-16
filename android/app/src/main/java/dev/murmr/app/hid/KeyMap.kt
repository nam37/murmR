package dev.murmr.app.hid

/** One key press: a HID usage code (Usage Page 0x07) plus a modifier bitmask. */
data class KeyStroke(val usage: Int, val modifiers: Int = 0) {
    /** True for the 26 letter keys, the only ones whose output Caps Lock changes. */
    val isLetter: Boolean get() = usage in 0x04..0x1D
}

/**
 * Character to key-stroke mapping for a host set to the US keyboard layout.
 *
 * HID keyboards send physical key positions, not characters; the host's layout decides what
 * character a position produces. This table is correct for US/ANSI. Other layouts need their own
 * table (see docs/architecture.md, "Keyboard layouts").
 */
object KeyMap {
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08

    val ENTER = KeyStroke(0x28)
    val BACKSPACE = KeyStroke(0x2A)
    val TAB = KeyStroke(0x2B)
    val SPACE = KeyStroke(0x2C)

    private val us: Map<Char, KeyStroke> = buildMap {
        ('a'..'z').forEachIndexed { i, c ->
            put(c, KeyStroke(0x04 + i))
            put(c.uppercaseChar(), KeyStroke(0x04 + i, MOD_LEFT_SHIFT))
        }
        // Digit row: 1..9 then 0 occupy usages 0x1E..0x27, shifted symbols share the keys.
        "1234567890".forEachIndexed { i, c -> put(c, KeyStroke(0x1E + i)) }
        "!@#\$%^&*()".forEachIndexed { i, c -> put(c, KeyStroke(0x1E + i, MOD_LEFT_SHIFT)) }

        put('\n', ENTER)
        put('\t', TAB)
        put(' ', SPACE)

        punct('-', '_', 0x2D)
        punct('=', '+', 0x2E)
        punct('[', '{', 0x2F)
        punct(']', '}', 0x30)
        punct('\\', '|', 0x31)
        punct(';', ':', 0x33)
        punct('\'', '"', 0x34)
        punct('`', '~', 0x35)
        punct(',', '<', 0x36)
        punct('.', '>', 0x37)
        punct('/', '?', 0x38)
    }

    private fun MutableMap<Char, KeyStroke>.punct(plain: Char, shifted: Char, usage: Int) {
        put(plain, KeyStroke(usage))
        put(shifted, KeyStroke(usage, MOD_LEFT_SHIFT))
    }

    /** Returns the key stroke for [c], or null when the US layout has no key for it. */
    fun lookup(c: Char): KeyStroke? = us[c]
}
