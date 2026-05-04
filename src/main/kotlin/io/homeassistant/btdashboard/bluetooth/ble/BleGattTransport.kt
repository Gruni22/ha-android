package io.homeassistant.btdashboard.bluetooth.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import io.homeassistant.btdashboard.bluetooth.FrameProtocol
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import timber.log.Timber

private val HA_BLE_SERVICE_UUID: UUID = UUID.fromString("a10d4b1c-bf45-4c2a-9c32-4a8f7e3d1234")
private val HA_BLE_TX_UUID: UUID      = UUID.fromString("a10d4b1c-bf45-4c2a-9c32-4a8f7e3d1235")
private val HA_BLE_RX_UUID: UUID      = UUID.fromString("a10d4b1c-bf45-4c2a-9c32-4a8f7e3d1236")

private const val CHUNK_CONTINUES: Byte = 0x00
private const val CHUNK_FINAL: Byte     = 0x01
private const val BLE_MAX_CHUNK         = 244  // MTU 247 − 3 ATT header

/**
 * BLE GATT transport built on Nordic Semiconductor's BleManager.
 *
 * Why Nordic instead of raw BluetoothGatt: long-lived connections built directly
 * on Android's BluetoothGatt regularly lose notification delivery (the central
 * stack stops invoking onCharacteristicChanged even though the peripheral sends
 * notifies and the link is up). Nordic's BleManager has battle-tested
 * workarounds (re-enable notifications on link refresh, retry on transient
 * errors, MTU/PHY negotiation order, etc.) and serializes all writes internally
 * so the request queue never races.
 */
@SuppressLint("MissingPermission")
internal class BleGattTransport private constructor(
    private val manager: HaBleManager,
) : FrameProtocol {

    override val isOpen: Boolean get() = manager.transportReady

    override suspend fun readFrame(): ByteArray = manager.incomingFrames.receive()

    override suspend fun writeFrame(data: ByteArray) {
        if (!manager.transportReady) throw IOException("BLE transport is closed")
        manager.writeFrame(data)
    }

    override fun close() {
        manager.disconnectSync()
    }

    companion object {
        suspend fun connect(
            context: Context,
            adapter: BluetoothAdapter,
            deviceAddress: String,
        ): BleGattTransport {
            val device = adapter.getRemoteDevice(deviceAddress)
                ?: throw IOException("BLE device not found: $deviceAddress")

            // Open BLE: peer (ESP32) requires no bond. Remove any stale Android
            // bond so the LL doesn't try LL_ENC_REQ with a stale LTK.
            if (device.bondState != BluetoothDevice.BOND_NONE) {
                Timber.d("BLE: removing stale bond for $deviceAddress")
                runCatching { device.javaClass.getMethod("removeBond").invoke(device) }
            }

            val manager = HaBleManager(context)
            try {
                manager.connect(device)
                    .useAutoConnect(false)
                    .timeout(20_000)
                    .retry(3, 200)
                    .suspend()
            } catch (e: Exception) {
                manager.close()
                throw IOException("BLE connect failed: ${e.message}", e)
            }
            return BleGattTransport(manager)
        }
    }
}

/**
 * Nordic BleManager subclass implementing our HA service: subscribes to TX,
 * sends chunks to RX, exposes a Channel of fully-reassembled inbound frames.
 */
@SuppressLint("MissingPermission")
internal class HaBleManager(context: Context) : BleManager(context) {

    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    private val chunkBuffer = mutableListOf<Byte>()
    val incomingFrames: Channel<ByteArray> = Channel(capacity = Channel.UNLIMITED)

    private val _connState = MutableStateFlow(false)
    val isReadyFlow: StateFlow<Boolean> = _connState
    val transportReady: Boolean get() = _connState.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        connectionObserver = object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                Timber.d("BLE: connecting to ${device.address}")
            }
            override fun onDeviceConnected(device: BluetoothDevice) {
                Timber.d("BLE: connected ${device.address}")
            }
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                Timber.w("BLE: failed to connect ${device.address} reason=$reason")
                _connState.value = false
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                Timber.i("BLE: device READY ${device.address}")
                _connState.value = true
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                Timber.d("BLE: disconnecting ${device.address}")
            }
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                Timber.i("BLE: disconnected ${device.address} reason=$reason")
                _connState.value = false
                incomingFrames.close()
            }
        }
    }

    override fun getMinLogPriority(): Int = android.util.Log.VERBOSE

    override fun log(priority: Int, message: String) {
        when (priority) {
            android.util.Log.ERROR -> Timber.e("BLE-NRF: %s", message)
            android.util.Log.WARN -> Timber.w("BLE-NRF: %s", message)
            else -> Timber.d("BLE-NRF: %s", message)
        }
    }

    override fun getGattCallback(): BleManagerGattCallback = HaGattCallback()

    private inner class HaGattCallback : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(HA_BLE_SERVICE_UUID) ?: return false
            txChar = svc.getCharacteristic(HA_BLE_TX_UUID)
            rxChar = svc.getCharacteristic(HA_BLE_RX_UUID)
            return txChar != null && rxChar != null
        }

        override fun onServicesInvalidated() {
            txChar = null
            rxChar = null
        }

        override fun initialize() {
            // Negotiate MTU (peer supports 247). Nordic falls back gracefully if it fails.
            requestMtu(247).enqueue()

            // Reassemble incoming chunks into full frames.
            setNotificationCallback(txChar)
                .with { _, data ->
                    val bytes = data.value ?: return@with
                    if (bytes.isEmpty()) return@with
                    val flag = bytes[0]
                    chunkBuffer.addAll(bytes.drop(1))
                    if (flag == CHUNK_FINAL) {
                        val frame = chunkBuffer.toByteArray()
                        chunkBuffer.clear()
                        Timber.d("BLE-RX: assembled frame %d bytes", frame.size)
                        incomingFrames.trySend(frame)
                    }
                }

            // Enable notifications on TX (writes CCCD 0x0100). Library handles the
            // descriptor write internally and retries if the peer is busy.
            enableNotifications(txChar)
                .done { Timber.d("BLE: notifications enabled on TX") }
                .fail { _, status -> Timber.e("BLE: enableNotifications failed status=$status") }
                .enqueue()
        }
    }

    /**
     * Splits [data] into BLE_MAX_CHUNK pieces with a 1-byte continuation flag and
     * writes each chunk to RX with WRITE_NO_RESPONSE. All requests are serialised
     * by Nordic's internal queue, so concurrent callers don't race.
     */
    suspend fun writeFrame(data: ByteArray) {
        val rx = rxChar ?: throw IOException("RX characteristic not ready")
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + BLE_MAX_CHUNK, data.size)
            val isLast = end == data.size
            val chunk = ByteArray(end - offset + 1)
            chunk[0] = if (isLast) CHUNK_FINAL else CHUNK_CONTINUES
            data.copyInto(chunk, destinationOffset = 1, startIndex = offset, endIndex = end)
            try {
                writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    .suspend()
            } catch (e: Exception) {
                throw IOException("BLE write failed at offset $offset: ${e.message}", e)
            }
            offset = end
        }
    }

    fun disconnectSync() {
        runCatching { disconnect().enqueue() }
        runCatching { close() }
        scope.cancel()
        incomingFrames.close()
    }
}
