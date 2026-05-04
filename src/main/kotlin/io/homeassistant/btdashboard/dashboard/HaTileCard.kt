package io.homeassistant.btdashboard.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Lovelace-style tile card. Mirrors the HA web `tile` card:
 *   ┌─────────────────────────────────────┐
 *   │ ⬤  Friendly Name                    │
 *   │     state · attribute               │
 *   │     ━━━━━━━●━━━━━ (only for lights) │
 *   └─────────────────────────────────────┘
 *
 * - Background tints with the domain accent when the entity is on.
 * - Tap anywhere on the card toggles the entity (for controllable domains).
 * - Lights show an inline brightness slider when on.
 */
@Composable
fun HaTileCard(
    entity: HaEntityState,
    onToggle: () -> Unit,
    onBrightness: (Int) -> Unit = {},
    onFanSpeed: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = haDomainColor(entity.domain)
    val isOn = entity.isActive
    val bg by animateColorAsState(
        targetValue = if (isOn) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        label = "tile_bg",
    )
    val onBg = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .let { if (entity.isControllable) it.clickable(onClick = onToggle) else it },
        shape = RoundedCornerShape(20.dp),
        color = bg,
        contentColor = onBg,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Circular icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isOn) accent else accent.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = haDomainIcon(entity.domain),
                        contentDescription = null,
                        tint = if (isOn) Color.White else accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.friendlyName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onBg,
                    )
                    Text(
                        text = formatState(entity),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBg.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Inline brightness slider for lights when on
            if (entity.domain == "light" && isOn && entity.supportsBrightness) {
                var local by remember(entity.entityId, entity.brightnessPercent) {
                    mutableFloatStateOf(entity.brightnessPercent.toFloat())
                }
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onBrightness(local.toInt()) },
                    valueRange = 1f..100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = accent.copy(alpha = 0.25f),
                    ),
                )
            }

            // Inline fan-speed slider for fans when on
            if (entity.domain == "fan" && isOn && entity.supportsFanSpeed) {
                var local by remember(entity.entityId, entity.fanPercentage ?: 0) {
                    mutableFloatStateOf((entity.fanPercentage ?: 0).toFloat())
                }
                val steps = if (entity.fanPercentageStep > 1)
                    (100 / entity.fanPercentageStep) - 1 else 0
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onFanSpeed(local.toInt()) },
                    valueRange = 0f..100f,
                    steps = steps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = accent.copy(alpha = 0.25f),
                    ),
                )
            }
        }
    }
}

// ── State formatting helpers (mirror HA's frontend pattern) ───────────────────

private fun formatState(entity: HaEntityState): String {
    val state = entity.state
    return when (entity.domain) {
        "light", "switch", "input_boolean", "automation", "script", "fan" -> {
            if (state == "on") {
                if (entity.domain == "light" && entity.supportsBrightness && entity.brightnessPercent > 0) {
                    "An · ${entity.brightnessPercent}%"
                } else "An"
            } else if (state == "off") "Aus" else state
        }
        "lock" -> when (state) {
            "locked" -> "Verriegelt"
            "unlocked" -> "Entriegelt"
            "locking" -> "Verriegelt..."
            "unlocking" -> "Entriegelt..."
            else -> state
        }
        "cover" -> when (state) {
            "open" -> "Offen"
            "closed" -> "Geschlossen"
            "opening" -> "Öffnet..."
            "closing" -> "Schließt..."
            else -> state
        }
        "climate" -> {
            val current = entity.attributes["current_temperature"]
            val setpoint = entity.attributes["temperature"]
            buildString {
                if (current != null) append("$current°")
                if (setpoint != null) {
                    if (isNotEmpty()) append(" → ")
                    append("$setpoint°")
                }
                if (isEmpty()) append(state)
            }
        }
        "binary_sensor" -> if (state == "on") "Erkannt" else "Klar"
        "media_player" -> when (state) {
            "playing" -> entity.attributes["media_title"]?.toString() ?: "Spielt"
            "paused" -> "Pausiert"
            "idle" -> "Bereit"
            "off" -> "Aus"
            else -> state
        }
        "sensor" -> {
            val unit = entity.unit
            if (unit != null) "$state $unit" else state
        }
        else -> state
    }
}

// ── Domain colors (HA's standard per-domain accents) ──────────────────────────

internal fun haDomainColor(domain: String): Color = when (domain) {
    "light"          -> Color(0xFFFDD835)  // amber
    "switch",
    "input_boolean"  -> Color(0xFF42A5F5)  // light blue
    "climate"        -> Color(0xFFFF7043)  // deep orange
    "cover"          -> Color(0xFF8BC34A)  // light green
    "media_player"   -> Color(0xFF26C6DA)  // cyan
    "lock"           -> Color(0xFFEF5350)  // red
    "fan"            -> Color(0xFF26A69A)  // teal
    "automation"     -> Color(0xFFFFA726)  // orange
    "script"         -> Color(0xFF9C27B0)  // purple
    "humidifier"     -> Color(0xFF5C6BC0)  // indigo
    "sensor",
    "binary_sensor"  -> Color(0xFF78909C)  // blue grey
    else             -> Color(0xFF009AC7)  // HA primary
}

internal fun haDomainIcon(domain: String): ImageVector = when (domain) {
    "light"          -> Icons.Filled.Lightbulb
    "switch",
    "input_boolean"  -> Icons.Filled.PowerSettingsNew
    "sensor",
    "binary_sensor"  -> Icons.Filled.Sensors
    "climate"        -> Icons.Filled.DeviceThermostat
    "cover"          -> Icons.Filled.Blinds
    "media_player"   -> Icons.Filled.PlayArrow
    "lock"           -> Icons.Filled.Lock
    "fan"            -> Icons.Filled.Air
    "automation"     -> Icons.Filled.Bolt
    "script"         -> Icons.Filled.Code
    "humidifier"     -> Icons.Filled.Water
    "sun"            -> Icons.Filled.WbSunny
    else             -> Icons.Filled.Home
}
