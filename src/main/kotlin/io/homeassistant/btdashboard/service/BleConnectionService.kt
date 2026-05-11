package io.github.gruni22.btdashboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import io.github.gruni22.btdashboard.config.BtConfig
import io.github.gruni22.btdashboard.config.DeviceConfig
import io.github.gruni22.btdashboard.dashboard.BluetoothDashboardClient
import io.github.gruni22.btdashboard.dashboard.HaArea
import io.github.gruni22.btdashboard.dashboard.HaDashboardInfo
import io.github.gruni22.btdashboard.dashboard.HaEntityState
import io.github.gruni22.btdashboard.dashboard.HaPacketClient
import io.github.gruni22.btdashboard.dashboard.HaView
import io.github.gruni22.btdashboard.db.AppDatabase
import io.github.gruni22.btdashboard.db.EntityEntity
import io.github.gruni22.btdashboard.sync.SyncManager
import io.github.gruni22.btdashboard.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Foreground service that holds one *parallel* BLE link per configured
 * gateway. Every device the user has set up gets its own [HaPacketClient]
 * with its own connect-retry loop. The service exposes UNION views across
 * all gateways so the dashboard, Settings screen and Android Auto don't have
 * to know how many gateways are active — they just observe the merged flows.
 *
 * - Per-device state lives in [Session]; reads on the hot path go through
 *   [_allEntities], populated by every gateway's state-change collector.
 * - Service calls (toggle, set_value, …) route by `entityId` to the session
 *   that owns it (see [sessionFor]); both gateways serving the same HA
 *   instance is fine — either route succeeds because HA executes the
 *   service call once regardless of which BLE bridge delivered it.
 * - Connection state is "any device connected" → CONNECTED, "any error and
 *   none connected" → ERROR, otherwise CONNECTING / DISCONNECTED.
 */
class BleConnectionService : Service() {

    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Per-device runtime state. Keyed by `DeviceConfig.id`. */
    private class Session(
        val device: DeviceConfig,
        val client: HaPacketClient,
        @Volatile var job: Job? = null,
        @Volatile var forceSyncOnNextConnect: Boolean = false,
        @Volatile var resyncResultChannel: Channel<SyncResult>? = null,
    )

    private val sessions = mutableMapOf<String, Session>()
    private val sessionsLock = Any()

    /**
     * `(entityId, sourceDeviceId) → Session` lookup for service-call routing.
     * Compound key because two HA instances behind two gateways can legitimately
     * expose the same `entity_id` (e.g. `light.kitchen`) — they're different
     * physical entities, so we must remember which gateway each tile came
     * through and route the call back accordingly. Populated by per-session
     * state-change collectors and by `refreshAggregates()`'s DB load.
     */
    private val entityOwners = mutableMapOf<Pair<String, String>, String>()

    // ── Aggregate flows ────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(BluetoothDashboardClient.State.DISCONNECTED)
    val connectionState: StateFlow<BluetoothDashboardClient.State> = _connectionState

    private val _stateChanges = MutableSharedFlow<HaEntityState>(extraBufferCapacity = 64)
    val stateChanges: SharedFlow<HaEntityState> = _stateChanges

    /** Entities filtered by the current phone-side view selection. */
    private val _entities = MutableStateFlow<List<HaEntityState>>(emptyList())
    val entities: StateFlow<List<HaEntityState>> = _entities

