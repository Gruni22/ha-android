package io.homeassistant.btdashboard.dashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.getSystemService
import io.homeassistant.btdashboard.bluetooth.BluetoothTransport
import io.homeassistant.btdashboard.bluetooth.openBluetoothTransport
import io.homeassistant.btdashboard.config.BtConfig
import io.homeassistant.btdashboard.protocol.PacketCodec
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Connects to HA via BLE using the passcode-secured packet protocol.
 *
 * No bonding/pairing is required. Every packet carries the shared 32-bit passcode.
 * Incoming STATE_CHANGE pushes are emitted on [stateChanges].
 */
@SuppressLint("MissingPermission")
class HaPacketClient(private val context: Context) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: io.homeassistant.btdashboard.bluetooth.FrameProtocol? = null
    private var receiveJob: Job? = null

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _stateChanges = MutableSharedFlow<EntityStateUpdate>(extraBufferCapacity = 64)
    val stateChanges: SharedFlow<EntityStateUpdate> = _stateChanges

    // Pending response channels keyed by expected answer cmd
    private val pendingResponses = mutableMapOf<Byte, Channel<PacketCodec.Packet>>()

    private var passcode: Int = 0

    suspend fun connect(address: String, passcode: Int, bluetoothTransport: BluetoothTransport = BluetoothTransport.BLE) {
        // Cancel any stale connection before starting a new one
        receiveJob?.cancel()
        transport?.close()
        transport = null

        this.passcode = passcode
        _state.value = State.CONNECTING
        val adapter = context.getSystemService<BluetoothManager>()?.adapter
            ?: throw IOException("No Bluetooth adapter")
        val t = openBluetoothTransport(context, adapter, address, bluetoothTransport)
        transport = t
        _state.value = State.CONNECTED
        receiveJob = scope.launch { receiveLoop(t) }
    }

    fun disconnect() {
        receiveJob?.cancel()
        transport?.close()
        transport = null
        _state.value = State.DISCONNECTED
    }

    suspend fun sendAck() {
        Timber.d("APP→PI: cmd=0x01 (ACK, 14 bytes)")
        transport?.writeFrame(PacketCodec.encode(passcode, PacketCodec.CMD_ACK))
    }

    // ── High-level request/response ───────────────────────────────────────────

    suspend fun requestAreas(): List<HaArea> {
        val json = requestResponse(PacketCodec.CMD_REQ_AREAS, null, PacketCodec.CMD_ANS_AREAS)
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HaArea(o.getString("id"), o.getString("name"), o.optString("icon", ""))
        }
    }

    suspend fun requestDevices(areaId: String? = null): List<HaEntityState> {
        val payload = if (areaId != null) """{"area_id":"$areaId"}""" else """{"area_id":null}"""
        val json = requestResponse(PacketCodec.CMD_REQ_DEVICES, payload, PacketCodec.CMD_ANS_DEVICES)
        return parseEntityList(json)
    }

    suspend fun requestDashboards(): List<HaDashboardInfo> {
        val json = requestResponse(PacketCodec.CMD_REQ_DASHBOARDS, null, PacketCodec.CMD_ANS_DASHBOARDS)
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HaDashboardInfo(
                id = o.getString("id"),
                urlPath = o.getString("url_path"),
                title = o.getString("title"),
                views = parseViews(o.optJSONArray("views")),
            )
        }
    }

    suspend fun requestState(entityId: String): HaEntityState? {
        val payload = """{"entity_id":"$entityId"}"""
        return try {
            val json = requestResponse(PacketCodec.CMD_REQ_STATE, payload, PacketCodec.CMD_ANS_STATE)
            parseSingleState(json)
        } catch (e: IOException) {
            Timber.w("requestState($entityId): ${e.message}")
            null
        }
    }

    suspend fun callService(domain: String, service: String, entityId: String?, data: Map<String, Any> = emptyMap()): Boolean {
        val obj = JSONObject().apply {
            put("domain", domain)
            put("service", service)
            entityId?.let { put("entity_id", it) }
            if (data.isNotEmpty()) put("data", JSONObject(data))
        }
        val json = requestResponse(PacketCodec.CMD_CALL_SERVICE, obj.toString(), PacketCodec.CMD_ANS_CALL_SERVICE)
        return JSONObject(json).optBoolean("success", false)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun requestResponse(
        requestCmd: Byte,
        payload: String?,
        expectedResponseCmd: Byte,
        timeoutMs: Long = 15_000L,
    ): String {
        val t = transport ?: throw IOException("Not connected")
        val responseCh = Channel<PacketCodec.Packet>(capacity = 1)
        // Replace any stale entry from a previous timed-out request for the same cmd.
        pendingResponses[expectedResponseCmd]?.close()
        pendingResponses[expectedResponseCmd] = responseCh

        try {
            val frame = if (payload != null)
                PacketCodec.encodeJson(passcode, requestCmd, payload)
            else
                PacketCodec.encode(passcode, requestCmd)
            Timber.d("APP→PI: cmd=0x%02X (%d bytes) payload=%s",
                requestCmd.toInt() and 0xFF, frame.size, payload ?: "<empty>")
            t.writeFrame(frame)
            val pkt = withTimeoutOrNull(timeoutMs) { responseCh.receive() }
                ?: throw IOException("Timeout waiting for response to cmd ${"0x%02X".format(requestCmd.toInt() and 0xFF)}")
            return pkt.payloadString()
        } finally {
            // Always clean up so the next request for the same cmd starts fresh.
            pendingResponses.remove(expectedResponseCmd)
            responseCh.close()
        }
    }

    private suspend fun receiveLoop(t: io.homeassistant.btdashboard.bluetooth.FrameProtocol) {
        try {
            while (t.isOpen) {
                val raw = t.readFrame()
                val pkt = PacketCodec.decode(raw) ?: run {
                    Timber.w("APP←PI: malformed/CRC-fail packet (%d bytes)", raw.size)
                    continue
                }
                if (pkt.passcode != passcode) {
                    Timber.w("APP←PI: wrong passcode 0x%08X — discarded", pkt.passcode)
                    continue
                }
                val payloadStr = if (pkt.payload.isEmpty()) "<empty>"
                                 else pkt.payloadString().let { if (it.length > 200) "${it.take(200)}…(+${it.length-200})" else it }
                Timber.d("APP←PI: cmd=0x%02X (%d bytes) payload=%s",
                    pkt.cmd.toInt() and 0xFF, raw.size, payloadStr)
                handlePacket(pkt)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Timber.d("BLE receive loop cancelled")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "BLE receive loop ended")
            _state.value = State.ERROR
        }
    }

    private suspend fun handlePacket(pkt: PacketCodec.Packet) {
        when (pkt.cmd) {
            PacketCodec.CMD_ACK -> {
                // ACK received — the matching response will follow, nothing to do here
            }
            PacketCodec.CMD_NACK -> {
                val error = JSONObject(pkt.payloadString()).optString("error", "NACK")
                Timber.w("NACK from HA: $error")
            }
            PacketCodec.CMD_STATE_CHANGE -> {
                parseSingleState(pkt.payloadString())?.let {
                    _stateChanges.tryEmit(EntityStateUpdate(it.entityId, it.state, it.attributes))
                    // ACK the push
                    Timber.d("APP→PI: cmd=0x01 (auto-ACK for STATE_CHANGE %s, 14 bytes)", it.entityId)
                    transport?.writeFrame(PacketCodec.encode(passcode, PacketCodec.CMD_ACK))
                }
            }
            else -> {
                pendingResponses[pkt.cmd]?.trySend(pkt)
            }
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseEntityList(json: String): List<HaEntityState> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val attrs = parseAttributes(o.optJSONObject("attrs")).toMutableMap<String, Any?>()
            val name = o.optString("name", "")
            if (name.isNotBlank()) attrs["friendly_name"] = name
            HaEntityState(
                entityId   = o.getString("entity_id"),
                state      = o.getString("state"),
                attributes = attrs,
                areaId     = o.optString("area_id").takeIf { s -> s.isNotBlank() },
            )
        }
    }

    private fun parseSingleState(json: String): HaEntityState? {
        val o = JSONObject(json)
        return HaEntityState(
            entityId   = o.getString("entity_id"),
            state      = o.getString("state"),
            attributes = parseAttributes(o.optJSONObject("attributes")),
        )
    }

    private fun parseAttributes(obj: JSONObject?): Map<String, Any> {
        obj ?: return emptyMap()
        return obj.keys().asSequence().associateWith { obj[it] }
    }

    private fun parseViews(arr: JSONArray?): List<HaView> {
        arr ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val eids = o.optJSONArray("entity_ids")
            HaView(
                id = o.getString("id"),
                path = o.getString("path"),
                title = o.getString("title"),
                entityIds = (0 until (eids?.length() ?: 0)).map { i -> eids!!.getString(i) },
            )
        }
    }
}

data class EntityStateUpdate(val entityId: String, val state: String, val attributes: Map<String, Any?>)

// Data classes shared with dashboard/service layer
data class HaArea(val id: String, val name: String, val icon: String)
data class HaView(val id: String, val path: String, val title: String, val entityIds: List<String>)
data class HaDashboardInfo(val id: String, val urlPath: String, val title: String, val views: List<HaView>)
