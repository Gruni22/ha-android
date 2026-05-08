package io.homeassistant.btdashboard.dashboard

import kotlin.math.roundToInt

data class HaEntityState(
    val entityId: String,
    val state: String,
    val attributes: Map<String, Any?> = emptyMap(),
    val lastChanged: String = "",
    val areaId: String? = null,
    /**
     * Which gateway this entity came in through. Empty when we don't know
     * (DB rows from before multi-instance support, or single-device setups).
     * Used by service-call routing so a tap on `light.kitchen` goes to the
     * gateway that actually owns it — matters when two HA instances share
     * an entity_id.
     */
    val sourceDeviceId: String = "",
) {
    val domain: String get() = entityId.substringBefore(".")

    val friendlyName: String
        get() = (attributes["friendly_name"] as? String)?.takeIf { it.isNotBlank() } ?: entityId

    val unit: String?
        get() = attributes["unit_of_measurement"] as? String

    val brightness: Int?
        get() = (attributes["brightness"] as? Number)?.toInt()

    // Brightness as 0–100 % — matches brightness_pct service param and HA frontend display.
    // HA rounds (not truncates) when converting raw 0–255 → percent, so e.g. brightness=127
    // shows as 50 % in HA, not 49 %. Use roundToInt so our slider stays in sync.
    val brightnessPercent: Int
        get() = ((brightness ?: 0).toDouble() * 100 / 255).roundToInt().coerceIn(0, 100)

    // Whether this entity is in an "active/on" state.
    // Climate uses hvac_mode strings instead of "on"/"off".
    // Covers and locks have transitional states ("closing","opening","locking","unlocking")
    // that must be treated as active so the UI doesn't immediately reverse the action.
    val isActive: Boolean
        get() = when (domain) {
            "climate" -> state != "off" && state != "unavailable" && state != "unknown"
            "cover"   -> state in setOf("open", "opening")
            "lock"    -> state in setOf("unlocked", "unlocking", "opening")
            else      -> state in setOf("on", "open", "unlocked", "playing", "home", "active")
        }

    val isControllable: Boolean
        get() = domain in setOf(
            "light", "switch", "input_boolean", "cover", "lock",
            "fan", "climate", "media_player", "automation", "script",
            "vacuum", "scene", "humidifier",
            "input_number", "number", "input_select", "select",
        )

    // Whether tapping the tile body itself triggers a service call (toggle,
    // play, run script, trigger scene, …). number / input_number / select /
    // input_select don't have a meaningful "on/off" — only their inline
    // slider/dropdown does anything, so we don't want a tile-tap firing a
    // bogus turn_on. Excluded from the .clickable() in HaTileCard.
    val isTapToggleable: Boolean
        get() = isControllable && domain !in setOf(
            "number", "input_number", "select", "input_select",
        )

    // One-shot domains (tap = run, no on/off state) — used to disable the
    // "isOn" visual emphasis on the tile.
    val isOneShot: Boolean get() = domain == "scene"

    // Uses supported_color_modes (HA 2021.5+); falls back to brightness attribute presence.
    // Mirrors supportsLightBrightness() in the companion app's Entity.kt.
    val supportsBrightness: Boolean
        get() {
            if (domain != "light") return false
            @Suppress("UNCHECKED_CAST")
            val modes = attributes["supported_color_modes"] as? List<String>
            return if (modes != null) modes.any { it != "onoff" && it != "unknown" }
            else brightness != null
        }

    val fanPercentage: Int?
        get() = (attributes["percentage"] as? Number)?.toInt()

    // Checks FanEntityFeature.SET_SPEED (= 8 in HA 2022.5+); falls back to attribute presence
    // for older HA versions. Mirrors supportsFanSetSpeed() in the companion app's Entity.kt.
    val supportsFanSpeed: Boolean
        get() {
            if (domain != "fan") return false
            val features = (attributes["supported_features"] as? Number)?.toInt()
            return if (features != null) (features and 8) == 8
            else attributes.containsKey("percentage")
        }

    // Discrete step size for the fan slider (from percentage_step attribute).
    val fanPercentageStep: Int
        get() = (attributes["percentage_step"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1

    // ── Climate ───────────────────────────────────────────────────────────────
    val currentTemperature: Double? get() = (attributes["current_temperature"] as? Number)?.toDouble()
    val targetTemperature: Double? get() = (attributes["temperature"] as? Number)?.toDouble()
    val targetTempLow: Double? get() = (attributes["target_temp_low"] as? Number)?.toDouble()
    val targetTempHigh: Double? get() = (attributes["target_temp_high"] as? Number)?.toDouble()
    // Defaults match HA frontend (5..30 °C, 0.5° step) for the rare case the
    // entity didn't ship them. Real thermostats almost always set min_temp /
    // max_temp / target_temp_step so this fallback shouldn't normally fire.
    val minTemp: Double get() = (attributes["min_temp"] as? Number)?.toDouble() ?: 5.0
    val maxTemp: Double get() = (attributes["max_temp"] as? Number)?.toDouble() ?: 30.0
    val tempStep: Double get() = (attributes["target_temp_step"] as? Number)?.toDouble() ?: 0.5

    // hvac_mode = the state itself for climate; hvac_modes = list of allowed values.
    @Suppress("UNCHECKED_CAST")
    val hvacModes: List<String>
        get() = (attributes["hvac_modes"] as? List<String>) ?: emptyList()

    // ── Cover position ────────────────────────────────────────────────────────
    val coverPosition: Int? get() = (attributes["current_position"] as? Number)?.toInt()
    // CoverEntityFeature.SET_POSITION = 4
    val supportsCoverPosition: Boolean
        get() = domain == "cover" &&
            (((attributes["supported_features"] as? Number)?.toInt() ?: 0) and 4) == 4

    // ── Number / input_number ─────────────────────────────────────────────────
    val numberValue: Double? get() = state.toDoubleOrNull()
    val numberMin: Double get() = (attributes["min"] as? Number)?.toDouble() ?: 0.0
    val numberMax: Double get() = (attributes["max"] as? Number)?.toDouble() ?: 100.0
    val numberStep: Double get() = (attributes["step"] as? Number)?.toDouble() ?: 1.0

    // ── Select / input_select ─────────────────────────────────────────────────
    @Suppress("UNCHECKED_CAST")
    val selectOptions: List<String>
        get() = (attributes["options"] as? List<String>) ?: emptyList()

    // ── Humidifier ────────────────────────────────────────────────────────────
    val targetHumidity: Int? get() = (attributes["humidity"] as? Number)?.toInt()
    val minHumidity: Int get() = (attributes["min_humidity"] as? Number)?.toInt() ?: 0
    val maxHumidity: Int get() = (attributes["max_humidity"] as? Number)?.toInt() ?: 100

    // ── Vacuum ────────────────────────────────────────────────────────────────
    // States: "docked", "cleaning", "returning", "paused", "idle", "error"
    val vacuumIsCleaning: Boolean get() = state in setOf("cleaning", "returning")
}

/**
 * Maps the current entity state to the *(serviceDomain, service)* call that
 * a primary-tap should issue. Single source of truth — used by the phone
 * dashboard's `viewModel.toggle()` and Android Auto's row-toggle, so both
 * surfaces stay consistent. Returns `null` for domains where a binary
 * tap-toggle has no meaning (e.g. `number`, `select`).
 */
fun HaEntityState.toggleAction(): Pair<String, String>? {
    val isOn = isActive
    return when (domain) {
        "cover" -> "cover" to when (state) {
            "open", "opening" -> "close_cover"
            "closing"         -> "open_cover"
            else              -> "open_cover"
        }
        "lock"          -> "lock"         to if (isOn) "lock" else "unlock"
        "media_player"  -> "media_player" to if (isOn) "media_pause" else "media_play"
        "script"        -> "script"       to "turn_on"
        "scene"         -> "scene"        to "turn_on"
        "vacuum" -> "vacuum" to when (state) {
            "cleaning"  -> "pause"
            "returning" -> "stop"
            "paused"    -> "start"
            else        -> "start"
        }
        // Domains with no meaningful tile-tap action (sliders/dropdowns only).
        "number", "input_number", "select", "input_select" -> null
        else -> domain to if (isOn) "turn_off" else "turn_on"
    }
}
