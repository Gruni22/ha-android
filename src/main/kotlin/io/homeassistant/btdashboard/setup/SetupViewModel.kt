package io.homeassistant.btdashboard.setup

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.btdashboard.bluetooth.BluetoothTransport
import io.homeassistant.btdashboard.config.BtConfig
import io.homeassistant.btdashboard.config.DeviceConfig
import io.homeassistant.btdashboard.dashboard.HaPacketClient
import java.util.UUID
import io.homeassistant.btdashboard.db.AppDatabase
import io.homeassistant.btdashboard.sync.SyncManager
import io.homeassistant.btdashboard.sync.SyncResult
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

private const val HA_BLE_SERVICE_UUID = "a10d4b1c-bf45-4c2a-9c32-4a8f7e3d1234"
private const val SCAN_DURATION_MS = 12_000L

data class ScannedDevice(val address: String, val name: String) {
    val displayName: String get() = name.replace("_", " ").trim().ifEmpty { address }
}

enum class SetupStep {
    SCAN,        // scanning for HA devices
    PASSCODE,    // enter / scan QR passcode
    SYNCING,     // initial data sync
    DONE,        // navigating to dashboard
}

data class SetupUiState(
    val step: SetupStep = SetupStep.SCAN,
    val scanning: Boolean = false,
    val scannedDevices: List<ScannedDevice> = emptyList(),
    val selectedDevice: ScannedDevice? = null,
    val address: String = "",
    val passcodeInput: String = "",
    val syncing: Boolean = false,
    val syncStatus: String = "",
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val btConfig = BtConfig(context)
    private val btAdapter = context.getSystemService<BluetoothManager>()?.adapter
    private val _uiState = MutableStateFlow(SetupUiState(address = btConfig.btAddress ?: ""))
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private var scanCallback: ScanCallback? = null

    // ── Scan ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = btAdapter?.bluetoothLeScanner ?: run {
            _uiState.update { it.copy(error = "Bluetooth not available") }
            return
        }
        _uiState.update { it.copy(scanning = true, scannedDevices = emptyList(), error = null) }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(HA_BLE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                @Suppress("MissingPermission")
                val name = runCatching { result.device.name }.getOrNull() ?: addr
                val current = _uiState.value.scannedDevices
                if (current.none { it.address == addr }) {
                    val device = ScannedDevice(addr, name)
                    _uiState.update { state ->
                        val updated = state.copy(scannedDevices = current + device)
                        if (state.selectedDevice == null)
                            updated.copy(selectedDevice = device, address = addr)
                        else updated
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                _uiState.update { it.copy(scanning = false, error = "Scan failed (code $errorCode)") }
            }
        }
        scanCallback = cb
        scanner.startScan(listOf(filter), settings, cb)
        viewModelScope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        btAdapter?.bluetoothLeScanner?.also { scanCallback?.let(it::stopScan) }
        scanCallback = null
        _uiState.update { it.copy(scanning = false) }
    }

    fun selectDevice(device: ScannedDevice) =
        _uiState.update { it.copy(selectedDevice = device, address = device.address, error = null) }

    fun setAddress(v: String) = _uiState.update { it.copy(address = v, error = null) }

    // ── Passcode step ─────────────────────────────────────────────────────────

    fun proceedToPasscode() {
        if (_uiState.value.address.isBlank()) {
            _uiState.update { it.copy(error = "Bitte zuerst ein Gerät auswählen") }
            return
        }
        _uiState.update { it.copy(step = SetupStep.PASSCODE, error = null) }
    }

    /** Called when QR scanner decodes "btdashboard:A3F912CB" or user types hex manually. */
    fun submitPasscode(raw: String) {
        val hex = raw.removePrefix("btdashboard:").replace("-", "").trim().uppercase()
        val value = hex.toLongOrNull(16)?.toInt()
        if (value == null || value == 0) {
            _uiState.update { it.copy(error = "Ungültiger Passcode: $raw") }
            return
        }
        _uiState.update { it.copy(passcodeInput = hex, error = null) }
        startSync(value)
    }

    fun setPasscodeInput(v: String) = _uiState.update { it.copy(passcodeInput = v, error = null) }

    // ── Sync step ─────────────────────────────────────────────────────────────

    private fun startSync(passcode: Int) {
        val address = _uiState.value.address.trim()
        val deviceId = UUID.randomUUID().toString()
        _uiState.update { it.copy(step = SetupStep.SYNCING, syncing = true, syncStatus = "Verbinde…", error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val client = HaPacketClient(context)
            try {
                client.connect(address, passcode, BluetoothTransport.BLE)
                _uiState.update { it.copy(syncStatus = "Synchronisiere Daten…") }

                val db = AppDatabase.getInstance(context)
                val result = SyncManager(client, db, deviceId).performInitialSync()

                when (result) {
                    is SyncResult.Success -> {
                        val scanned = _uiState.value.selectedDevice
                        val deviceName = scanned?.displayName ?: address
                        btConfig.addDevice(
                            DeviceConfig(
                                id = deviceId,
                                address = address,
                                passcode = passcode,
                                transport = BluetoothTransport.BLE,
                                name = deviceName,
                                lastSyncTime = System.currentTimeMillis(),
                            )
                        )
                        btConfig.setActive(deviceId)
                        _uiState.update { it.copy(syncing = false, saved = true, step = SetupStep.DONE) }
                        Timber.i("Setup sync complete: ${result.areasCount} areas, ${result.entitiesCount} entities")
                    }
                    is SyncResult.Error -> {
                        _uiState.update { it.copy(syncing = false, step = SetupStep.PASSCODE, error = result.message) }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Setup connect/sync failed")
                _uiState.update {
                    it.copy(syncing = false, step = SetupStep.PASSCODE, error = e.message ?: "Verbindungsfehler")
                }
            } finally {
                client.disconnect()
            }
        }
    }

    fun backToScan() = _uiState.update { it.copy(step = SetupStep.SCAN, error = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
