package io.github.gruni22.btdashboard.demo

import android.content.Context
import io.github.gruni22.btdashboard.bluetooth.BluetoothTransport
import io.github.gruni22.btdashboard.config.BtConfig
import io.github.gruni22.btdashboard.config.DeviceConfig
import io.github.gruni22.btdashboard.dashboard.HaArea
import io.github.gruni22.btdashboard.dashboard.HaDashboardInfo
import io.github.gruni22.btdashboard.dashboard.HaEntityState
import io.github.gruni22.btdashboard.dashboard.HaView
import io.github.gruni22.btdashboard.db.AppDatabase
import io.github.gruni22.btdashboard.db.AreaEntity
import io.github.gruni22.btdashboard.db.DashboardEntity
import io.github.gruni22.btdashboard.db.EntityEntity
import io.github.gruni22.btdashboard.db.ViewEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Static demo fixtures + seeder. Two purposes:
 *
 *   1. Populate the local Room DB with a representative smart-home so the
 *      dashboard renders nicely for screenshots — no real ESP32 gateway
 *      needed. Triggered by [seed] (e.g. from a hidden Settings entry, or
 *      via `adb shell am start … --es action seed_demo`).
 *
 *   2. Provide shared sample objects for unit tests so test code doesn't
 *      have to hand-build `HaEntityState` instances for every assertion.
 */
object DemoData {

    const val DEVICE_ID = "demo-gateway-0001"
    const val DEVICE_ADDRESS = "00:11:22:33:44:55"
    const val DEVICE_NAME = "Demo Gateway"
    const val DASH_HOME_ID = "demo-dash-home"
    const val DASH_AUTO_ID = "demo-dash-auto"

    // ── Areas ─────────────────────────────────────────────────────────────────

    val areas: List<HaArea> = listOf(
        HaArea(id = "living_room", name = "Wohnzimmer", icon = "mdi:sofa"),
        HaArea(id = "kitchen",     name = "Küche",      icon = "mdi:stove"),
        HaArea(id = "bedroom",     name = "Schlafzimmer", icon = "mdi:bed"),
        HaArea(id = "garden",      name = "Garten",     icon = "mdi:tree"),
    )

    // ── Entities ──────────────────────────────────────────────────────────────

    /**
     * Curated set covering every domain the UI knows how to render — lights
     * with and without brightness, switches, climate, cover with position,
     * lock, fan, media-player, vacuum, scene, humidifier, sensors and a
     * binary_sensor. Roughly mirrors a small "real" house so screenshots are
     * believable rather than test-data-looking.
     */
    val entities: List<HaEntityState> = listOf(
        // Lights
        light("light.living_room_ceiling", "Deckenlicht Wohnzimmer", area = "living_room",
              on = true, brightnessPct = 75, supportsBrightness = true),
        light("light.kitchen_spots",       "Küchen-Spots",            area = "kitchen",
              on = false, brightnessPct = 0, supportsBrightness = true),
        light("light.bedroom_lamp",        "Nachttischlampe",         area = "bedroom",
              on = true, brightnessPct = 30, supportsBrightness = true),
        light("light.garden_path",         "Garten-Wegbeleuchtung",   area = "garden",
              on = false, brightnessPct = 0, supportsBrightness = false),

        // Switches
        switch("switch.coffee_machine",    "Kaffeemaschine",          area = "kitchen",  on = false),
        switch("switch.living_room_tv",    "TV Steckdose",            area = "living_room", on = true),

        // Cover
        cover("cover.living_room_blinds",  "Jalousie Wohnzimmer",     area = "living_room",
              open = true, position = 60),

        // Climate
        climate("climate.bedroom_thermostat", "Thermostat Schlafzimmer", area = "bedroom",
                hvacMode = "heat", current = 21.5, target = 22.0),

        // Fan
        fan("fan.bedroom_fan",             "Deckenventilator",        area = "bedroom",
            on = true, percentage = 50),

        // Lock
        lock("lock.front_door",            "Haustür",                 area = null,
             locked = true),

        // Media player
        mediaPlayer("media_player.living_room_tv", "TV Wohnzimmer",   area = "living_room",
                    state = "playing", title = "Stranger Things — S4E7"),

        // Vacuum
        vacuum("vacuum.roomba",            "Roomba",                  area = "living_room",
               state = "docked"),

        // Scene
        scene("scene.movie_night",         "Filmabend",               area = "living_room"),

        // Humidifier
        humidifier("humidifier.bedroom",   "Luftbefeuchter Schlafzimmer", area = "bedroom",
                   on = true, targetHumidity = 50),

        // Binary sensor
        binarySensor("binary_sensor.front_door_open", "Haustür offen", area = null,
                     active = false),

        // Sensors
        sensor("sensor.kitchen_temperature", "Temperatur Küche",       area = "kitchen",
               state = "22.3", unit = "°C", deviceClass = "temperature"),
        sensor("sensor.garden_humidity",   "Luftfeuchte Garten",      area = "garden",
               state = "65", unit = "%", deviceClass = "humidity"),
        sensor("sensor.living_room_power", "Stromverbrauch",          area = "living_room",
               state = "138.4", unit = "W", deviceClass = "power"),
    )

