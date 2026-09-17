package dev.murmr.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import dev.murmr.app.stt.OfflinePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** When the screen is held awake. */
enum class KeepAwake(val label: String) {
    /** While a computer is connected and the app is visible; always during a hold or send. */
    WHILE_CONNECTED("While connected"),

    /** Only during a hold or send. */
    WHILE_DICTATING("Only while dictating"),

    NEVER("Never"),
}

/** How long a finished dictation stays on the phone's transcript panel. */
enum class AutoClear(val seconds: Int?, val label: String) {
    S15(15, "15 s"),
    S30(30, "30 s"),
    S60(60, "60 s"),
    NEVER(null, "Never"),
}

/** Pause between key reports while typing; sets characters per second. */
enum class TypingSpeed(val delayMs: Long, val label: String) {
    /** ~45 chars/s. For hosts that drop keys at higher rates. */
    CAREFUL(8, "Careful"),

    /** ~80 chars/s. Fine on current Windows and macOS Bluetooth stacks. */
    NORMAL(4, "Normal"),

    /** ~120 chars/s. Try it; fall back if characters go missing. */
    FAST(2, "Fast"),
}

/** App-wide settings. Per-computer settings (OS profile, keys) live elsewhere. */
data class Settings(
    val offlinePolicy: OfflinePolicy = OfflinePolicy.REQUIRED,
    /** Capture tail after release, in milliseconds. */
    val tailMs: Int = 600,
    val autoClear: AutoClear = AutoClear.S15,
    val keepAwake: KeepAwake = KeepAwake.WHILE_CONNECTED,
    /**
     * The app records audio itself and streams it to the recogniser, so the session cannot end
     * at a pause, plays no tones, and the mic is open within ~50 ms of the press. Android 13+;
     * proven on a Pixel 11 Pro, so it is the default there. Engines that refuse a supplied
     * stream report an error, and Standard is one switch away.
     */
    val continuousCapture: Boolean = Build.VERSION.SDK_INT >= 33,
    val typingSpeed: TypingSpeed = TypingSpeed.NORMAL,
) {
    companion object {
        val TAIL_OPTIONS = listOf(400, 600, 800, 1000)
    }
}

/**
 * Persists [Settings] in SharedPreferences and publishes them as a [StateFlow]. One instance
 * per process, owned by the Application, read by the service and the UI.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = _settings.updateAndGet(transform)
        prefs.edit {
            putString(KEY_POLICY, next.offlinePolicy.name)
            putInt(KEY_TAIL, next.tailMs)
            putString(KEY_AUTO_CLEAR, next.autoClear.name)
            putString(KEY_KEEP_AWAKE, next.keepAwake.name)
            putBoolean(KEY_CONTINUOUS, next.continuousCapture)
            putString(KEY_TYPING_SPEED, next.typingSpeed.name)
        }
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            offlinePolicy = enumOr(prefs.getString(KEY_POLICY, null), d.offlinePolicy),
            tailMs = prefs.getInt(KEY_TAIL, d.tailMs),
            autoClear = enumOr(prefs.getString(KEY_AUTO_CLEAR, null), d.autoClear),
            keepAwake = enumOr(prefs.getString(KEY_KEEP_AWAKE, null), d.keepAwake),
            continuousCapture = prefs.getBoolean(KEY_CONTINUOUS, d.continuousCapture),
            typingSpeed = enumOr(prefs.getString(KEY_TYPING_SPEED, null), d.typingSpeed),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> runCatching { enumValueOf<E>(n) }.getOrNull() } ?: default

    private companion object {
        const val PREFS = "murmr.settings"
        const val KEY_POLICY = "offline_policy"
        const val KEY_TAIL = "tail_ms"
        const val KEY_AUTO_CLEAR = "auto_clear"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_CONTINUOUS = "continuous_capture"
        const val KEY_TYPING_SPEED = "typing_speed"
    }
}
