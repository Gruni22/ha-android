package io.homeassistant.btdashboard.dashboard

data class HaEntityState(
    val entityId: String,
    val state: String,
    val attributes: Map<String, Any?> = emptyMap(),
    val lastChanged: String = "",
    val areaId: String? = null,
) {
    val domain: String get() = entityId.substringBefore(".")

    val friendlyName: String
        get() = (attributes["friendly_name"] as? String)?.takeIf { it.isNotBlank() } ?: entityId

    val unit: String?
        get() = attributes["unit_of_measurement"] as? String

    val brightness: Int?
        get() = (attributes["brightness"] as? Number)?.toInt()

    // Brightness as 0–100 % — matches brightness_pct service param and HA frontend display.
    val brightnessPercent: Int
        get() = ((brightness ?: 0) * 100 / 255).coerceIn(0, 100)

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
        )

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
}
