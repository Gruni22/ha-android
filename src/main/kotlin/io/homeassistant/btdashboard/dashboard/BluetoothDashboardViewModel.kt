package io.homeassistant.btdashboard.dashboard

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
import io.homeassistant.btdashboard.dashboard.HaView
import io.homeassistant.btdashboard.service.BleConnectionService
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BluetoothDashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _service = MutableStateFlow<BleConnectionService?>(null)

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            _service.value = (binder as BleConnectionService.BleConnectionBinder).getService()
            Timber.d("BtDashboard: service connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
            Timber.w("BtDashboard: service disconnected")
        }
    }

    val connectionState: StateFlow<BluetoothDashboardClient.State> = _service.flatMapLatest { svc ->
        svc?.connectionState ?: flowOf(BluetoothDashboardClient.State.DISCONNECTED)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BluetoothDashboardClient.State.DISCONNECTED)

    private val remoteEntities = _service.flatMapLatest { svc ->
        svc?.entities ?: flowOf(emptyList())
    }

    private val entityAreaMap = _service.flatMapLatest { svc ->
        svc?.entityAreaMap ?: flowOf(emptyMap())
    }

    val areas: StateFlow<List<HaArea>> = _service.flatMapLatest { svc ->
        svc?.areas ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Areas with at least one exposed entity. The bottom-of-dashboard area
     *  grid renders this list — areas without any visible device just clutter
     *  the UI and lead to dead-end taps (you'd select an empty area, the
     *  entity list would clear, and there'd be nowhere to tap to undo it). */
    val populatedAreas: StateFlow<List<HaArea>> = combine(
        areas, remoteEntities, entityAreaMap,
    ) { allAreas, entities, areaMap ->
        val populated = entities.mapNotNullTo(HashSet(allAreas.size)) { areaMap[it.entityId] }
        allAreas.filter { it.id in populated }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val dashboards: StateFlow<List<HaDashboardInfo>> = _service.flatMapLatest { svc ->
        svc?.dashboards ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeDashboardIndex: StateFlow<Int> = _service.flatMapLatest { svc ->
        svc?.activeDashboardIndex ?: flowOf(0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val views: StateFlow<List<HaView>> = _service.flatMapLatest { svc ->
        svc?.views ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeViewIndex: StateFlow<Int> = _service.flatMapLatest { svc ->
        svc?.activeViewIndex ?: flowOf(0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Map `deviceId → friendly device name` so tiles can show a gateway badge
     * when the user has more than one device configured. Built from
     * [BtConfig.devices] which is the source of truth — the service doesn't
     * expose it because device names live in user prefs, not in the BLE
     * link.
     */
    val gatewayLabels: StateFlow<Map<String, String>> = MutableStateFlow(
        BtConfig(context).devices.associate { it.id to it.name }
    )

    private val _searchQuery = MutableStateFlow("")
    private val _domainFilter = MutableStateFlow<String?>(null)
    private val _areaFilter = MutableStateFlow<String?>(null)

    val searchQuery: StateFlow<String> = _searchQuery
    val domainFilter: StateFlow<String?> = _domainFilter
    val areaFilter: StateFlow<String?> = _areaFilter

    val filteredEntities: StateFlow<List<HaEntityState>> = combine(
        remoteEntities, _searchQuery, _domainFilter, _areaFilter, entityAreaMap,
    ) { entities, query, domain, area, areaMap ->
        entities
            .filter { e ->
                (domain == null || e.domain == domain) &&
                (area == null || areaMap[e.entityId] == area) &&
                (query.isBlank() || e.friendlyName.contains(query, ignoreCase = true) || e.entityId.contains(query, ignoreCase = true))
            }
            .sortedWith(compareBy({ it.domain }, { it.friendlyName }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @Volatile private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        val intent = Intent(context, BleConnectionService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)

        // Observe state changes from service to update entities
        viewModelScope.launch {
            _service.flatMapLatest { svc ->
                svc?.stateChanges ?: flowOf()
            }.collect { /* entities updated by service internally */ }
        }
    }

    fun refresh() { _service.value?.refresh() }
    fun selectDashboard(index: Int) { _service.value?.selectDashboard(index) }
    fun selectView(index: Int) { _service.value?.selectView(index) }
    fun setSearch(query: String) { _searchQuery.value = query }
    fun setDomainFilter(domain: String?) { _domainFilter.value = domain }
    fun setAreaFilter(areaId: String?) { _areaFilter.value = areaId }

    fun toggle(entity: HaEntityState) {
        val svc = _service.value ?: return
        val (svcDomain, service) = entity.toggleAction() ?: return  // no-op for domains without binary toggle
        viewModelScope.launch {
            runCatching { svc.callService(svcDomain, service, entity.entityId, sourceDeviceId = entity.sourceDeviceId) }
                .onFailure { Timber.e(it, "BtDashboard: toggle ${entity.entityId} failed") }
        }
    }

    // ── Domain-specific setters ─────────────────────────────────────────────────
    //
    // All setters take the full `HaEntityState` so we can route to the right
    // gateway via `entity.sourceDeviceId`. Using just `entityId` would lose
    // that and break the multi-instance compound-key routing.

    /** Set climate setpoint (single-target). */
    fun setClimateTemperature(entity: HaEntityState, temperature: Double) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    "climate", "set_temperature", entity.entityId,
                    mapOf("temperature" to temperature), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: setClimateTemperature ${entity.entityId} failed") }
        }
    }

    fun setClimateHvacMode(entity: HaEntityState, hvacMode: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    "climate", "set_hvac_mode", entity.entityId,
                    mapOf("hvac_mode" to hvacMode), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: setClimateHvacMode ${entity.entityId} failed") }
        }
    }

    fun setBrightness(entity: HaEntityState, brightnessPercent: Int) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    "light", "turn_on", entity.entityId,
                    mapOf("brightness_pct" to brightnessPercent), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: setBrightness ${entity.entityId} failed") }
        }
    }

    fun setFanSpeed(entity: HaEntityState, percentage: Int) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    "fan", "set_percentage", entity.entityId,
                    mapOf("percentage" to percentage), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: setFanSpeed ${entity.entityId} failed") }
        }
    }

    /** position 0..100 — closed to fully open (HA cover convention). */
    fun setCoverPosition(entity: HaEntityState, position: Int) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    "cover", "set_cover_position", entity.entityId,
                    mapOf("position" to position), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: setCoverPosition ${entity.entityId} failed") }
        }
    }

    /** action: "start" | "pause" | "stop" | "return_to_base" | "locate". */
    fun vacuumAction(entity: HaEntityState, action: String) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService("vacuum", action, entity.entityId, sourceDeviceId = entity.sourceDeviceId)
            }.onFailure { Timber.e(it, "BtDashboard: vacuumAction ${entity.entityId}/$action failed") }
        }
    }

    fun setNumberValue(entity: HaEntityState, value: Double) {
        val svc = _service.value ?: return
        val domain = entity.entityId.substringBefore(".")  // "input_number" or "number"
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    domain, "set_value", entity.entityId,
                    mapOf("value" to value), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: setNumberValue ${entity.entityId} failed") }
        }
    }

    fun selectOption(entity: HaEntityState, option: String) {
        val svc = _service.value ?: return
        val domain = entity.entityId.substringBefore(".")
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    domain, "select_option", entity.entityId,
                    mapOf("option" to option), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: selectOption ${entity.entityId} failed") }
        }
    }

    fun setHumidity(entity: HaEntityState, humidity: Int) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService(
                    "humidifier", "set_humidity", entity.entityId,
                    mapOf("humidity" to humidity), entity.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtDashboard: setHumidity ${entity.entityId} failed") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { context.unbindService(serviceConn) }
        // Service keeps running in background — do NOT stop it here
    }
}
