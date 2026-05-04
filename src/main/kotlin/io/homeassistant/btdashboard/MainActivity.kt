package io.homeassistant.btdashboard

import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.btdashboard.config.BtConfig
import io.homeassistant.btdashboard.dashboard.BluetoothDashboardScreen
import io.homeassistant.btdashboard.settings.SettingsScreen
import io.homeassistant.btdashboard.setup.SetupScreen
import io.homeassistant.btdashboard.ui.HaBluetoothTheme
import io.homeassistant.btdashboard.welcome.WelcomeScreen
import timber.log.Timber

private const val HA_BLE_SVC_UUID = "a10d4b1c-bf45-4c2a-9c32-4a8f7e3d1234"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val cdmLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { Timber.d("CDM: association dialog closed") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = BtConfig(applicationContext)
        val startDest = if (config.isConfigured) "dashboard" else "welcome"

        // Ensure CDM association exists for the active BLE device so Android
        // auto-confirms Just Works pairing silently (canBondWithoutDialog=true).
        if (savedInstanceState == null) ensureCompanionAssociation(config)

        setContent {
            HaBluetoothTheme {
                val navController = rememberNavController()
                NavHost(navController, startDestination = startDest) {
                    composable("welcome") {
                        WelcomeScreen(
                            onConnectClick = { navController.navigate("setup") },
                        )
                    }
                    composable("setup") {
                        SetupScreen(
                            onSaved = {
                                navController.navigate("dashboard") {
                                    popUpTo("welcome") { inclusive = true }
                                }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("dashboard") {
                        BluetoothDashboardScreen(
                            onNavigateToSetup = {
                                navController.navigate("setup") {
                                    popUpTo("dashboard") { inclusive = true }
                                }
                            },
                            onNavigateToSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToSetup = {
                                navController.navigate("setup") {
                                    popUpTo("dashboard") { inclusive = true }
                                }
                            },
                            onAddDevice = { navController.navigate("setup") },
                        )
                    }
                }
            }
        }
    }

    private fun ensureCompanionAssociation(config: BtConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val address = config.activeDevice?.address ?: return
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return

        val isAssociated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.any {
                it.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
            }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations.any { it.equals(address, ignoreCase = true) }
        }
        if (isAssociated) { Timber.d("CDM: $address already associated"); return }

        val scanFilter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(HA_BLE_SVC_UUID))
            .setDeviceAddress(address)
            .build()
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        try {
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    Timber.d("CDM: launching association dialog for $address")
                    cdmLauncher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
                }
                override fun onFailure(error: CharSequence?) {
                    Timber.w("CDM: association failed: $error")
                }
            }, null)
        } catch (e: Exception) {
            Timber.w(e, "CDM: associate() threw")
        }
    }
}
