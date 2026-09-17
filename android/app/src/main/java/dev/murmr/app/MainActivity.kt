package dev.murmr.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.murmr.app.hid.HostDevice
import dev.murmr.app.service.MurmrService
import dev.murmr.app.service.UiState
import dev.murmr.app.ui.MainScreen
import dev.murmr.app.ui.theme.MurmrTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Thin host for the Compose UI. Requests permissions, starts and binds [MurmrService], and
 * turns Volume Down into a hardware push-to-talk key while the screen is in front.
 */
class MainActivity : ComponentActivity() {

    private val service = MutableStateFlow<MurmrService?>(null)
    private val permissionsDenied = MutableStateFlow(false)
    private var bound = false
    private var permissionsRequested = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service.value = (binder as MurmrService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service.value = null
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val ok = essentialPermissions().all(::isGranted)
            permissionsDenied.value = !ok
            if (ok) startAndBindService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The instrument screen is a dark chassis; ask for light system-bar icons over it.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MurmrTheme {
                val svc by service.collectAsStateWithLifecycle()
                val denied by permissionsDenied.collectAsStateWithLifecycle()
                val fallback = remember { MutableStateFlow(UiState()) }
                val state by (svc?.ui ?: fallback).collectAsStateWithLifecycle()
                var hosts by remember { mutableStateOf(emptyList<HostDevice>()) }
                LaunchedEffect(svc) { hosts = svc?.hosts().orEmpty() }

                MainScreen(
                    state = state,
                    hosts = hosts,
                    permissionsDenied = denied,
                    onRefreshHosts = { hosts = svc?.hosts().orEmpty() },
                    onConnect = { address -> svc?.connect(address) },
                    onDisconnect = { svc?.disconnect() },
                    onMakeDiscoverable = ::requestDiscoverable,
                    onPttDown = { svc?.pttDown() },
                    onPttUp = { svc?.pttUp() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (essentialPermissions().all(::isGranted)) {
            startAndBindService()
        } else if (!permissionsRequested) {
            permissionsRequested = true
            permissionLauncher.launch(allPermissions().filterNot(::isGranted).toTypedArray())
        } else {
            permissionsDenied.value = true
        }
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
            service.value = null
        }
        super.onStop()
    }

    // Volume Down doubles as a hardware push-to-talk key while this screen is in front.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) service.value?.pttDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            service.value?.pttUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startAndBindService() {
        if (bound) return
        val intent = Intent(this, MurmrService::class.java)
        startForegroundService(intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun requestDiscoverable() {
        startActivity(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
        )
    }

    /** Permissions without which the app cannot work at all. */
    private fun essentialPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }

    /** Essentials plus nice-to-haves that we ask for once but do not insist on. */
    private fun allPermissions(): List<String> = buildList {
        addAll(essentialPermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
