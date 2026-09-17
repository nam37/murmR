package dev.murmr.app.macros

import dev.murmr.app.transport.KeyChord

/** Which operating system a paired computer runs. Decides how shortcut presets resolve. */
enum class HostOs(val label: String) {
    WINDOWS("Windows"),
    MACOS("macOS"),
    LINUX("Linux"),
}

/** HID modifier bits (Usage Page 0x07 modifier byte). GUI is Command on a Mac, Win elsewhere. */
object Mod {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val GUI = 0x08
}

/** HID usage codes and display names for the keys the editor offers. */
object Keys {
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    fun letter(c: Char): Int = 0x04 + (c.lowercaseChar() - 'a')
    fun digit(d: Char): Int = if (d == '0') 0x27 else 0x1E + (d - '1')
    fun function(n: Int): Int = 0x3A + (n - 1)

    /** A key the editor can offer by name: its usage, a short caption, and a keycap symbol. */
    data class Named(val usage: Int, val caption: String, val symbol: String)

    val COMMON: List<Named> = listOf(
        Named(ENTER, "ENTER", "↵"),
        Named(TAB, "TAB", "⇥"),
        Named(ESC, "ESC", "esc"),
        Named(BACKSPACE, "BACK", "⌫"),
        Named(DELETE, "DEL", "⌦"),
        Named(SPACE, "SPACE", "␣"),
        Named(LEFT, "LEFT", "←"),
        Named(RIGHT, "RIGHT", "→"),
        Named(UP, "UP", "↑"),
        Named(DOWN, "DOWN", "↓"),
        Named(HOME, "HOME", "⇱"),
        Named(END, "END", "⇲"),
        Named(PAGE_UP, "PG UP", "⇞"),
        Named(PAGE_DOWN, "PG DN", "⇟"),
    )
    val LETTERS: List<Named> = ('A'..'Z').map { Named(letter(it), it.toString(), it.toString()) }
    val DIGITS: List<Named> = ('0'..'9').map { Named(digit(it), it.toString(), it.toString()) }
    val FUNCTION: List<Named> = (1..12).map { Named(function(it), "F$it", "F$it") }

    private val all: Map<Int, Named> by lazy { (COMMON + LETTERS + DIGITS + FUNCTION).associateBy { it.usage } }

    fun named(usage: Int): Named? = all[usage]
    fun symbolFor(usage: Int): String = all[usage]?.symbol ?: "?"
    fun captionFor(usage: Int): String = all[usage]?.caption ?: "KEY"
}

/**
 * Shortcuts that mean the same thing on every computer but are pressed differently. Stored by
 * name and resolved to a chord at send time from the computer's [HostOs], so changing the
 * profile fixes every preset on that computer at once.
 */
enum class Preset(val caption: String, private val letter: Char?, private val primary: Boolean, private val shift: Boolean = false, private val usage: Int? = null) {
    PASTE("PASTE", 'v', primary = true),
    COPY("COPY", 'c', primary = true),
    CUT("CUT", 'x', primary = true),
    UNDO("UNDO", 'z', primary = true),
    SELECT_ALL("ALL", 'a', primary = true),
    SAVE("SAVE", 's', primary = true),
    FIND("FIND", 'f', primary = true),
    NEW_LINE("NEW LINE", null, primary = false, shift = true, usage = Keys.ENTER);

    fun resolve(os: HostOs): KeyChord {
        val u = usage ?: Keys.letter(letter!!)
        var mods = 0
        if (primary) mods = mods or (if (os == HostOs.MACOS) Mod.GUI else Mod.CTRL)
        if (shift) mods = mods or Mod.SHIFT
        return KeyChord(u, mods)
    }

    /** Keycap symbol for the resolved chord, or "OS?" until the computer's OS is known. */
    fun symbol(os: HostOs?): String = if (os == null) "OS?" else chordSymbol(resolve(os), os)
}

/** What a keycap does. */
sealed interface MacroAction {
    data class Key(val chord: KeyChord) : MacroAction
    data class Shortcut(val preset: Preset) : MacroAction
    data class Text(val text: String, val enterAfter: Boolean) : MacroAction
    data object None : MacroAction
}

/** One keycap: what it shows and what it sends. */
data class Macro(val caption: String, val action: MacroAction) {
    val isAssigned: Boolean get() = action != MacroAction.None

    fun symbol(os: HostOs?): String = when (val a = action) {
        is MacroAction.Key -> chordSymbol(a.chord, os)
        is MacroAction.Shortcut -> a.preset.symbol(os)
        is MacroAction.Text -> "Aa"
        MacroAction.None -> ""
    }
}

/** Compact keycap notation for a chord, in the modifier vocabulary of the computer's OS. */
fun chordSymbol(chord: KeyChord, os: HostOs?): String {
    val mac = os == HostOs.MACOS
    val m = chord.modifiers
    return buildString {
        if (m and Mod.CTRL != 0) append(if (mac) "⌃" else "^")
        if (m and Mod.ALT != 0) append(if (mac) "⌥" else "⎇")
        if (m and Mod.SHIFT != 0) append("⇧")
        if (m and Mod.GUI != 0) append(if (mac) "⌘" else "⊞")
        append(Keys.symbolFor(chord.usage))
    }
}

/** The mockup's sample assignments; every new computer starts with these. */
val DEFAULT_MACROS: List<Macro> = listOf(
    Macro("ENTER", MacroAction.Key(KeyChord(Keys.ENTER, 0))),
    Macro("TAB", MacroAction.Key(KeyChord(Keys.TAB, 0))),
    Macro("ESC", MacroAction.Key(KeyChord(Keys.ESC, 0))),
    Macro("PASTE", MacroAction.Shortcut(Preset.PASTE)),
)

/** Everything stored per paired computer. */
data class HostConfig(
    val os: HostOs? = null,
    val macros: List<Macro> = DEFAULT_MACROS,
)
