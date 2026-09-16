package dev.murmr.app.hid

/** A paired Bluetooth device as shown to the user. [address] is the MAC used to connect. */
data class HostDevice(val name: String, val address: String)