    /** Every entity across every gateway. Android Auto reads this. */
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
        db = AppDatabase.getInstance(this)
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(connected = false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        synchronized(sessionsLock) {
            sessions.values.forEach { runCatching { it.client.disconnect() } }
            sessions.clear()
        }
        super.onDestroy()
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * Spin up a session per configured device that doesn't already have one.
     * Sessions for removed devices get torn down. Idempotent.
     */
    fun connect() {
        val configured = BtConfig(applicationContext).devices
        val configuredIds = configured.map { it.id }.toSet()

        synchronized(sessionsLock) {
            // Tear down sessions whose device is gone from BtConfig.
            val obsolete = sessions.keys - configuredIds
            for (id in obsolete) {
                Timber.i("BleService: tearing down obsolete session $id")
                sessions.remove(id)?.let {
                    it.job?.cancel()
                    runCatching { it.client.disconnect() }
                }
            }
            // Start sessions for newly-added devices.
            for (device in configured) {
                if (device.passcode == 0) {
                    Timber.w("BleService: device ${device.id} has no passcode, skipping")
                    continue
                }
                if (sessions[device.id]?.job?.isActive == true) continue
                startSession(device)
            }
        }

        // Re-aggregate dashboards/areas after a (possibly) changed device set.
        scope.launch { refreshAggregates() }
    }

    private fun startSession(device: DeviceConfig) {
        val client = HaPacketClient(this)
        val session = Session(device, client)
        sessions[device.id] = session

        // Per-session state-change collector. Flows fan into the service-level
        // `_allEntities` and `_stateChanges`; entityOwner is updated so future
        // service calls route to this gateway.
        scope.launch {
            client.stateChanges.collect { update ->
                val entity = HaEntityState(
                    entityId = update.entityId,
                    state = update.state,
                    attributes = update.attributes,
                    sourceDeviceId = device.id,
                )
                entityOwners[entity.entityId to device.id] = device.id
                _stateChanges.emit(entity)
                mergeEntityIntoAggregates(entity)
            }
        }

        // Map per-session connection state into the aggregate connection flow.
        scope.launch {
            client.state.collect { _ -> recomputeConnectionState() }
        }

        session.job = scope.launch { runConnectLoop(session) }
    }

    /**
     * Connect retry loop for a single session. Mirrors the original single-
     * device behaviour: exponential-ish backoff that resets after a fully
     * successful connect+sync cycle.
     */
    private suspend fun runConnectLoop(session: Session) {
        val device = session.device
        val client = session.client
        var attempt = 0

        while (true) {
            attempt++
            try {
                if (attempt > 1) {
                    val delayMs = when (attempt) {
                        2 -> 5_000L; 3 -> 15_000L; 4 -> 30_000L
                        else -> 60_000L
                    }
                    delay(delayMs)
                }
                Timber.i("BleService[${device.id}]: connect attempt $attempt to ${device.address}")
                client.connect(device.address, device.passcode, device.transport)

                val needsSync = session.forceSyncOnNextConnect ||
                    db.areaDao().getAll(device.id).isEmpty()
                if (needsSync) {
                    session.forceSyncOnNextConnect = false
                    Timber.i("BleService[${device.id}]: starting sync")
                    val result = SyncManager(client, db, device.id).performInitialSync()
                    Timber.i("BleService[${device.id}]: sync done: $result")
                    session.resyncResultChannel?.trySend(result)
                    session.resyncResultChannel = null
                    if (result is SyncResult.Error) {
                        throw java.io.IOException("Sync failed: ${result.message}")
                    }
                } else {
                    client.sendAck()
                }

                refreshAggregates()
                attempt = 0
                Timber.i("BleService[${device.id}]: ready")

                client.state.first { it != HaPacketClient.State.CONNECTED }
                Timber.i("BleService[${device.id}]: connection lost — will reconnect")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "BleService[${device.id}]: attempt $attempt failed")
            } finally {
                runCatching { client.disconnect() }
            }
        }
    }

    // ── Aggregation ───────────────────────────────────────────────────────────

