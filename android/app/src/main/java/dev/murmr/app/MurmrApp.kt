package dev.murmr.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.murmr.app.settings.SettingsStore

class MurmrApp : Application() {

    /** App-wide settings, shared by the service and the UI. */
    val settings: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "murmr.keyboard"
    }
}
