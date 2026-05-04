package io.homeassistant.btdashboard.setup

import android.Manifest
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import io.homeassistant.btdashboard.R
import kotlinx.coroutines.launch
import timber.log.Timber

private val HaBrandBlue = Color(0xFF18BCF2)
private val MAX_CONTENT_WIDTH = 480.dp
private const val HA_BLE_SERVICE_UUID = "a10d4b1c-bf45-4c2a-9c32-4a8f7e3d1234"

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    val context = LocalContext.current
    val cdmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { onSaved() }

    LaunchedEffect(uiState.saved) {
        if (!uiState.saved) return@LaunchedEffect
        tryAssociateCompanionDevice(
            context = context,
            address = uiState.address.trim(),
            onAssociate = { sender ->
                cdmLauncher.launch(IntentSenderRequest.Builder(sender).build())
            },
            onSkip = onSaved,
        )
    }

    val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberMultiplePermissionsState(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        )
    } else {
        rememberMultiplePermissionsState(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    LaunchedEffect(btPermissions.allPermissionsGranted) {
        if (btPermissions.allPermissionsGranted && uiState.step == SetupStep.SCAN) viewModel.startScan()
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    var showQrScanner by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.selectedDevice) {
        if (uiState.selectedDevice != null && !showSheet && uiState.step == SetupStep.SCAN) {
            showSheet = true
            scope.launch { sheetState.show() }
        }
    }

    BackHandler(enabled = uiState.step == SetupStep.PASSCODE) { viewModel.backToScan() }

    val onNavBack: () -> Unit = if (uiState.step == SetupStep.PASSCODE) viewModel::backToScan else onBack

    Scaffold(
        topBar = {
            if (uiState.step != SetupStep.SYNCING) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onNavBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState.step) {
                SetupStep.SCAN -> ScanStep(
                    uiState = uiState,
                    permissionsGranted = btPermissions.allPermissionsGranted,
                    onRequestPermissions = { btPermissions.launchMultiplePermissionRequest() },
                    onShowSheet = { showSheet = true; scope.launch { sheetState.show() } },
                )
                SetupStep.PASSCODE -> PasscodeStep(
                    uiState = uiState,
                    onScanQr = { showQrScanner = true },
                    onPasscodeChange = viewModel::setPasscodeInput,
                    onSubmit = { viewModel.submitPasscode(uiState.passcodeInput) },
                )
                SetupStep.SYNCING -> SyncingStep(uiState)
                SetupStep.DONE -> Unit
            }

            if (showSheet && uiState.step == SetupStep.SCAN) {
                ModalBottomSheet(
                    onDismissRequest = { showSheet = false },
                    sheetState = sheetState,
                ) {
                    ConnectBottomSheet(
                        uiState = uiState,
                        onAddressChange = viewModel::setAddress,
                        onContinue = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                showSheet = false
                                viewModel.proceedToPasscode()
                            }
                        },
                    )
                }
            }

            // Full-screen QR scanner overlay (replaces the old ZXing launcher activity)
            if (showQrScanner) {
                QrScannerScreen(
                    onScanned = { code ->
                        showQrScanner = false
                        viewModel.submitPasscode(code)
                    },
                    onCancel = { showQrScanner = false },
                )
            }
        }
    }
}

// ── SCAN step ─────────────────────────────────────────────────────────────────

@Composable
private fun ScanStep(
    uiState: SetupUiState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onShowSheet: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!permissionsGranted) {
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.bt_setup_permission_required),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermissions) {
                Text(stringResource(R.string.bt_grant_permission))
            }
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                text = stringResource(R.string.bt_setup_searching),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH)
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
            ScanningContent(uiState)
            TextButton(
                onClick = onShowSheet,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                Text(stringResource(R.string.bt_setup_address_manual))
            }
        }
    }
}

// ── PASSCODE step ─────────────────────────────────────────────────────────────