    // ── Dashboards (DASH_* label simulation) ──────────────────────────────────

    val dashboards: List<HaDashboardInfo> = listOf(
        HaDashboardInfo(
            id = DASH_HOME_ID,
            urlPath = "demo-home",
            title = "Home",
            views = listOf(
                HaView(
                    id = "$DASH_HOME_ID-overview",
                    path = "overview",
                    title = "Übersicht",
                    entityIds = entities.map { it.entityId },
                ),
            ),
        ),
        HaDashboardInfo(
            id = DASH_AUTO_ID,
            urlPath = "demo-auto",
            title = "Auto",
            views = listOf(
                HaView(
                    id = "$DASH_AUTO_ID-driving",
                    path = "driving",
                    title = "Fahrt",
                    entityIds = listOf(
                        "light.living_room_ceiling",
                        "lock.front_door",
                        "cover.living_room_blinds",
                        "media_player.living_room_tv",
                    ),
                ),
            ),
        ),
    )

    // ── Seeder ────────────────────────────────────────────────────────────────

    /**
     * Wipes any existing demo gateway, then writes the fixture data to the
     * Room DB and registers a stub [DeviceConfig] so the rest of the app
     * thinks a gateway is configured. Safe to call multiple times.
     */
    suspend fun seed(context: Context) {
        val db = AppDatabase.getInstance(context)
        db.entityDao().deleteAllForDevice(DEVICE_ID)
        db.areaDao().deleteAllForDevice(DEVICE_ID)
        db.dashboardDao().deleteAllForDevice(DEVICE_ID)
        db.viewDao().deleteAllForDevice(DEVICE_ID)

        db.areaDao().upsertAll(areas.map { it.toRow() })
        db.entityDao().upsertAll(entities.map { it.toRow() })
        db.dashboardDao().upsertAll(dashboards.map { it.toRow() })
        db.viewDao().upsertAll(dashboards.flatMap { dash -> dash.views.map { it.toRow(dash.id) } })

        val cfg = BtConfig(context)
        if (cfg.devices.none { it.id == DEVICE_ID }) {
            cfg.addDevice(
                DeviceConfig(
                    id = DEVICE_ID,
                    address = DEVICE_ADDRESS,
                    passcode = 0x12345678,
                    transport = BluetoothTransport.BLE,
                    name = DEVICE_NAME,
                    lastSyncTime = System.currentTimeMillis(),
                ),
            )
        }
        if (cfg.activeDeviceId == null) cfg.setActive(DEVICE_ID)
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private fun HaArea.toRow() =
        AreaEntity(deviceId = DEVICE_ID, id = id, name = name, icon = icon)

    private fun HaEntityState.toRow(): EntityEntity {
        val attr = JSONObject()
        attributes.forEach { (k, v) -> attr.put(k, v ?: JSONObject.NULL) }
        return EntityEntity(
            deviceId = DEVICE_ID,
            id = entityId,
            name = friendlyName,
            domain = domain,
            areaId = areaId,
            state = state,
            attributesJson = attr.toString(),
            lastUpdated = System.currentTimeMillis(),
        )
    }

    private fun HaDashboardInfo.toRow() =
        DashboardEntity(deviceId = DEVICE_ID, id = id, urlPath = urlPath, title = title)

    private fun HaView.toRow(dashboardId: String) =
        ViewEntity(
            deviceId = DEVICE_ID,
            id = id,
            dashboardId = dashboardId,
            path = path,
            title = title,
            entityIdsJson = JSONArray(entityIds).toString(),
        )

    // ── Entity factories (keep call sites readable) ───────────────────────────

    private fun light(
        id: String, name: String, area: String?, on: Boolean,
        brightnessPct: Int, supportsBrightness: Boolean,
    ): HaEntityState {
        val attrs = mutableMapOf<String, Any?>("friendly_name" to name)
        if (supportsBrightness) {
            attrs["supported_color_modes"] = listOf("brightness")
            if (on) attrs["brightness"] = (brightnessPct * 255 / 100)
        }
        return HaEntityState(
            entityId = id,
            state = if (on) "on" else "off",
            attributes = attrs,
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )
    }

    private fun switch(id: String, name: String, area: String?, on: Boolean) =
        HaEntityState(
            entityId = id,
            state = if (on) "on" else "off",
            attributes = mapOf("friendly_name" to name),
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun cover(id: String, name: String, area: String?, open: Boolean, position: Int) =
        HaEntityState(
            entityId = id,
            state = if (open) "open" else "closed",
            attributes = mapOf(
                "friendly_name" to name,
                "current_position" to position,
                // CoverEntityFeature.SET_POSITION = 4
                "supported_features" to 4,
            ),
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun climate(
        id: String, name: String, area: String?,
        hvacMode: String, current: Double, target: Double,
    ) = HaEntityState(
        entityId = id,
        state = hvacMode,
        attributes = mapOf(
            "friendly_name" to name,
            "current_temperature" to current,
            "temperature" to target,
            "hvac_modes" to listOf("off", "heat", "cool", "auto"),
            "min_temp" to 5.0,
            "max_temp" to 30.0,
            "target_temp_step" to 0.5,
        ),
        areaId = area,
        sourceDeviceId = DEVICE_ID,
    )

    private fun fan(id: String, name: String, area: String?, on: Boolean, percentage: Int) =
        HaEntityState(
            entityId = id,
            state = if (on) "on" else "off",
            attributes = mapOf(
                "friendly_name" to name,
                "percentage" to percentage,
                "percentage_step" to 10,
                // FanEntityFeature.SET_SPEED = 8
                "supported_features" to 8,
            ),
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun lock(id: String, name: String, area: String?, locked: Boolean) =
        HaEntityState(
            entityId = id,
            state = if (locked) "locked" else "unlocked",
            attributes = mapOf("friendly_name" to name),
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun mediaPlayer(id: String, name: String, area: String?, state: String, title: String?) =
        HaEntityState(
            entityId = id,
            state = state,
            attributes = buildMap {
                put("friendly_name", name)
                if (title != null) put("media_title", title)
            },
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun vacuum(id: String, name: String, area: String?, state: String) =
        HaEntityState(
            entityId = id,
            state = state,
            attributes = mapOf("friendly_name" to name),
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun scene(id: String, name: String, area: String?) =
        HaEntityState(
            entityId = id,
            state = "scening",  // HA reports the last-activation timestamp; any non-"unknown" string works
            attributes = mapOf("friendly_name" to name),
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun humidifier(
        id: String, name: String, area: String?, on: Boolean, targetHumidity: Int,
    ) = HaEntityState(
        entityId = id,
        state = if (on) "on" else "off",
        attributes = mapOf(
            "friendly_name" to name,
            "humidity" to targetHumidity,
            "min_humidity" to 30,
            "max_humidity" to 80,
        ),
        areaId = area,
        sourceDeviceId = DEVICE_ID,
    )

    private fun binarySensor(id: String, name: String, area: String?, active: Boolean) =
        HaEntityState(
            entityId = id,
            state = if (active) "on" else "off",
            attributes = mapOf("friendly_name" to name),
            areaId = area,
            sourceDeviceId = DEVICE_ID,
        )

    private fun sensor(
        id: String, name: String, area: String?,
        state: String, unit: String?, deviceClass: String?,
    ) = HaEntityState(
        entityId = id,
        state = state,
        attributes = buildMap {
            put("friendly_name", name)
            if (unit != null) put("unit_of_measurement", unit)
            if (deviceClass != null) put("device_class", deviceClass)
        },
        areaId = area,
        sourceDeviceId = DEVICE_ID,
    )
}
