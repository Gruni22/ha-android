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

    val areas: StateFlow<List<HaArea>> = _service.flatMapLatest { svc ->
        svc?.areas ?: flowOf(emptyList())
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

    private val entityAreaMap = _service.flatMapLatest { svc ->
        svc?.entityAreaMap ?: flowOf(emptyMap())
    }

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
        val isOn = entity.isActive
        val (svcDomain, service) = when (entity.domain) {
            // Cover: open/opening → close; closing → open (reverse); closed/stopped → open
            "cover" -> "cover" to when (entity.state) {
                "open", "opening" -> "close_cover"
                "closing"         -> "open_cover"
                else              -> "open_cover"  // closed, stopped
            }
            // Lock: unlocked/unlocking/opening → lock; locked/locking → unlock
            "lock"         -> "lock"         to if (isOn) "lock" else "unlock"
            "media_player" -> "media_player" to if (isOn) "media_pause" else "media_play"
            "script"       -> "script"       to "turn_on"
            else           -> entity.domain  to if (isOn) "turn_off" else "turn_on"
        }
        viewModelScope.launch {
            runCatching { svc.callService(svcDomain, service, entity.entityId) }
                .onFailure { Timber.e(it, "BtDashboard: toggle ${entity.entityId} failed") }
        }
    }

    // brightnessPercent: 0-100 (matches HA brightness_pct service param)
    fun setBrightness(entityId: String, brightnessPercent: Int) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService("light", "turn_on", entityId, mapOf("brightness_pct" to brightnessPercent))
            }.onFailure { Timber.e(it, "BtDashboard: setBrightness $entityId failed") }
        }
    }

    // percentage: 0-100 (matches HA fan.set_percentage service param)
    fun setFanSpeed(entityId: String, percentage: Int) {
        val svc = _service.value ?: return
        viewModelScope.launch {
            runCatching {
                svc.callService("fan", "set_percentage", entityId, mapOf("percentage" to percentage))
            }.onFailure { Timber.e(it, "BtDashboard: setFanSpeed $entityId failed") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { context.unbindService(serviceConn) }
        // Service keeps running in background — do NOT stop it here
    }
}
