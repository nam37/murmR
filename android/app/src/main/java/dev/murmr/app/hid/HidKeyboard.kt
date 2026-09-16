package dev.murmr.app.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registers this phone as a Bluetooth HID keyboard and sends key reports to the connected host.
 *
 * The host (PC, Mac, ...) sees an ordinary Bluetooth keyboard, so it needs no software of its own.
 * The keyboard follows the Bluetooth adapter: if Bluetooth is off at [start] or turned off later,
 * it waits and re-registers when the adapter comes back on.
 *
 * Callers must hold BLUETOOTH_CONNECT (Android 12+) before calling [start]. MainActivity checks
 * that before the service starts, which is why the MissingPermission lint is suppressed here.
 * Keep BluetoothDevice objects inside this class; the rest of the app works with plain strings.
 */
@SuppressLint("MissingPermission")
class HidKeyboard(private val context: Context) {

    sealed interface State {
        /** Waiting for the system to hand us the HID-device profile proxy. */
        data object Starting : State

        /** Bluetooth is off, or this phone does not support the HID device role. */
        data class Unavailable(val reason: String) : State

        /** Profile proxy available, but our keyboard app is not (or no longer) registered. */
        data object Unregistered : State

        /** Keyboard registered; waiting for a host to connect. */
        data object Registered : State

        data class Connecting(val hostName: String) : State

        data class Connected(val hostName: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var receiverRegistered = false

    val isConnected: Boolean get() = hid != null && host != null

    /**
     * Host Caps Lock state, learned from the keyboard LED output report the host sends us.
     * Assumed off until the host says otherwise.
     */
    @Volatile
    var capsLockOn: Boolean = false
        private set

    /** Starts following the Bluetooth adapter and registers the keyboard when it is on. */
    fun start() {
        if (adapter == null) {
            _state.value = State.Unavailable("This phone has no Bluetooth")
            return
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        requestProfile()
    }

    /** Disconnects the host, unregisters the keyboard and stops following the adapter. */
    fun stop() {
        if (receiverRegistered) {
            context.unregisterReceiver(adapterStateReceiver)
            receiverRegistered = false
        }
        releaseProfile()
        _state.value = State.Unregistered
    }

    /** Devices paired with this phone; any of them may be the computer to connect to. */
    fun pairedHosts(): List<HostDevice> =
        adapter?.bondedDevices.orEmpty().map { HostDevice(it.displayName(), it.address) }

    /** Connects to an already-paired host by MAC address. The result arrives via [state]. */
    fun connect(address: String): Boolean {
        val device = adapter?.bondedDevices.orEmpty().firstOrNull { it.address == address }
            ?: return false
        return connect(device)
    }

    private fun connect(device: BluetoothDevice): Boolean {
        val h = hid ?: return false
        _state.value = State.Connecting(device.displayName())
        return h.connect(device)
    }

    fun disconnect() {
        val h = hid ?: return
        host?.let { h.disconnect(it) }
    }

    /**
     * Sends one keyboard input report: [modifiers] bitmask plus up to six key usages.
     * An empty [keys] array with zero modifiers is the "all keys released" report.
     */
    fun sendKeyReport(modifiers: Int, keys: IntArray): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        for (i in 0 until minOf(6, keys.size)) report[2 + i] = keys[i].toByte()
        return h.sendReport(d, REPORT_ID_KEYBOARD, report)
    }

    // ---- Adapter lifecycle ------------------------------------------------------------------

    private fun requestProfile() {
        val adapter = adapter ?: return
        if (!adapter.isEnabled) {
            // adapterStateReceiver calls us again when Bluetooth turns on.
            _state.value = State.Unavailable("Bluetooth is off")
            return
        }
        if (hid != null) return
        _state.value = State.Starting
        val requested = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        if (!requested) {
            _state.value = State.Unavailable("This phone does not expose the Bluetooth HID device profile")
        }
    }

    private fun releaseProfile() {
        val h = hid ?: return
        host?.let { runCatching { h.disconnect(it) }.onFailure { e -> Log.w(TAG, "disconnect failed", e) } }
        runCatching { h.unregisterApp() }.onFailure { e -> Log.w(TAG, "unregisterApp failed", e) }
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        hid = null
        host = null
        capsLockOn = false
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth on; registering keyboard")
                    requestProfile()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth off; releasing keyboard")
                    releaseProfile()
                    _state.value = State.Unavailable("Bluetooth is off")
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            host = null
            capsLockOn = false
            _state.value = State.Unregistered
        }
    }

    private fun registerApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "murmr",
            "murmr voice keyboard",
            "murmr",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_REPORT_DESCRIPTOR,
        )
        // Best-effort QoS values commonly used for HID keyboards.
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
        )
        val ok = hid?.registerApp(sdp, null, qos, context.mainExecutor, callback) ?: false
        if (!ok) {
            _state.value = State.Unavailable(
                "Could not register as a HID keyboard (another app may already own the HID profile)"
            )
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "app registered=$registered plugged=${pluggedDevice?.address}")
            if (!registered) {
                host = null
                _state.value = State.Unregistered
                return
            }
            _state.value = State.Registered
            // A host that previously "virtually plugged" this keyboard: reconnect to it.
            if (pluggedDevice != null) connect(pluggedDevice)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "connection ${device.address} state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    capsLockOn = false
                    _state.value = State.Connected(device.displayName())
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _state.value = State.Connecting(device.displayName())
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    val current = host
                    if (current == null || current.address == device.address) {
                        host = null
                        capsLockOn = false
                        _state.value = if (hid != null) State.Registered else State.Unregistered
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // Host polled us; reply with an all-released keyboard report.
            hid?.replyReport(device, type, id, ByteArray(8))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // Keyboard LEDs (Num/Caps/Scroll Lock) arrive as an output report on the control channel.
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) applyLeds(data)
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            // Some hosts send the LED report on the interrupt channel instead.
            if (reportId.toInt() == REPORT_ID_KEYBOARD) applyLeds(data)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            Log.i(TAG, "virtual cable unplug from ${device.address}")
            host = null
            capsLockOn = false
            _state.value = State.Registered
        }
    }

    private fun applyLeds(data: ByteArray) {
        val leds = data.firstOrNull()?.toInt() ?: return
        val caps = leds and LED_CAPS_LOCK != 0
        if (caps != capsLockOn) Log.i(TAG, "host caps lock=$caps")
        capsLockOn = caps
    }

    private fun BluetoothDevice.displayName(): String = name ?: address

    private companion object {
        const val TAG = "HidKeyboard"

        /** Bit 1 of the keyboard LED output report (bit 0 is Num Lock, bit 2 Scroll Lock). */
        const val LED_CAPS_LOCK = 0x02
    }
}
