package io.homeassistant.btdashboard.config

import android.content.Context
import io.homeassistant.btdashboard.bluetooth.BluetoothTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DeviceConfig(
    val id: String,
    val address: String,
    val passcode: Int,
    val transport: BluetoothTransport,
    val name: String,
    val lastSyncTime: Long = 0L,
) {
    fun passcodeDisplay(): String {
        val hex = "%08X".format(passcode)
        return "${hex.substring(0, 4)}-${hex.substring(4)}"
    }
}

class BtConfig(context: Context) {
    private val prefs = context.getSharedPreferences("btdash", Context.MODE_PRIVATE)

    // ── Multi-device list ──────────────────────────────────────────────────────

    var devices: List<DeviceConfig>
        get() {
            val json = prefs.getString(KEY_DEVICES, null) ?: return migrateLegacyDevice()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { parseDevice(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        private set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(serializeDevice(it)) }
            prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
        }

    var activeDeviceId: String?
        get() = prefs.getString(KEY_ACTIVE_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_DEVICE, value).apply()

    val activeDevice: DeviceConfig?
        get() = devices.find { it.id == activeDeviceId } ?: devices.firstOrNull()

    val isConfigured: Boolean
        get() = activeDevice != null

    // ── Compat accessors (delegate to active device) ───────────────────────────

    val btAddress: String? get() = activeDevice?.address
    val passcode: Int get() = activeDevice?.passcode ?: 0
    val transport: BluetoothTransport get() = activeDevice?.transport ?: BluetoothTransport.BLE
    var lastSyncTime: Long
        get() = activeDevice?.lastSyncTime ?: 0L
        set(value) {
            val active = activeDevice ?: return
            updateDevice(active.copy(lastSyncTime = value))
        }

    fun passcodeDisplay(): String = activeDevice?.passcodeDisplay() ?: "-"

    // ── Device management ──────────────────────────────────────────────────────

    fun addDevice(config: DeviceConfig) {
        val current = devices.toMutableList()
        current.removeAll { it.id == config.id }
        current.add(config)
        devices = current
        if (activeDeviceId == null) activeDeviceId = config.id
    }

    fun removeDevice(id: String) {
        devices = devices.filter { it.id != id }
        if (activeDeviceId == id) activeDeviceId = devices.firstOrNull()?.id
    }

    fun setActive(id: String) {
        if (devices.any { it.id == id }) activeDeviceId = id
    }

    fun updateDevice(config: DeviceConfig) {
        devices = devices.map { if (it.id == config.id) config else it }
    }

    fun clear() = prefs.edit().clear().apply()

    // ── Legacy migration (single-device → list) ────────────────────────────────

    private fun migrateLegacyDevice(): List<DeviceConfig> {
        val address = prefs.getString(KEY_LEGACY_ADDRESS, null) ?: return emptyList()
        val passcode = prefs.getInt(KEY_LEGACY_PASSCODE, 0)
        if (passcode == 0) return emptyList()
        val transport = BluetoothTransport.valueOf(
            prefs.getString(KEY_LEGACY_TRANSPORT, BluetoothTransport.BLE.name)!!
        )
        val lastSync = prefs.getLong(KEY_LEGACY_LAST_SYNC, 0L)
        val id = UUID.randomUUID().toString()
        val device = DeviceConfig(
            id = id, address = address, passcode = passcode,
            transport = transport, name = "Home Assistant", lastSyncTime = lastSync,
        )
        val arr = JSONArray().apply { put(serializeDevice(device)) }
        prefs.edit()
            .putString(KEY_DEVICES, arr.toString())
            .putString(KEY_ACTIVE_DEVICE, id)
            .remove(KEY_LEGACY_ADDRESS)
            .remove(KEY_LEGACY_PASSCODE)
            .remove(KEY_LEGACY_TRANSPORT)
            .remove(KEY_LEGACY_LAST_SYNC)
            .apply()
        return listOf(device)
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    private fun serializeDevice(d: DeviceConfig): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("address", d.address)
        put("passcode", d.passcode)
        put("transport", d.transport.name)
        put("name", d.name)
        put("lastSyncTime", d.lastSyncTime)
    }

    private fun parseDevice(obj: JSONObject) = DeviceConfig(
        id = obj.getString("id"),
        address = obj.getString("address"),
        passcode = obj.getInt("passcode"),
        transport = BluetoothTransport.valueOf(obj.optString("transport", BluetoothTransport.BLE.name)),
        name = obj.optString("name", "Home Assistant"),
        lastSyncTime = obj.optLong("lastSyncTime", 0L),
    )

    companion object {
        private const val KEY_DEVICES       = "devices_v2"
        private const val KEY_ACTIVE_DEVICE = "active_device_id"
        // Legacy keys (pre-multi-device)
        private const val KEY_LEGACY_ADDRESS   = "bt_address"
        private const val KEY_LEGACY_PASSCODE  = "passcode"
        private const val KEY_LEGACY_TRANSPORT = "bt_transport"
        private const val KEY_LEGACY_LAST_SYNC = "last_sync"
    }
}