@Composable
private fun PasscodeStep(
    uiState: SetupUiState,
    onScanQr: () -> Unit,
    onPasscodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bt_setup_passcode_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.bt_setup_passcode_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onScanQr,
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(50),
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.bt_setup_scan_qr), fontWeight = FontWeight.W500)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.bt_setup_or_manual),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            value = uiState.passcodeInput,
            onValueChange = onPasscodeChange,
            label = { Text(stringResource(R.string.bt_setup_passcode_label)) },
            placeholder = { Text("A3F9-12CB") },
            singleLine = true,
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
        )
        if (uiState.error != null) {
            Text(
                text = uiState.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
            )
        }
        OutlinedButton(
            onClick = onSubmit,
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(50),
            enabled = uiState.passcodeInput.isNotBlank(),
        ) {
            Text(stringResource(R.string.bt_setup_submit_passcode), fontWeight = FontWeight.W500)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── SYNCING step ──────────────────────────────────────────────────────────────

@Composable
private fun SyncingStep(uiState: SetupUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            text = uiState.syncStatus.ifEmpty { stringResource(R.string.bt_setup_syncing) },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
        )
        if (uiState.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = uiState.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth(),
            )
        }
    }
}

// ── Animated scanning content ─────────────────────────────────────────────────

@Composable
private fun ColumnScope.ScanningContent(uiState: SetupUiState) {
    val positionPercentage = 0.2f
    Spacer(Modifier.weight(positionPercentage))
    AnimatedDiscoveryIcon()
    Spacer(Modifier.weight(positionPercentage))
    val noResultAlpha by animateFloatAsState(
        targetValue = if (!uiState.scanning && uiState.scannedDevices.isEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
        label = "no_result_alpha",
    )
    Text(
        text = stringResource(R.string.bt_setup_no_server_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .widthIn(max = MAX_CONTENT_WIDTH)
            .padding(vertical = 12.dp, horizontal = 16.dp)
            .alpha(noResultAlpha),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.weight(1f - 2f * positionPercentage))
}

@Composable
private fun AnimatedDiscoveryIcon() {
    val rotation by rememberInfiniteTransition(label = "dots_rotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dots_rotation_value",
    )
    val pulse by rememberInfiniteTransition(label = "icon_pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "icon_pulse_value",
    )

    Box(contentAlignment = Alignment.Center) {
        Image(
            imageVector = ImageVector.vectorResource(R.drawable.dots),
            contentDescription = null,
            modifier = Modifier
                .size(220.dp)
                .rotate(rotation),
        )
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(pulse)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(80.dp)) { drawCircle(HaBrandBlue) }
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_ha_icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp).scale(pulse),
                tint = Color.White,
            )
        }
    }
}

// ── Connect bottom sheet (SCAN step) ─────────────────────────────────────────

@Composable
private fun ConnectBottomSheet(
    uiState: SetupUiState,
    onAddressChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val device = uiState.selectedDevice
        if (device != null) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.W500,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = uiState.address,
                onValueChange = onAddressChange,
                label = { Text(stringResource(R.string.bt_setup_address_label)) },
                placeholder = { Text(stringResource(R.string.bt_setup_address_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider()
        if (uiState.error != null) {
            Text(
                text = uiState.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(50),
            enabled = uiState.address.isNotBlank() || device != null,
        ) {
            Text(stringResource(R.string.bt_setup_continue), fontWeight = FontWeight.W500)
        }
    }
}

// ── Companion Device Association ─────────────────────────────────────────────
// Creates a one-time CDM association so Android auto-confirms Just Works BLE pairing
// silently on every future connect (canBondWithoutDialog=true), eliminating pairing dialogs.

private fun tryAssociateCompanionDevice(
    context: Context,
    address: String,
    onAssociate: (IntentSender) -> Unit,
    onSkip: () -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || address.isBlank()) { onSkip(); return }

    val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: run { onSkip(); return }

    val alreadyAssociated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        cdm.myAssociations.any {
            it.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
        }
    } else {
        @Suppress("DEPRECATION")
        cdm.associations.any { it.equals(address, ignoreCase = true) }
    }
    if (alreadyAssociated) { Timber.d("CDM: already associated with $address"); onSkip(); return }

    // BluetoothLeDeviceFilter uses setScanFilter to combine service UUID + device address
    val scanFilter = android.bluetooth.le.ScanFilter.Builder()
        .setServiceUuid(ParcelUuid.fromString(HA_BLE_SERVICE_UUID))
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
                Timber.d("CDM: device found, launching association dialog")
                onAssociate(chooserLauncher)
            }
            override fun onFailure(error: CharSequence?) {
                Timber.w("CDM: association failed ($error) — skipping")
                onSkip()
            }
        }, null)
    } catch (e: Exception) {
        Timber.w(e, "CDM: associate() threw, skipping")
        onSkip()
    }
}
