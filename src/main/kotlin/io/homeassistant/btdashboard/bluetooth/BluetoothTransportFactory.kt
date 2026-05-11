package io.github.gruni22.btdashboard.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import io.github.gruni22.btdashboard.bluetooth.ble.BleGattTransport
import io.github.gruni22.btdashboard.bluetooth.rfcomm.RfcommTransport

suspend fun openBluetoothTransport(
    context: Context,
    adapter: BluetoothAdapter,
    address: String,
    transportType: BluetoothTransport,
): FrameProtocol = when (transportType) {
    BluetoothTransport.RFCOMM -> RfcommTransport.connect(adapter, address)
    BluetoothTransport.BLE -> BleGattTransport.connect(context, adapter, address)
}
