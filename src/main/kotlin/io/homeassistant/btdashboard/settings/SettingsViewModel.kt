package io.homeassistant.btdashboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.btdashboard.config.BtConfig
import io.homeassistant.btdashboard.config.DeviceConfig
import io.homeassistant.btdashboard.db.AppDatabase
import io.homeassistant.btdashboard.service.BleConnectionService
import io.homeassistant.btdashboard.sync.SyncResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class SettingsUiState(
    val devices: List<DeviceConfig> = emptyList(),
    val activeDeviceId: String? = null,
    val syncing: Boolean = false,
    val syncStatus: String = "",
    val error: String? = null,
    val navigateToSetup: Boolean = false,
)

val SettingsUiState.activeDevice: DeviceConfig?
    get() = devices.find { it.id == activeDeviceId } ?: devices.firstOrNull()

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val btConfig = BtConfig(context)

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Bind to BleConnectionService so resync uses its connection (NOT a new one).
    // Two parallel BluetoothGatt instances to the same device would split notification
    // delivery between the two callbacks, causing the second client to never see ANS_DEVICES.
    @Volatile private var service: BleConnectionService? = null
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BleConnectionService.BleConnectionBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    init {
        // Bind without START — service is already started by the dashboard
        context.bindService(
            Intent(context, BleConnectionService::class.java), serviceConn, Context.BIND_AUTO_CREATE,
        )
    }

    override fun onCleared() {
        runCatching { context.unbindService(serviceConn) }
        super.onCleared()
    }

    private fun buildInitialState() = SettingsUiState(
        devices = btConfig.devices,
        activeDeviceId = btConfig.activeDeviceId,
    )

    /**
     * No-op kept for source compatibility — multi-instance mode runs every
     * configured device in parallel, so switching is meaningless. Calling it
     * just nudges the service to reconcile its sessions with `BtConfig.devices`
     * (e.g. picks up a freshly-added device that wasn't connected yet).
     */
    fun switchDevice(id: String) {
        service?.applyActiveDeviceChange()
    }

    fun removeDevice(id: String) {
        val wasActive = btConfig.activeDeviceId == id
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getInstance(context)
                db.entityDao().deleteAllForDevice(id)
                db.areaDao().deleteAllForDevice(id)
                db.dashboardDao().deleteAllForDevice(id)
                db.viewDao().deleteAllForDevice(id)
                btConfig.removeDevice(id)
            }.onFailure { Timber.e(it, "Settings: removeDevice failed") }
            val remaining = btConfig.devices
            if (remaining.isEmpty()) {
                _uiState.update { it.copy(navigateToSetup = true) }
            } else {
                _uiState.update { it.copy(devices = remaining, activeDeviceId = btConfig.activeDeviceId) }
                // If we just removed the device the service was using, the
                // active fell through to the next entry — kick the service so
                // it reconnects to that one instead of dangling on the dead BLE
                // address.
                if (wasActive) service?.applyActiveDeviceChange()
            }
        }
    }

    /**
     * Resync. With no [deviceId] passed, resyncs every configured gateway in
     * parallel; with a [deviceId] only that one. Both routes go through
     * [BleConnectionService.forceResync] which handles the disconnect/sync
     * dance per session.
     */
    fun resync(deviceId: String? = null) {
        val svc = service ?: run {
            _uiState.update { it.copy(error = "Service nicht verbunden — App neu öffnen") }
            return
        }
        val targets = if (deviceId != null) listOfNotNull(btConfig.devices.find { it.id == deviceId })
                      else btConfig.devices
        if (targets.isEmpty()) return
        _uiState.update { it.copy(syncing = true, syncStatus = "Synchronisiere…", error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = svc.forceResync(deviceId)) {
                    is SyncResult.Success -> {
                        // Update lastSyncTime for every device that was synced.
                        val now = System.currentTimeMillis()
                        targets.forEach { btConfig.updateDevice(it.copy(lastSyncTime = now)) }
                        _uiState.update {
                            it.copy(
                                syncing = false,
                                syncStatus = "Sync abgeschlossen (${result.areasCount} Bereiche, ${result.entitiesCount} Entitäten)",
                                devices = btConfig.devices,
                                error = null,
                            )
                        }
                        Timber.i("Settings: resync complete")
                    }
                    is SyncResult.Error -> {
                        _uiState.update { it.copy(syncing = false, error = result.message, syncStatus = "") }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Settings: resync failed")
                _uiState.update { it.copy(syncing = false, error = e.message ?: "Sync-Fehler", syncStatus = "") }
            }
        }
    }

    fun reset() {
        val deviceId = btConfig.activeDevice?.id
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getInstance(context)
                if (deviceId != null) {
                    db.entityDao().deleteAllForDevice(deviceId)
                    db.areaDao().deleteAllForDevice(deviceId)
                    db.dashboardDao().deleteAllForDevice(deviceId)
                    db.viewDao().deleteAllForDevice(deviceId)
                    btConfig.removeDevice(deviceId)
                } else {
                    db.entityDao().deleteAll()
                    db.areaDao().deleteAll()
                    db.dashboardDao().deleteAll()
                    db.viewDao().deleteAll()
                    btConfig.clear()
                }
            }.onFailure { Timber.e(it, "Settings: reset failed") }
            val remaining = btConfig.devices
            if (remaining.isEmpty()) {
                _uiState.update { it.copy(navigateToSetup = true) }
            } else {
                _uiState.update { it.copy(devices = remaining, activeDeviceId = btConfig.activeDeviceId) }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}

internal fun formatDate(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))
