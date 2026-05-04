package io.homeassistant.btdashboard.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import io.homeassistant.btdashboard.bluetooth.ble.BleGattTransport
import io.homeassistant.btdashboard.bluetooth.rfcomm.RfcommTransport

suspend fun openBluetoothTransport(
    context: Context,
    adapter: BluetoothAdapter,
    address: String,
    transportType: BluetoothTransport,
): FrameProtocol = when (transportType) {
    BluetoothTransport.RFCOMM -> RfcommTransport.connect(adapter, address)
    BluetoothTransport.BLE -> BleGattTransport.connect(context, adapter, address)
}
