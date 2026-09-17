package dev.murmr.app.macros

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.murmr.app.transport.KeyChord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-computer configuration (OS profile, keycap assignments), keyed by the computer's Bluetooth
 * address and persisted in SharedPreferences as JSON. Owned by the Application.
 */
class MacroStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _hosts = MutableStateFlow(loadAll())

    /** All known computers by address. Unknown addresses map to [HostConfig] defaults. */
    val hosts: StateFlow<Map<String, HostConfig>> = _hosts.asStateFlow()

    fun configFor(address: String?): HostConfig = address?.let { _hosts.value[it] } ?: HostConfig()

    fun setOs(address: String, os: HostOs) = change(address) { it.copy(os = os) }

    fun setMacro(address: String, index: Int, macro: Macro) = change(address) { cfg ->
        val macros = cfg.macros.toMutableList()
        while (macros.size <= index) macros.add(Macro("", MacroAction.None))
        macros[index] = macro
        cfg.copy(macros = macros)
    }

    private fun change(address: String, transform: (HostConfig) -> HostConfig) {
        val next = transform(configFor(address))
        _hosts.update { it + (address to next) }
        prefs.edit { putString(KEY_PREFIX + address, encode(next).toString()) }
    }

    private fun loadAll(): Map<String, HostConfig> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.value is String }
            .mapNotNull { (key, value) ->
                runCatching { key.removePrefix(KEY_PREFIX) to decode(JSONObject(value as String)) }.getOrNull()
            }
            .toMap()

    // ---- JSON --------------------------------------------------------------------------------

    private fun encode(cfg: HostConfig): JSONObject = JSONObject().apply {
        cfg.os?.let { put("os", it.name) }
        put("macros", JSONArray().apply { cfg.macros.forEach { put(encode(it)) } })
    }

    private fun encode(m: Macro): JSONObject = JSONObject().apply {
        put("caption", m.caption)
        when (val a = m.action) {
            is MacroAction.Key -> { put("type", "key"); put("usage", a.chord.usage); put("modifiers", a.chord.modifiers) }
            is MacroAction.Shortcut -> { put("type", "shortcut"); put("preset", a.preset.name) }
            is MacroAction.Text -> { put("type", "text"); put("text", a.text); put("enter", a.enterAfter) }
            MacroAction.None -> put("type", "none")
        }
    }

    private fun decode(o: JSONObject): HostConfig {
        val os = o.optString("os", "").takeIf { it.isNotEmpty() }?.let { n -> HostOs.entries.firstOrNull { it.name == n } }
        val arr = o.optJSONArray("macros")
        val macros = if (arr == null) DEFAULT_MACROS else (0 until arr.length()).map { decodeMacro(arr.getJSONObject(it)) }
        return HostConfig(os = os, macros = macros)
    }

    private fun decodeMacro(o: JSONObject): Macro {
        val caption = o.optString("caption", "")
        val action: MacroAction = when (o.optString("type", "none")) {
            "key" -> MacroAction.Key(KeyChord(o.getInt("usage"), o.optInt("modifiers", 0)))
            "shortcut" -> Preset.entries.firstOrNull { it.name == o.optString("preset") }
                ?.let { MacroAction.Shortcut(it) } ?: MacroAction.None
            "text" -> MacroAction.Text(o.optString("text", ""), o.optBoolean("enter", false))
            else -> MacroAction.None
        }
        return Macro(caption, action)
    }

    private companion object {
        const val PREFS = "murmr.macros"
        const val KEY_PREFIX = "host."
    }
}
