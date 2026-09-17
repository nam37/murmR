package dev.murmr.app.ui.instrument

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.murmr.app.BuildConfig
import dev.murmr.app.settings.AutoClear
import dev.murmr.app.settings.KeepAwake
import dev.murmr.app.settings.Settings
import dev.murmr.app.stt.OfflinePolicy

/**
 * App-wide settings. Per-computer settings (OS profile, key assignments) belong in the
 * connection sheet, not here. Light sheet over the dark instrument, like the connection sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    chassisArt: ChassisArt,
    glassArt: GlassArt,
    onChassisArt: (ChassisArt) -> Unit,
    onGlassArt: (GlassArt) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            SectionTitle("Dictation")
            ChoiceRow(
                label = "Capture tail",
                options = Settings.TAIL_OPTIONS.map { it to "$it ms" },
                selected = settings.tailMs,
            ) { v -> onSettings { it.copy(tailMs = v) } }
            Help("How long the microphone keeps capturing after you release, so the last word is not clipped.")
            ChoiceRow(
                label = "Recognition",
                options = listOf(OfflinePolicy.REQUIRED to "On-device only", OfflinePolicy.PREFERRED to "Allow online"),
                selected = settings.offlinePolicy,
            ) { v -> onSettings { it.copy(offlinePolicy = v) } }
            Help(
                "On-device only is the privacy promise: audio never leaves the phone. Allow online lets " +
                    "Android fall back to its network recogniser when no offline language pack is installed.",
            )
            ChoiceRow(
                label = "Capture",
                options = listOf(false to "Standard", true to "Continuous (experimental)"),
                selected = settings.continuousCapture,
            ) { v -> onSettings { it.copy(continuousCapture = v) } }
            Help(
                if (Build.VERSION.SDK_INT >= 33) {
                    "Continuous: the app records audio itself and streams it to the recogniser, so " +
                        "recognition cannot stop at a pause and plays no tones. Experimental: if " +
                        "dictation fails with it on, switch back to Standard."
                } else {
                    "Continuous capture needs Android 13 or newer; this phone uses Standard."
                },
            )
            Help("Volume Down also works as push-to-talk while the app is open.")

            SectionTitle("Transcript")
            ChoiceRow(
                label = "Auto-clear",
                options = AutoClear.entries.map { it to it.label },
                selected = settings.autoClear,
            ) { v -> onSettings { it.copy(autoClear = v) } }
            Help(
                "Clears the phone's display after a dictation; the text on the computer is untouched. " +
                    "Erase last is available until then.",
            )

            SectionTitle("Display")
            ChoiceRow(
                label = "Keep screen awake",
                options = KeepAwake.entries.map { it to it.label },
                selected = settings.keepAwake,
            ) { v -> onSettings { it.copy(keepAwake = v) } }

            SectionTitle("Artwork comparison")
            Help("Temporary: the production asset candidates against the approved mockup art. Original is what the mockup ships.")
            ChoiceRow(
                label = "Chassis",
                options = listOf(ChassisArt.ORIGINAL to "Original", ChassisArt.TILED_METAL to "Tiled metal"),
                selected = chassisArt,
                onSelect = onChassisArt,
            )
            ChoiceRow(
                label = "Glass",
                options = listOf(GlassArt.ORIGINAL to "Original", GlassArt.CANDIDATE to "New panel"),
                selected = glassArt,
                onSelect = onGlassArt,
            )

            SectionTitle("About")
            Text("murmr ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Help(
                "Speech is recognised on the phone and typed into the computer as a Bluetooth keyboard. " +
                    "With on-device only recognition, audio never leaves the phone.",
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** A labelled row of single-choice chips; scrolls sideways if the options do not fit. */
@Composable
internal fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, name) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(name) })
            }
        }
    }
}
