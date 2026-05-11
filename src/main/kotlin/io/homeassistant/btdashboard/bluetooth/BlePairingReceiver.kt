package io.github.gruni22.btdashboard.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.gruni22.btdashboard.config.BtConfig
import timber.log.Timber

/**
 * Auto-confirms Just Works (NoInputNoOutput / CONSENT) BLE pairing for the configured HA device.
 *
 * BlueZ on Linux sends an SMP Security Request for every new LE connection even when no bonding
 * is required. Android's BluetoothPairingRequest activity shows a dialog unless something calls
 * setPairingConfirmation(true) first. CDM-associated apps are granted implicit BLUETOOTH_PRIVILEGED
 * for their associated device (Android 12+), so we can suppress the dialog here.
 */
class BlePairingReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return

        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)

        // PAIRING_VARIANT_CONSENT = 3 (Just Works / NoInputNoOutput)
        // PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2 (Numeric Comparison — also auto-accept)
        if (variant != 3 && variant != 2) {
            Timber.d("BLE: pairing variant=$variant — not auto-confirming")
            return
        }

        val configuredAddresses = BtConfig(context).devices.map { it.address }
        Timber.d("BLE: pairing from ${device.address} variant=$variant; configured=$configuredAddresses")
        // Just Works (variant 3) and Numeric Comparison (variant 2) provide no user-verifiable
        // security — auto-confirm for all to suppress the system dialog. Real auth is the 32-bit
        // packet passcode. Only confirm if the device is in our config, but log either way.
        if (configuredAddresses.isNotEmpty() && configuredAddresses.none { it.equals(device.address, ignoreCase = true) }) {
            Timber.d("BLE: not our device — skipping auto-confirm")
            return
        }

        try {
            device.setPairingConfirmation(true)
            abortBroadcast()
            Timber.i("BLE: auto-confirmed Just Works pairing for ${device.address} (variant=$variant)")
        } catch (e: SecurityException) {
            // CDM implicit privilege not granted on this Android version — log only, dialog will show
            Timber.w(e, "BLE: setPairingConfirmation denied (no BLUETOOTH_PRIVILEGED) for ${device.address}")
        }
    }
}
