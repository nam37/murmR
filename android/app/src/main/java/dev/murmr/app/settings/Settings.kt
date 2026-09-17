package dev.murmr.app.settings

import android.content.Context
import android.content.SharedPreferences
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

/** App-wide settings. Per-computer settings (OS profile, keys) live elsewhere. */
data class Settings(
    val offlinePolicy: OfflinePolicy = OfflinePolicy.REQUIRED,
    /** Capture tail after release, in milliseconds. */
    val tailMs: Int = 600,
    val autoClear: AutoClear = AutoClear.S30,
    val keepAwake: KeepAwake = KeepAwake.WHILE_CONNECTED,
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
        }
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            offlinePolicy = enumOr(prefs.getString(KEY_POLICY, null), d.offlinePolicy),
            tailMs = prefs.getInt(KEY_TAIL, d.tailMs),
            autoClear = enumOr(prefs.getString(KEY_AUTO_CLEAR, null), d.autoClear),
            keepAwake = enumOr(prefs.getString(KEY_KEEP_AWAKE, null), d.keepAwake),
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
    }
}
