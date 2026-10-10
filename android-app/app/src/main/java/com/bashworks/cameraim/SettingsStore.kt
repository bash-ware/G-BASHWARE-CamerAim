package com.bashworks.cameraim

import android.content.Context
import androidx.core.content.edit

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("cameraim", Context.MODE_PRIVATE)

    fun load(): ConnectionSettings = ConnectionSettings(
        baseUrl = preferences.getString("baseUrl", "") ?: "",
        machineId = preferences.getString("machineId", "workshop-cnc-1") ?: "workshop-cnc-1",
        readToken = preferences.getString("readToken", "") ?: ""
    )

    fun save(settings: ConnectionSettings) {
        preferences.edit {
            putString("baseUrl", settings.baseUrl)
            putString("machineId", settings.machineId)
            putString("readToken", settings.readToken)
        }
    }

    fun cursor(machineId: String): Long = preferences.getLong("cursor.$machineId", 0)

    fun saveCursor(machineId: String, value: Long) {
        preferences.edit { putLong("cursor.$machineId", value) }
    }

    fun setMonitoring(active: Boolean, detail: String) {
        preferences.edit {
            putBoolean("monitoring.active", active)
            putString("monitoring.detail", detail)
            putLong("monitoring.heartbeat", System.currentTimeMillis())
        }
    }

    fun touchMonitoring() {
        preferences.edit { putLong("monitoring.heartbeat", System.currentTimeMillis()) }
    }

    fun monitoringActive(): Boolean {
        val active = preferences.getBoolean("monitoring.active", false)
        val heartbeat = preferences.getLong("monitoring.heartbeat", 0)
        if (active && System.currentTimeMillis() - heartbeat > 15_000) {
            setMonitoring(false, "Praćenje je prekinuto; pokrenite ga ponovo.")
            return false
        }
        return active
    }
    fun monitoringDetail(): String = preferences.getString("monitoring.detail", "Zaustavljeno") ?: "Zaustavljeno"
}