    /**
     * Re-load DB rows for every active session and rebuild the aggregate
     * StateFlows (entities / areas / dashboards / entityAreaMap). Called after
     * sync, after a session is added/removed, and at boot.
     */
    private suspend fun refreshAggregates() {
        val deviceIds = synchronized(sessionsLock) { sessions.keys.toList() }

        // Compound-keyed: each (entityId, sourceDeviceId) is a *separate*
        // entity. Same entityId from two gateways = two entries (could be
        // same HA seen twice → cosmetically redundant, but always semantically
        // correct; could be two HAs with overlapping ids → must stay split).
        val mergedEntities = mutableMapOf<Pair<String, String>, HaEntityState>()
        val mergedAreas = mutableMapOf<String, HaArea>()
        val mergedAreaMap = mutableMapOf<String, String>()
        val mergedDashboards = mutableMapOf<String, HaDashboardInfo>()

        for (deviceId in deviceIds) {
            // Areas
            db.areaDao().getAll(deviceId).forEach { a ->
                mergedAreas.putIfAbsent(a.id, HaArea(id = a.id, name = a.name, icon = a.icon ?: ""))
            }
            // Entities — keep one entry per (entityId, deviceId).
            val rows = db.entityDao().getAll(deviceId)
            for (e in rows) {
                val key = e.id to deviceId
                if (mergedEntities[key] == null) {
                    mergedEntities[key] = e.toHaEntityState().copy(sourceDeviceId = deviceId)
                }
                entityOwners[key] = deviceId
                e.areaId?.let { mergedAreaMap[e.id] = it }
            }
            // Dashboards (DASH_* labels — same id from two HA instances means
            // the same logical dashboard, so dedupe by id and union views'
            // entity_ids).
            db.dashboardDao().getAll(deviceId).forEach { d ->
                val dbViews = db.viewDao().getByDashboard(deviceId, d.id)
                val current = mergedDashboards[d.id]
                val newViews = dbViews.map { v ->
                    HaView(
                        id = v.id, path = v.path, title = v.title,
                        entityIds = parseJsonArray(v.entityIdsJson),
                    )
                }
                mergedDashboards[d.id] = if (current == null) {
                    HaDashboardInfo(
                        id = d.id, urlPath = d.urlPath, title = d.title, views = newViews,
                    )
                } else {
                    // Merge views: same view id = union of entity_ids.
                    val viewMap = current.views.associateBy { it.id }.toMutableMap()
                    for (v in newViews) {
                        val existing = viewMap[v.id]
                        viewMap[v.id] = if (existing == null) v
                        else existing.copy(entityIds = (existing.entityIds + v.entityIds).distinct())
                    }
                    current.copy(views = viewMap.values.toList())
                }
            }
        }

        // Preserve any live state-change updates that happened since the
        // session's last DB sync (mergedEntities is from DB, _allEntities has
        // the live state).
        val live = _allEntities.value.associateBy { it.entityId to it.sourceDeviceId }
        val combined = mergedEntities.values.map { dbEntity ->
            live[dbEntity.entityId to dbEntity.sourceDeviceId] ?: dbEntity
        } + live.values.filter { (it.entityId to it.sourceDeviceId) !in mergedEntities }

        _allEntities.value = combined
        _areas.value = mergedAreas.values.toList()
        _entityAreaMap.value = mergedAreaMap
        _dashboards.value = mergedDashboards.values.sortedBy { it.title.lowercase() }
        _activeDashboardIndex.value = _activeDashboardIndex.value.coerceIn(0, (_dashboards.value.size - 1).coerceAtLeast(0))
        _views.value = _dashboards.value.getOrNull(_activeDashboardIndex.value)?.views ?: emptyList()
        _activeViewIndex.value = _activeViewIndex.value.coerceIn(0, (_views.value.size - 1).coerceAtLeast(0))
        loadEntitiesForView(_views.value.getOrNull(_activeViewIndex.value))
    }

    /**
     * Live state-change update fan-in. Updates the entry whose `(entityId,
     * sourceDeviceId)` matches; appends a new compound entry if not seen
     * before. Same entityId from a different gateway is *not* the same row.
     */
    private fun mergeEntityIntoAggregates(entity: HaEntityState) {
        fun matches(e: HaEntityState) =
            e.entityId == entity.entityId && e.sourceDeviceId == entity.sourceDeviceId

        val all = _allEntities.value
        _allEntities.value = if (all.any(::matches)) {
            all.map { if (matches(it)) entity else it }
        } else {
            all + entity
        }
        val current = _entities.value
        if (current.any(::matches)) {
            _entities.value = current.map { if (matches(it)) entity else it }
        }
    }

    private fun loadEntitiesForView(view: HaView?) {
        val all = _allEntities.value
        _entities.value = when {
            view == null -> all
            view.entityIds.isEmpty() -> emptyList()
            else -> {
                val wanted = view.entityIds.toHashSet()
                all.filter { it.entityId in wanted }
            }
        }
    }

    /**
     * Aggregate connection state across sessions:
     * - any session CONNECTED → CONNECTED
     * - else any AUTHENTICATING → AUTHENTICATING (treated as CONNECTING here)
     * - else any CONNECTING → CONNECTING
     * - else any ERROR → ERROR
     * - else DISCONNECTED
     */
    private fun recomputeConnectionState() {
        val states = synchronized(sessionsLock) {
            sessions.values.map { it.client.state.value }
        }
        val mapped = when {
            HaPacketClient.State.CONNECTED in states   -> BluetoothDashboardClient.State.CONNECTED
            HaPacketClient.State.CONNECTING in states  -> BluetoothDashboardClient.State.CONNECTING
            HaPacketClient.State.ERROR in states       -> BluetoothDashboardClient.State.ERROR
            else -> BluetoothDashboardClient.State.DISCONNECTED
        }
        _connectionState.value = mapped
        getSystemService<NotificationManager>()
            ?.notify(NOTIFICATION_ID, buildNotification(mapped == BluetoothDashboardClient.State.CONNECTED))
    }

