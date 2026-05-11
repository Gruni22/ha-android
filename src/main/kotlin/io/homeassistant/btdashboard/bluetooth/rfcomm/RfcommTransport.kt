package io.github.gruni22.btdashboard.bluetooth.rfcomm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import io.github.gruni22.btdashboard.bluetooth.FrameProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

private val HA_RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
class RfcommTransport private constructor(
    private val socket: BluetoothSocket,
) : FrameProtocol {

    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)

    override val isOpen: Boolean get() = socket.isConnected

    override suspend fun readFrame(): ByteArray = withContext(Dispatchers.IO) {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_SIZE) throw IOException("Invalid RFCOMM frame length: $length")
        val buffer = ByteArray(length)
        input.readFully(buffer)
        buffer
    }

    override suspend fun writeFrame(data: ByteArray) = withContext(Dispatchers.IO) {
        if (data.size > MAX_FRAME_SIZE) throw IOException("Frame too large: ${data.size}")
        output.writeInt(data.size)
        output.write(data)
        output.flush()
    }

    override fun close() = runCatching { socket.close() }.let {}

    companion object {
        private const val MAX_FRAME_SIZE = 16 * 1024 * 1024
        private const val RFCOMM_CHANNEL = 1

        suspend fun connect(adapter: BluetoothAdapter, deviceAddress: String): RfcommTransport =
            withContext(Dispatchers.IO) {
                val device = adapter.getRemoteDevice(deviceAddress)
                adapter.cancelDiscovery()
                val socket = connectWithFallback(device)
                Timber.i("RFCOMM connected to $deviceAddress")
                RfcommTransport(socket)
            }

        @Suppress("DiscouragedPrivateApi")
        private fun connectWithFallback(device: android.bluetooth.BluetoothDevice): BluetoothSocket {
            runCatching {
                device.createInsecureRfcommSocketToServiceRecord(HA_RFCOMM_UUID).also { it.connect() }
            }.onSuccess { return it }

            runCatching {
                (device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.java)
                    .invoke(device, RFCOMM_CHANNEL) as BluetoothSocket).also { it.connect() }
            }.onSuccess { return it }

            return (device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                .invoke(device, RFCOMM_CHANNEL) as BluetoothSocket).also { it.connect() }
        }
    }
}
