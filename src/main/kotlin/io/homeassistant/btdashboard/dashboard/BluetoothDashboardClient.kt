package io.github.gruni22.btdashboard.dashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.getSystemService
import io.github.gruni22.btdashboard.bluetooth.BluetoothTransport
import io.github.gruni22.btdashboard.bluetooth.FrameProtocol
import io.github.gruni22.btdashboard.bluetooth.openBluetoothTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

@SuppressLint("MissingPermission")
class BluetoothDashboardClient(context: Context) {

    private val adapter: BluetoothAdapter? = context.getSystemService<BluetoothManager>()?.adapter
    private val appContext: Context = context.applicationContext
    private var transport: FrameProtocol? = null
    private var scope = freshScope()
    private var receiveJob: Job? = null

    private val msgId = AtomicInteger(1)
    private val pending = mutableMapOf<Int, CompletableDeferred<JSONObject>>()
    private val _stateChanges = MutableSharedFlow<HaEntityState>(extraBufferCapacity = 64)
    val stateChanges: SharedFlow<HaEntityState> = _stateChanges

    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, ERROR, INVALID_AUTH }
    private val _connectionState = MutableSharedFlow<State>(replay = 1, extraBufferCapacity = 4)
    val connectionState: SharedFlow<State> = _connectionState

    private fun freshScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun connect(
        deviceAddress: String,
        accessToken: String,
        transportType: BluetoothTransport = BluetoothTransport.BLE,
    ) {
        // Close any existing transport BEFORE opening a new one.
        // If we skip this and the old BLE connection is still up, Android's BLE stack reuses
        // the same underlying ACL link for the new connectGatt() call. The ESP32 never fires
        // onConnect() again, gSubscribeNotified stays true, and client_connected is never sent
        // to Home Assistant — so auth_required never arrives and we time out after 30 s.
        transport?.let { old ->
            transport = null
            receiveJob?.cancel()
            receiveJob = null
            runCatching { old.close() }
            delay(800L)  // let the BLE stack process the disconnect before reconnecting
        }

        // Recreate scope if a previous disconnect() cancelled it
        if (!scope.isActive) scope = freshScope()
        // Reset message counter and discard stale auth messages from previous attempt
        msgId.set(1)
        pending.clear()
        while (true) { authMessages.tryReceive().getOrNull() ?: break }

        _connectionState.emit(State.CONNECTING)
        val adp = adapter ?: run {
            _connectionState.emit(State.ERROR)
            throw IOException("No Bluetooth adapter")
        }
        val t = openBluetoothTransport(appContext, adp, deviceAddress, transportType)
        transport = t

        _connectionState.emit(State.AUTHENTICATING)
        startReceiveLoop(t)

        // 25 s: covers the 2.5 s Pi delay + WS setup + auth_required delivery.
        // Pi delays opening WS by 2.5 s after client_connected to ensure Android's
        // setCharacteristicNotification is registered before auth_required arrives.
        Timber.d("BtDashboard: waiting for auth_required")
        val authRequired = withTimeout(25_000) { readExpected("auth_required") }
        Timber.d("BtDashboard: auth_required received (HA ${authRequired.optString("ha_version")}), sending auth")

        val authFrame = JSONObject().apply {
            put("type", "auth")
            put("access_token", accessToken)
        }.toString().toByteArray()
        t.writeFrame(authFrame)
        Timber.d("BtDashboard: auth sent, waiting for auth_ok")

        // Pi may reconnect its HA WebSocket mid-auth (HA's 10 s auth timeout fires before
        // our write arrives over BLE). Each reconnect re-sends auth_required; we must
        // re-send auth each time so HA eventually accepts it and replies auth_ok.
        val authResult = withTimeout(25_000) {
            while (true) {
                val msg = authMessages.receive()
                when (msg.optString("type")) {
                    "_ble_disconnected" -> throw IOException("BLE disconnected during auth")
                    "auth_required"     -> {
                        Timber.d("BtDashboard: new auth_required — re-sending auth (Pi WS reconnect)")
                        t.writeFrame(authFrame)
                    }
                    "auth_ok", "auth_invalid" -> return@withTimeout msg
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        if (authResult.getString("type") == "auth_invalid") {
            _connectionState.emit(State.INVALID_AUTH)
            throw IOException("BtDashboard: auth rejected")
        }
        _connectionState.emit(State.CONNECTED)
        Timber.i("BtDashboard: authenticated — pausing 800 ms before subscribe")
        delay(800)
        subscribeEvents()
    }

    suspend fun getStates(): List<HaEntityState> {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        transport?.writeFrame(JSONObject().apply { put("id", id); put("type", "get_states") }.toString().toByteArray())
        val response = withTimeout(30_000) { deferred.await() }
        if (!response.optBoolean("success", false)) throw IOException("get_states failed: ${response.optString("error")}")
        return parseStates(response.getJSONArray("result")).also { Timber.i("BtDashboard: ${it.size} entities") }
    }

    suspend fun callService(domain: String, service: String, entityId: String, serviceData: Map<String, Any?> = emptyMap()) {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "call_service")
            put("domain", domain)
            put("service", service)
            put("target", JSONObject().put("entity_id", entityId))
            if (serviceData.isNotEmpty()) put("service_data", JSONObject(serviceData))
        }
        transport?.writeFrame(msg.toString().toByteArray())
        val result = withTimeout(10_000) { deferred.await() }
        if (!result.optBoolean("success", true)) {
            val err = result.optJSONObject("error")
            throw IOException("call_service $domain.$service failed: ${err?.optString("message") ?: result}")
        }
    }

    suspend fun getAreas(): List<HaArea> {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        transport?.writeFrame(JSONObject().apply { put("id", id); put("type", "config/area_registry/list") }.toString().toByteArray())
        val response = withTimeout(10_000) { deferred.await() }
        if (!response.optBoolean("success", false)) return emptyList()
        val result = response.getJSONArray("result")
        return buildList {
            for (i in 0 until result.length()) {
                val area = result.getJSONObject(i)
                add(HaArea(id = area.getString("area_id"), name = area.getString("name"), icon = ""))
            }
        }
    }

    suspend fun getEntityAreaMap(): Map<String, String> {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        transport?.writeFrame(JSONObject().apply { put("id", id); put("type", "config/entity_registry/list") }.toString().toByteArray())
        val response = withTimeout(10_000) { deferred.await() }
        if (!response.optBoolean("success", false)) return emptyMap()
        val result = response.getJSONArray("result")
        return buildMap {
            for (i in 0 until result.length()) {
                val entry = result.getJSONObject(i)
                val areaId = entry.optString("area_id").takeIf { it.isNotBlank() } ?: continue
                put(entry.getString("entity_id"), areaId)
            }
        }
    }

    suspend fun getLoveDashboards(): List<HaDashboardInfo> {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        transport?.writeFrame(JSONObject().apply { put("id", id); put("type", "lovelace/dashboards/list") }.toString().toByteArray())
        val response = withTimeout(10_000) { deferred.await() }
        if (!response.optBoolean("success", false)) return emptyList()
        val arr = response.optJSONArray("result") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                if (!d.optBoolean("show_in_sidebar", true)) continue
                val rawPath = if (d.isNull("url_path")) null else d.optString("url_path").takeIf { it.isNotBlank() }
                add(HaDashboardInfo(
                    id = d.optString("id", "lovelace"),
                    urlPath = rawPath ?: "",
                    title = d.optString("title", "Dashboard"),
                    views = emptyList(),
                ))
            }
        }
    }

    suspend fun getLovelaceViews(urlPath: String? = null): List<HaView> {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "lovelace/config")
            put("force", false)
            if (urlPath != null) put("url_path", urlPath)
        }
        transport?.writeFrame(msg.toString().toByteArray())
        val response = withTimeout(15_000) { deferred.await() }
        if (!response.optBoolean("success", false)) return emptyList()
        val config = response.optJSONObject("result") ?: return emptyList()
        val viewsArray = config.optJSONArray("views") ?: return emptyList()
        return buildList {
            for (i in 0 until viewsArray.length()) {
                val view = viewsArray.getJSONObject(i)
                val path = view.optString("path", "view_$i")
                val title = view.optString("title", "View ${i + 1}")
                val entityIds = buildSet {
                    // Standard layout: cards directly on the view
                    view.optJSONArray("cards")?.let { addAll(extractEntityIdsFromCards(it)) }
                    // Sections layout (HA 2024+): view.sections[].cards
                    view.optJSONArray("sections")?.let { sections ->
                        for (s in 0 until sections.length()) {
                            sections.optJSONObject(s)?.optJSONArray("cards")
                                ?.let { addAll(extractEntityIdsFromCards(it)) }
                        }
                    }
                }.toList()
                if (entityIds.isNotEmpty()) add(HaView(id = "view_$i", path = path, title = title, entityIds = entityIds.toList()))
            }
        }
    }

    private fun extractEntityIdsFromCards(cards: JSONArray): Set<String> {
        val ids = mutableSetOf<String>()
        for (i in 0 until cards.length()) {
            val card = runCatching { cards.getJSONObject(i) }.getOrNull() ?: continue
            card.optString("entity").takeIf { it.isNotBlank() && it != "null" }?.let { ids += it }
            card.optJSONArray("entities")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optString(j).takeIf { it.isNotBlank() && it != "null" }?.let { ids += it }
                    arr.optJSONObject(j)?.optString("entity")?.takeIf { it.isNotBlank() && it != "null" }?.let { ids += it }
                }
            }
            card.optJSONArray("cards")?.let { ids += extractEntityIdsFromCards(it) }
        }
        return ids
    }

    suspend fun getStatesByIds(entityIds: Collection<String>): List<HaEntityState> {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "get_states")
            put("bt_filter_entity_ids", org.json.JSONArray(entityIds.toList()))
        }
        transport?.writeFrame(msg.toString().toByteArray())
        val response = withTimeout(30_000) { deferred.await() }
        if (!response.optBoolean("success", false)) throw IOException("get_states failed: ${response.optString("error")}")
        return parseStates(response.getJSONArray("result")).also { Timber.i("BtDashboard: ${it.size} entities (filtered from ${entityIds.size} requested)") }
    }

    fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        transport?.close()
        transport = null
        scope.cancel()  // scope is recreated on next connect()
        _connectionState.tryEmit(State.DISCONNECTED)
    }

    private suspend fun subscribeEvents() {
        val id = msgId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }.toString().toByteArray()

        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                Timber.d("BtDashboard: subscribe_events attempt $attempt (id=$id)")
                transport?.writeFrame(msg)
                Timber.d("BtDashboard: subscribe_events written, awaiting result")
                withTimeout(8_000) { deferred.await() }
                Timber.i("BtDashboard: subscribe_events confirmed")
                return
            } catch (e: Exception) {
                lastErr = e
                Timber.w(e, "BtDashboard: subscribe_events attempt $attempt failed")
                if (attempt < 3) delay(1_500)
            }
        }
        throw lastErr ?: IOException("subscribe_events failed")
    }

    private fun startReceiveLoop(t: FrameProtocol) {
        receiveJob = scope.launch {
            try {
                while (true) {
                    val frame = t.readFrame()
                    try {
                        handleMessage(JSONObject(String(frame)))
                    } catch (e: org.json.JSONException) {
                        Timber.w(e, "BtDashboard: malformed frame (BLE chunk loss?), skipping")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "BtDashboard: receive loop ended")
                _connectionState.emit(State.ERROR)
                // Wake any coroutine blocked in readExpected() so it fails fast
                // instead of waiting for the 30 s withTimeout.
                authMessages.trySend(JSONObject().put("type", "_ble_disconnected"))
            }
        }
    }

    // Channel instead of SharedFlow: auth messages are buffered even when no one is
    // collecting, so auth_ok arriving in the gap between writeFrame(auth) and the
    // next readExpected("auth_ok") call is never lost.
    private val authMessages = Channel<JSONObject>(Channel.UNLIMITED)

    private suspend fun readExpected(vararg types: String): JSONObject {
        while (true) {
            val msg = authMessages.receive()
            if (msg.optString("type") == "_ble_disconnected") throw IOException("BLE disconnected during auth")
            if (msg.optString("type") in types) return msg
        }
    }

    private suspend fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "auth_required", "auth_ok", "auth_invalid" -> authMessages.trySend(msg)
            "result" -> pending.remove(msg.optInt("id", -1))?.complete(msg)
            "event" -> {
                val newState = msg.optJSONObject("event")
                    ?.optJSONObject("data")
                    ?.optJSONObject("new_state") ?: return
                parseState(newState)?.let { _stateChanges.emit(it) }
            }
        }
    }

    private fun parseStates(array: JSONArray): List<HaEntityState> = buildList {
        for (i in 0 until array.length()) parseState(array.getJSONObject(i))?.let { add(it) }
    }

    private fun parseState(obj: JSONObject): HaEntityState? = runCatching {
        val attrs = obj.optJSONObject("attributes") ?: JSONObject()
        HaEntityState(
            entityId = obj.getString("entity_id"),
            state = obj.getString("state"),
            attributes = buildMap { attrs.keys().forEach { key -> put(key, attrs.get(key)) } },
            lastChanged = obj.optString("last_changed"),
        )
    }.getOrNull()
}
