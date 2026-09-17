package dev.murmr.app.ui.instrument

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.murmr.app.hid.HidKeyboard
import dev.murmr.app.hid.HostDevice

/**
 * Connection management (pair, connect, disconnect) plus the artwork comparison switches the
 * production asset pack asks for. Opens from the status pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(
    hid: HidKeyboard.State,
    lastHost: String?,
    hosts: List<HostDevice>,
    chassisArt: ChassisArt,
    glassArt: GlassArt,
    onChassisArt: (ChassisArt) -> Unit,
    onGlassArt: (GlassArt) -> Unit,
    onRefreshHosts: () -> Unit,
    onConnect: (address: String) -> Unit,
    onDisconnect: () -> Unit,
    onMakeDiscoverable: () -> Unit,
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
            Text("Computer", style = MaterialTheme.typography.titleLarge)
            Text(connectionLine(hid, lastHost), style = MaterialTheme.typography.bodyMedium)

            when (hid) {
                is HidKeyboard.State.Connected -> {
                    Button(onClick = { onDisconnect(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect")
                    }
                }
                HidKeyboard.State.Registered, is HidKeyboard.State.Connecting -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onMakeDiscoverable) { Text("Make discoverable") }
                        TextButton(onClick = onRefreshHosts) { Text("Refresh") }
                    }
                    if (hosts.isEmpty()) {
                        Text(
                            "No paired devices yet. Make the phone discoverable, then add it from " +
                                "the computer's Bluetooth settings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Paired devices", style = MaterialTheme.typography.labelLarge)
                        hosts.forEach { host ->
                            OutlinedButton(
                                onClick = { onConnect(host.address); onDismiss() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(host.name)
                            }
                        }
                    }
                }
                else -> Unit
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Artwork comparison", style = MaterialTheme.typography.titleMedium)
            Text(
                "Side-by-side check of the production asset candidates against the approved " +
                    "mockup artwork. Original is what shipped in the mockup.",
                style = MaterialTheme.typography.bodySmall,
            )
            ArtChoice(
                label = "Chassis",
                options = listOf(ChassisArt.ORIGINAL to "Original", ChassisArt.TILED_METAL to "Tiled metal"),
                selected = chassisArt,
                onSelect = onChassisArt,
            )
            ArtChoice(
                label = "Glass",
                options = listOf(GlassArt.ORIGINAL to "Original", GlassArt.NINE_SLICE to "Nine-slice"),
                selected = glassArt,
                onSelect = onGlassArt,
            )
        }
    }
}

private fun connectionLine(hid: HidKeyboard.State, lastHost: String?): String = when (hid) {
    is HidKeyboard.State.Connected -> "Connected to ${hid.hostName}."
    is HidKeyboard.State.Connecting -> "Connecting to ${hid.hostName}..."
    HidKeyboard.State.Registered ->
        if (lastHost != null) "Keyboard ready. Not connected to $lastHost." else "Keyboard ready. Not connected."
    is HidKeyboard.State.Unavailable -> "Unavailable: ${hid.reason}"
    HidKeyboard.State.Starting -> "Starting Bluetooth keyboard..."
    HidKeyboard.State.Unregistered -> "Keyboard not registered."
}

@Composable
private fun <T> ArtChoice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
        options.forEach { (value, name) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(name) })
        }
    }
}
