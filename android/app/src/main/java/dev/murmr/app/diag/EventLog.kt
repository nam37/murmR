package dev.murmr.app.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app event log: the last few hundred notable events (Bluetooth link state, dictation
 * phases, engine sessions, timings), readable and copyable from Settings. The phone is
 * usually sideloaded without a debugger attached, so this is how a field report gets its
 * evidence. Everything also goes to logcat under its own tag.
 */
object EventLog {

    private const val MAX_LINES = 300

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        Log.i(tag, message)
        val stamp = synchronized(clock) { clock.format(Date()) }
        _lines.update { (it + "$stamp $tag: $message").takeLast(MAX_LINES) }
    }

    fun clear() = _lines.update { emptyList() }

    fun text(): String = lines.value.joinToString("\n")
}
