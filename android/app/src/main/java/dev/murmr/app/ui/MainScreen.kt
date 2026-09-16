package dev.murmr.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.murmr.app.R
import dev.murmr.app.hid.HidKeyboard
import dev.murmr.app.hid.HostDevice
import dev.murmr.app.service.PttPhase
import dev.murmr.app.service.UiState

@Composable
fun MainScreen(
    state: UiState,
    hosts: List<HostDevice>,
    permissionsDenied: Boolean,
    onRefreshHosts: () -> Unit,
    onConnect: (address: String) -> Unit,
    onDisconnect: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.murmr_mark),
                        contentDescription = null,
                        modifier = Modifier.height(36.dp),
                    )
                    Text("murmr", style = MaterialTheme.typography.headlineMedium)
                }
                if (permissionsDenied) {
                    Text(
                        "Microphone and Nearby devices permissions are required. " +
                            "Grant them in system settings and reopen the app.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ConnectionCard(
                    hid = state.hid,
                    hosts = hosts,
                    onRefreshHosts = onRefreshHosts,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onMakeDiscoverable = onMakeDiscoverable,
                )
                TranscriptCard(state)
            }
            PushToTalkButton(
                phase = state.phase,
                onDown = onPttDown,
                onUp = onPttUp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                "Hold the button (or Volume Down) while you speak. Release to type.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    hid: HidKeyboard.State,
    hosts: List<HostDevice>,
    onRefreshHosts: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onMakeDiscoverable: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Computer", style = MaterialTheme.typography.titleMedium)
            Text(statusText(hid))
            when (hid) {
                is HidKeyboard.State.Connected -> {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                HidKeyboard.State.Registered, is HidKeyboard.State.Connecting -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onMakeDiscoverable) { Text("Make discoverable") }
                        TextButton(onClick = onRefreshHosts) { Text("Refresh") }
                    }
                    if (hosts.isEmpty()) {
                        Text(
                            "No paired devices yet. Make the phone discoverable, then add it " +
                                "from the computer's Bluetooth settings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Paired devices", style = MaterialTheme.typography.labelLarge)
                        hosts.forEach { host ->
                            OutlinedButton(
                                onClick = { onConnect(host.address) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(host.name)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

private fun statusText(hid: HidKeyboard.State): String = when (hid) {
    HidKeyboard.State.Starting -> "Starting Bluetooth keyboard..."
    is HidKeyboard.State.Unavailable -> "Unavailable: ${hid.reason}"
    HidKeyboard.State.Unregistered -> "Keyboard not registered"
    HidKeyboard.State.Registered -> "Keyboard ready. Not connected."
    is HidKeyboard.State.Connecting -> "Connecting to ${hid.hostName}..."
    is HidKeyboard.State.Connected -> "Connected to ${hid.hostName}"
}

@Composable
private fun TranscriptCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phaseText(state.phase), style = MaterialTheme.typography.titleMedium)
            val shown = state.partial.ifBlank { state.lastTyped }
            Text(
                if (shown.isBlank()) "Nothing dictated yet." else shown,
                style = MaterialTheme.typography.bodyLarge,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun phaseText(phase: PttPhase): String = when (phase) {
    PttPhase.IDLE -> "Transcript"
    PttPhase.LISTENING -> "Listening..."
    PttPhase.FINISHING -> "Finishing..."
    PttPhase.TYPING -> "Typing..."
}

@Composable
private fun PushToTalkButton(
    phase: PttPhase,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // pointerInput(Unit) captures its lambdas once; read the latest callbacks through state.
    val currentDown by rememberUpdatedState(onDown)
    val currentUp by rememberUpdatedState(onUp)

    val colors = MaterialTheme.colorScheme
    val background = when (phase) {
        PttPhase.IDLE -> colors.primary
        PttPhase.LISTENING -> colors.error
        PttPhase.FINISHING, PttPhase.TYPING -> colors.tertiary
    }
    val label = when (phase) {
        PttPhase.IDLE -> "Hold to talk"
        PttPhase.LISTENING -> "Listening"
        PttPhase.FINISHING -> "Finishing"
        PttPhase.TYPING -> "Typing"
    }

    Box(
        modifier = modifier
            .size(168.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        currentDown()
                        tryAwaitRelease()
                        currentUp()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = contentColorFor(background), style = MaterialTheme.typography.titleLarge)
    }
}