    // ── UI selection ──────────────────────────────────────────────────────────

    fun selectDashboard(index: Int) {
        val dashList = _dashboards.value
        if (index !in dashList.indices || index == _activeDashboardIndex.value) return
        _activeDashboardIndex.value = index
        _activeViewIndex.value = 0
        val dash = dashList[index]
        _views.value = dash.views
        loadEntitiesForView(dash.views.firstOrNull())
    }

    fun selectView(index: Int) {
        val viewList = _views.value
        if (index !in viewList.indices || index == _activeViewIndex.value) return
        _activeViewIndex.value = index
        loadEntitiesForView(viewList[index])
    }

    fun refresh() {
        loadEntitiesForView(_views.value.getOrNull(_activeViewIndex.value))
    }

    // ── Service-call routing ─────────────────────────────────────────────────

    /**
     * Pick the session that owns the (entityId, sourceDeviceId) pair. If no
     * sourceDeviceId is given (legacy callers), fall back to any session
     * that has reported this entityId, then to any connected session.
     */
    private fun sessionFor(entityId: String, sourceDeviceId: String?): Session? {
        synchronized(sessionsLock) {
            // Exact match: caller knows which gateway the tile came from.
            if (!sourceDeviceId.isNullOrEmpty()) {
                sessions[sourceDeviceId]?.let { return it }
            }
            // Fallback: any gateway that ever reported this entityId.
            val anyOwner = entityOwners.entries
                .firstOrNull { it.key.first == entityId }
                ?.value
            anyOwner?.let { sessions[it] }?.let { return it }
            return sessions.values.firstOrNull { it.client.state.value == HaPacketClient.State.CONNECTED }
                ?: sessions.values.firstOrNull()
        }
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        data: Map<String, Any?> = emptyMap(),
        sourceDeviceId: String? = null,
    ) {
        val cleanData = data.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        val session = sessionFor(entityId, sourceDeviceId) ?: run {
            Timber.w("BleService: callService($entityId) — no session available")
            return
        }
        session.client.callService(domain, service, entityId, cleanData)
    }

    // ── Resync ────────────────────────────────────────────────────────────────

    /**
     * Force a fresh sync. With multiple gateways, default behaviour is to
     * resync every connected one in parallel and return a combined result
     * (Success if all succeed, first Error otherwise). [deviceId] narrows
     * to a single device — used by the per-device "Resync" buttons in
     * Settings.
     */
    suspend fun forceResync(deviceId: String? = null): SyncResult {
        val targets: List<Session> = synchronized(sessionsLock) {
            if (deviceId != null) listOfNotNull(sessions[deviceId]) else sessions.values.toList()
        }
        if (targets.isEmpty()) return SyncResult.Error("No gateway sessions")

        val results = mutableListOf<SyncResult>()
        for (session in targets) {
            Timber.i("BleService[${session.device.id}]: forceResync — signalling reconnect+sync")
            val ch = Channel<SyncResult>(capacity = 1)
            session.resyncResultChannel = ch
            session.forceSyncOnNextConnect = true
            runCatching { session.client.disconnect() }
            val r = withTimeoutOrNull(30_000L) { ch.receive() }
                ?: SyncResult.Error("Resync timeout (30s) for ${session.device.id}")
            results += r
        }
        refreshAggregates()
        // Combine: any error becomes the result, else aggregate counts.
        return results.firstOrNull { it is SyncResult.Error }
            ?: SyncResult.Success(
                areasCount = results.filterIsInstance<SyncResult.Success>().sumOf { it.areasCount },
                entitiesCount = results.filterIsInstance<SyncResult.Success>().sumOf { it.entitiesCount },
                dashboardsCount = results.filterIsInstance<SyncResult.Success>().sumOf { it.dashboardsCount },
            )
    }

    /**
     * Kept for backwards source compatibility with old Settings code that
     * calls it after switching the active device. With multi-instance the
     * concept of "active device" is gone — every configured device runs in
     * parallel — so this just (re-)reconciles sessions with BtConfig.
     */
    fun applyActiveDeviceChange() {
        scope.launch { connect() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
            entityId = id,
            state = state,
            attributes = attrs,
            areaId = areaId,
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
