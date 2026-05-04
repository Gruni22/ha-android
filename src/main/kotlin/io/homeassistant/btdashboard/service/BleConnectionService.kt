package io.homeassistant.btdashboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import io.homeassistant.btdashboard.bluetooth.BluetoothTransport
import io.homeassistant.btdashboard.config.BtConfig
import io.homeassistant.btdashboard.dashboard.BluetoothDashboardClient
import io.homeassistant.btdashboard.sync.SyncManager
import io.homeassistant.btdashboard.dashboard.HaArea
import io.homeassistant.btdashboard.dashboard.HaDashboardInfo
import io.homeassistant.btdashboard.dashboard.HaEntityState
import io.homeassistant.btdashboard.dashboard.HaPacketClient
import io.homeassistant.btdashboard.dashboard.HaView
import io.homeassistant.btdashboard.db.AppDatabase
import io.homeassistant.btdashboard.db.EntityEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class BleConnectionService : Service() {

    private lateinit var client: HaPacketClient
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    // Set by forceResync() to make the next reconnect run a full sync regardless
    // of DB state. Reset once the sync completes.
    @Volatile private var forceSyncOnNextConnect: Boolean = false
    // Channel that the resync waits on to learn whether the post-reconnect sync succeeded
    private var resyncResultChannel: kotlinx.coroutines.channels.Channel<io.homeassistant.btdashboard.sync.SyncResult>? = null

    private val _connectionState = MutableStateFlow(BluetoothDashboardClient.State.DISCONNECTED)
    val connectionState: StateFlow<BluetoothDashboardClient.State> = _connectionState

    private val _stateChanges = MutableSharedFlow<HaEntityState>(extraBufferCapacity = 64)
    val stateChanges: SharedFlow<HaEntityState> = _stateChanges

    // Entities filtered by the current phone-side view selection. Drives the
    // phone dashboard. DO NOT use this from Android Auto — AA needs its own
    // independent dashboard, see [allEntities] below.
    private val _entities = MutableStateFlow<List<HaEntityState>>(emptyList())
    val entities: StateFlow<List<HaEntityState>> = _entities

    // Every entity in the DB for the active device, regardless of which view
    // the phone has open. Android Auto observes this so AA's dashboard filter
    // is independent from the phone's view selection.
    private val _allEntities = MutableStateFlow<List<HaEntityState>>(emptyList())
    val allEntities: StateFlow<List<HaEntityState>> = _allEntities

    private val _areas = MutableStateFlow<List<HaArea>>(emptyList())
    val areas: StateFlow<List<HaArea>> = _areas

    private val _entityAreaMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val entityAreaMap: StateFlow<Map<String, String>> = _entityAreaMap

    private val _dashboards = MutableStateFlow<List<HaDashboardInfo>>(emptyList())
    val dashboards: StateFlow<List<HaDashboardInfo>> = _dashboards

    private val _activeDashboardIndex = MutableStateFlow(0)
    val activeDashboardIndex: StateFlow<Int> = _activeDashboardIndex

    private val _views = MutableStateFlow<List<HaView>>(emptyList())
    val views: StateFlow<List<HaView>> = _views

    private val _activeViewIndex = MutableStateFlow(0)
    val activeViewIndex: StateFlow<Int> = _activeViewIndex

    inner class BleConnectionBinder : Binder() {
        fun getService(): BleConnectionService = this@BleConnectionService
    }

    private val binder = BleConnectionBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        client = HaPacketClient(this)
        db = AppDatabase.getInstance(this)
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(false))

        scope.launch {
            client.state.collect { haState ->
                val mapped = when (haState) {
                    HaPacketClient.State.DISCONNECTED -> BluetoothDashboardClient.State.DISCONNECTED
                    HaPacketClient.State.CONNECTING   -> BluetoothDashboardClient.State.CONNECTING
                    HaPacketClient.State.CONNECTED    -> BluetoothDashboardClient.State.CONNECTED
                    HaPacketClient.State.ERROR        -> BluetoothDashboardClient.State.ERROR
                }
                _connectionState.value = mapped
                val nm = getSystemService<NotificationManager>()
                nm?.notify(NOTIFICATION_ID, buildNotification(haState == HaPacketClient.State.CONNECTED))
            }
        }

        scope.launch {
            client.stateChanges.collect { update ->
                val entity = HaEntityState(
                    entityId   = update.entityId,
                    state      = update.state,
                    attributes = update.attributes,
                )
                _stateChanges.emit(entity)
                // Update phone-side filtered view if entity is currently shown
                val current = _entities.value
                if (current.any { it.entityId == update.entityId }) {
                    _entities.value = current.map { if (it.entityId == update.entityId) entity else it }
                }
                // Always update the unfiltered _allEntities so AA sees live state
                val all = _allEntities.value
                _allEntities.value = if (all.any { it.entityId == update.entityId }) {
                    all.map { if (it.entityId == update.entityId) entity else it }
                } else {
                    all + entity
                }
            }
        }

        scope.launch {
            _connectionState.drop(1).collect { state ->
                if (state == BluetoothDashboardClient.State.ERROR && connectJob?.isActive != true) {
                    Timber.i("BleService: connection error, reconnecting in 5s")
                    delay(5_000)
                    if (connectJob?.isActive != true) connect()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (connectJob?.isActive != true) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        client.disconnect()
        super.onDestroy()
    }

    fun connect() {
        val config = BtConfig(applicationContext)
        val device = config.activeDevice ?: run { Timber.w("BleService: no active device"); return }
        val address = device.address
        val passcode = device.passcode.takeIf { it != 0 } ?: run { Timber.w("BleService: no passcode"); return }
        val transport = device.transport

        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    if (attempt > 1) {
                        // Longer backoffs reduce Android BLE stack stress: rapid
                        // connect/disconnect cycles destabilise the stack so much
                        // that notifications stop being delivered.
                        val delayMs = when (attempt) { 2 -> 5_000L; 3 -> 15_000L; 4 -> 30_000L; else -> 60_000L }
                        delay(delayMs)
                    }
                    Timber.i("BleService: connect attempt $attempt to $address")
                    client.connect(address, passcode, transport)

                    // Auto-sync if the DB has no data (e.g. first connect, or after remove+re-add)
                    // OR if "Sync now" set the force flag.
                    val deviceId = device.id
                    val forceSync = forceSyncOnNextConnect
                    val needsSync = forceSync || db.areaDao().getAll(deviceId).isEmpty()
                    if (needsSync) {
                        forceSyncOnNextConnect = false
                        Timber.i("BleService: starting sync (force=$forceSync)")
                        val result = SyncManager(client, db, deviceId).performInitialSync()
                        Timber.i("BleService: sync done: $result")
                        // Notify any waiting forceResync() caller
                        resyncResultChannel?.trySend(result)
                        resyncResultChannel = null
                        if (result is io.homeassistant.btdashboard.sync.SyncResult.Error) {
                            throw java.io.IOException("Sync failed: ${result.message}")
                        }
                    } else {
                        // Send ACK so the Pi sets _connected=True and can push STATE_CHANGEs
                        client.sendAck()
                    }

                    loadStaticDataFromDb()
                    attempt = 0  // reset only after a fully successful connect+sync+load
                    Timber.i("BleService: ready — ${_entities.value.size} entities")

                    // Keep alive: suspend until the BLE connection drops
                    client.state.first { it != HaPacketClient.State.CONNECTED }
                    Timber.i("BleService: connection lost — will reconnect")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e  // structured concurrency: must propagate
                } catch (e: Exception) {
                    Timber.e(e, "BleService: attempt $attempt failed")
                } finally {
                    client.disconnect()
                }
            }
        }
    }

    private suspend fun loadStaticDataFromDb() {
        val deviceId = BtConfig(applicationContext).activeDevice?.id ?: return

        _areas.value = db.areaDao().getAll(deviceId).map { a ->
            HaArea(id = a.id, name = a.name, icon = a.icon ?: "")
        }
        val dbEntities = db.entityDao().getAll(deviceId)
        _entityAreaMap.value = dbEntities
            .mapNotNull { e -> e.areaId?.let { e.id to it } }
            .toMap()
        // _allEntities tracks the full DB so consumers (Android Auto) can apply
        // their own filter independent of phone-side view selection.
        _allEntities.value = dbEntities.map { it.toHaEntityState() }

        val dbDashboards = db.dashboardDao().getAll(deviceId)
        val allDashboards = dbDashboards.map { d ->
            val dbViews = db.viewDao().getByDashboard(deviceId, d.id)
            HaDashboardInfo(
                id = d.id,
                urlPath = d.urlPath,
                title = d.title,
                views = dbViews.map { v ->
                    val ids = parseJsonArray(v.entityIdsJson)
                    HaView(id = v.id, path = v.path, title = v.title, entityIds = ids)
                },
            )
        }
        _dashboards.value = allDashboards
        _activeDashboardIndex.value = 0

        val firstDash = allDashboards.firstOrNull()
        if (firstDash != null) {
            _views.value = firstDash.views
            _activeViewIndex.value = 0
            loadEntitiesForView(deviceId, firstDash.views.firstOrNull())
        } else {
            // No dashboards/views at all — show all DB entities so the dashboard isn't empty
            _entities.value = db.entityDao().getAll(deviceId).map { it.toHaEntityState() }
        }
    }

    private suspend fun loadEntitiesForView(deviceId: String, view: HaView?) {
        when {
            // No view at all (dashboard has no views configured) → show all entities
            // so the user sees something on a default dashboard.
            view == null ->
                _entities.value = db.entityDao().getAll(deviceId).map { it.toHaEntityState() }
            // View exists but defines no entities → respect that, show empty.
            // (User explicitly picked this view; if it's empty in HA, it's empty here.)
            view.entityIds.isEmpty() -> _entities.value = emptyList()
            // Normal case: load just the entities the view declares.
            else ->
                _entities.value = db.entityDao().getByIds(deviceId, view.entityIds).map { it.toHaEntityState() }
        }
    }

    fun refresh() {
        scope.launch {
            val deviceId = BtConfig(applicationContext).activeDevice?.id ?: return@launch
            val view = _views.value.getOrNull(_activeViewIndex.value)
            loadEntitiesForView(deviceId, view)
        }
    }

    /**
     * Force a fresh sync from HA. Triggers a clean BLE disconnect+reconnect so the
     * Android BLE stack gives us a fresh CCCD subscription (long-lived links
     * silently lose notification delivery). Waits for the next sync to complete
     * inside the existing retry loop and returns its result.
     */
    suspend fun forceResync(): io.homeassistant.btdashboard.sync.SyncResult {
        val device = BtConfig(applicationContext).activeDevice
            ?: return io.homeassistant.btdashboard.sync.SyncResult.Error("No active device")
        Timber.i("BleService: forceResync — signalling reconnect+sync")
        val resultCh = kotlinx.coroutines.channels.Channel<io.homeassistant.btdashboard.sync.SyncResult>(capacity = 1)
        resyncResultChannel = resultCh
        forceSyncOnNextConnect = true
        // Drop the BLE link — the existing connectJob's catch+finally will reconnect
        // and (because of forceSyncOnNextConnect) run a fresh sync.
        client.disconnect()
        // Wait up to 30s for the sync to complete (reconnect ~3s + sync ~3-5s)
        val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) { resultCh.receive() }
            ?: return io.homeassistant.btdashboard.sync.SyncResult.Error("Resync timeout (30s)")
        if (result is io.homeassistant.btdashboard.sync.SyncResult.Success) loadStaticDataFromDb()
        return result
    }

    fun selectDashboard(index: Int) {
        val dashList = _dashboards.value
        if (index !in dashList.indices || index == _activeDashboardIndex.value) return
        _activeDashboardIndex.value = index
        _activeViewIndex.value = 0
        scope.launch {
            val deviceId = BtConfig(applicationContext).activeDevice?.id ?: return@launch
            val dash = dashList[index]
            _views.value = dash.views
            loadEntitiesForView(deviceId, dash.views.firstOrNull())
        }
    }

    fun selectView(index: Int) {
        val viewList = _views.value
        if (index !in viewList.indices || index == _activeViewIndex.value) return
        _activeViewIndex.value = index
        scope.launch {
            val deviceId = BtConfig(applicationContext).activeDevice?.id ?: return@launch
            loadEntitiesForView(deviceId, viewList[index])
        }
    }

    suspend fun callService(domain: String, service: String, entityId: String, data: Map<String, Any?> = emptyMap()) {
        val cleanData = data.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        client.callService(domain, service, entityId, cleanData)
    }

    private fun parseJsonArray(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    private fun EntityEntity.toHaEntityState(): HaEntityState {
        val attrs = try {
            val jo = JSONObject(attributesJson)
            jo.keys().asSequence().associateWith<String, Any?> { jo.get(it) }
        } catch (e: Exception) { emptyMap() }
        return HaEntityState(
            entityId   = id,
            state      = state,
            attributes = attrs,
            areaId     = areaId,
        )
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HA Bluetooth Verbindung",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Permanente Verbindung zu Home Assistant via BLE" }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(connected: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Home Assistant Bluetooth")
            .setContentText(if (connected) "Verbunden mit Home Assistant" else "Verbinde mit Home Assistant...")
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        const val CHANNEL_ID = "ble_connection"
        const val NOTIFICATION_ID = 1001
    }
}
