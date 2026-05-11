package io.github.gruni22.btdashboard.dashboard

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gruni22.btdashboard.R
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
    onClimateTemp: (Double) -> Unit = {},
    onClimateMode: (String) -> Unit = {},
    onCoverPosition: (Int) -> Unit = {},
    onVacuumAction: (String) -> Unit = {},
    onNumber: (Double) -> Unit = {},
    onSelect: (String) -> Unit = {},
    onHumidity: (Int) -> Unit = {},
    /**
     * Friendly name of the gateway this entity came in through. Only set when
     * the user has >1 device configured — otherwise the label is noise. Shown
     * as a small "via …" caption below the entity name so identical
     * `entity_id`s from different gateways stay distinguishable.
     */
    gatewayLabel: String? = null,
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
            .let { if (entity.isTapToggleable) it.clickable(onClick = onToggle) else it },
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
                        text = formatState(LocalContext.current, entity),
                        style = MaterialTheme.typography.bodySmall,
                        color = onBg.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (gatewayLabel != null) {
                        Text(
                            text = "$gatewayLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = onBg.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

            // Climate: −/+ stepper for the setpoint, plus HVAC-mode chips.
            // Mirrors HA's tile-card "target-temperature" + "climate-hvac-modes"
            // features: big target-temp readout in the middle, large round
            // buttons either side, mode chips below.
            if (entity.domain == "climate" && entity.targetTemperature != null && isOn) {
                Spacer(Modifier.height(8.dp))
                ClimateStepper(
                    target = entity.targetTemperature!!,
                    minTemp = entity.minTemp,
                    maxTemp = entity.maxTemp,
                    step = entity.tempStep,
                    accent = accent,
                    onChange = onClimateTemp,
                )
            }
            if (entity.domain == "climate" && entity.hvacModes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HvacModeRow(
                    modes = entity.hvacModes,
                    active = entity.state,
                    accent = accent,
                    onSelect = onClimateMode,
                )
            }

            // Cover position slider (only when cover supports SET_POSITION).
            // HA tile uses ▲ / ▼ steppers + percent label.
            if (entity.domain == "cover" && entity.supportsCoverPosition) {
                var local by remember(entity.entityId, entity.coverPosition ?: 0) {
                    mutableFloatStateOf((entity.coverPosition ?: 0).toFloat())
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${local.toInt()} %",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.width(56.dp),
                    )
                    Slider(
                        value = local,
                        onValueChange = { local = it },
                        onValueChangeFinished = { onCoverPosition(local.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).height(28.dp),
                        colors = sliderColors(accent),
                    )
                }
            }

            // Vacuum: action buttons
            if (entity.domain == "vacuum") {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val cleaning = entity.vacuumIsCleaning
                    VacuumButton(
                        icon = if (cleaning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        label = stringResource(if (cleaning) R.string.bt_vacuum_btn_pause else R.string.bt_vacuum_btn_start),
                        accent = accent,
                        onClick = { onVacuumAction(if (cleaning) "pause" else "start") },
                        modifier = Modifier.weight(1f),
                    )
                    VacuumButton(
                        icon = Icons.Filled.Home,
                        label = stringResource(R.string.bt_vacuum_btn_dock),
                        accent = accent,
                        onClick = { onVacuumAction("return_to_base") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // input_number / number — slider with current value label
            if (entity.domain == "input_number" || entity.domain == "number") {
                val v = entity.numberValue
                if (v != null) {
                    var local by remember(entity.entityId, v) { mutableFloatStateOf(v.toFloat()) }
                    val steps = ((entity.numberMax - entity.numberMin) / entity.numberStep).toInt() - 1
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(if (entity.numberStep < 1.0) "%.1f".format(local) else "${local.toInt()}")
                                entity.unit?.let { append(" $it") }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            modifier = Modifier.width(64.dp),
                        )
                        Slider(
                            value = local,
                            onValueChange = { local = it },
                            onValueChangeFinished = {
                                onNumber(if (entity.numberStep < 1.0) local.toDouble() else local.toInt().toDouble())
                            },
                            valueRange = entity.numberMin.toFloat()..entity.numberMax.toFloat(),
                            steps = steps.coerceIn(0, 99),
                            modifier = Modifier.weight(1f).height(28.dp),
                            colors = sliderColors(accent),
                        )
                    }
                }
            }

            // input_select / select — dropdown (current value as a chip)
            if (entity.domain == "input_select" || entity.domain == "select") {
                val options = entity.selectOptions
                if (options.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(6.dp))
                    Box {
                        AssistChip(
                            onClick = { expanded = true },
                            label = { Text(entity.state, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = accent.copy(alpha = 0.18f),
                                labelColor = accent,
                            ),
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            options.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        expanded = false
                                        if (opt != entity.state) onSelect(opt)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Humidifier — when on, show target-humidity slider
            if (entity.domain == "humidifier" && isOn && entity.targetHumidity != null) {
                var local by remember(entity.entityId, entity.targetHumidity) {
                    mutableFloatStateOf(entity.targetHumidity!!.toFloat())
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${local.toInt()} %",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.width(56.dp),
                    )
                    Slider(
                        value = local,
                        onValueChange = { local = it },
                        onValueChangeFinished = { onHumidity(local.toInt()) },
                        valueRange = entity.minHumidity.toFloat()..entity.maxHumidity.toFloat(),
                        modifier = Modifier.weight(1f).height(28.dp),
                        colors = sliderColors(accent),
                    )
                }
            }
        }
    }
}

/**
 * HA-style climate target-temperature stepper.
 *
 *   [ − ]    21.5°    [ + ]
 *
 * Local target tracks user taps optimistically and is replayed to HA on
 * each tap. Steps stay clamped to [minTemp, maxTemp].
 */
@Composable
private fun ClimateStepper(
    target: Double,
    minTemp: Double,
    maxTemp: Double,
    step: Double,
    accent: Color,
    onChange: (Double) -> Unit,
) {
    var local by remember(target) { mutableFloatStateOf(target.toFloat()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepperButton(
            icon = Icons.Filled.Remove,
            accent = accent,
            enabled = local > minTemp,
            onClick = {
                val next = (local - step.toFloat()).coerceAtLeast(minTemp.toFloat())
                if (next != local) {
                    local = next
                    onChange(local.toDouble())
                }
            },
        )
        Text(
            text = "${"%.1f".format(local)}°",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        StepperButton(
            icon = Icons.Filled.Add,
            accent = accent,
            enabled = local < maxTemp,
            onClick = {
                val next = (local + step.toFloat()).coerceAtMost(maxTemp.toFloat())
                if (next != local) {
                    local = next
                    onChange(local.toDouble())
                }
            },
        )
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (enabled) accent.copy(alpha = 0.18f) else accent.copy(alpha = 0.08f),
        contentColor = if (enabled) accent else accent.copy(alpha = 0.4f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun HvacModeRow(
    modes: List<String>,
    active: String,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modes.forEach { mode ->
            FilterChip(
                selected = mode == active,
                onClick = { if (mode != active) onSelect(mode) },
                label = { Text(hvacModeLabel(LocalContext.current, mode)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent,
                    selectedLabelColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun VacuumButton(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun hvacModeLabel(ctx: android.content.Context, mode: String): String = when (mode) {
    "off" -> ctx.getString(R.string.bt_state_off)
    "heat" -> ctx.getString(R.string.bt_hvac_heat)
    "cool" -> ctx.getString(R.string.bt_hvac_cool)
    "heat_cool", "auto" -> ctx.getString(R.string.bt_hvac_auto)
    "dry" -> ctx.getString(R.string.bt_hvac_dry)
    "fan_only" -> ctx.getString(R.string.bt_hvac_fan_only)
    else -> mode
}

@Composable
private fun sliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent,
    inactiveTrackColor = accent.copy(alpha = 0.25f),
)

// ── State formatting helpers (mirror HA's frontend pattern) ───────────────────

private fun formatState(ctx: android.content.Context, entity: HaEntityState): String {
    val state = entity.state
    fun s(id: Int) = ctx.getString(id)
    return when (entity.domain) {
        "light", "switch", "input_boolean", "automation", "script", "fan" -> {
            if (state == "on") {
                if (entity.domain == "light" && entity.supportsBrightness && entity.brightnessPercent > 0) {
                    ctx.getString(R.string.bt_state_on_with_brightness, entity.brightnessPercent)
                } else s(R.string.bt_state_on)
            } else if (state == "off") s(R.string.bt_state_off) else state
        }
        "lock" -> when (state) {
            "locked" -> s(R.string.bt_state_locked)
            "unlocked" -> s(R.string.bt_state_unlocked)
            "locking" -> s(R.string.bt_state_locking)
            "unlocking" -> s(R.string.bt_state_unlocking)
            else -> state
        }
        "cover" -> when (state) {
            "open" -> s(R.string.bt_state_open)
            "closed" -> s(R.string.bt_state_closed)
            "opening" -> s(R.string.bt_state_opening)
            "closing" -> s(R.string.bt_state_closing)
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
        "binary_sensor" -> if (state == "on") s(R.string.bt_state_detected) else s(R.string.bt_state_clear)
        "media_player" -> when (state) {
            "playing" -> entity.attributes["media_title"]?.toString() ?: s(R.string.bt_media_playing)
            "paused" -> s(R.string.bt_media_paused)
            "idle" -> s(R.string.bt_media_idle)
            "off" -> s(R.string.bt_state_off)
            else -> state
        }
        "sensor", "input_number", "number" -> {
            val unit = entity.unit
            if (unit != null) "$state $unit" else state
        }
        "vacuum" -> when (state) {
            "cleaning"  -> s(R.string.bt_vacuum_cleaning)
            "returning" -> s(R.string.bt_vacuum_returning)
            "paused"    -> s(R.string.bt_vacuum_paused)
            "docked"    -> s(R.string.bt_vacuum_docked)
            "idle"      -> s(R.string.bt_vacuum_idle)
            "error"     -> s(R.string.bt_vacuum_error)
            else        -> state
        }
        "humidifier" -> {
            val target = entity.targetHumidity
            when {
                state == "off"            -> s(R.string.bt_state_off)
                target != null            -> ctx.getString(R.string.bt_state_on_with_humidity, target)
                else                      -> s(R.string.bt_state_on)
            }
        }
        "scene"   -> s(R.string.bt_state_scene)
        "select", "input_select" -> state  // current option name
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
    "vacuum"         -> Color(0xFF7E57C2)  // deep purple
    "scene"          -> Color(0xFFEC407A)  // pink
    "input_number",
    "number"         -> Color(0xFF66BB6A)  // green
    "input_select",
    "select"         -> Color(0xFF26A69A)  // teal
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
    "humidifier"     -> Icons.Filled.WaterDrop
    "vacuum"         -> Icons.Filled.CleaningServices
    "scene"          -> Icons.Filled.AutoAwesome
    "input_number",
    "number"         -> Icons.Filled.Numbers
    "input_select",
    "select"         -> Icons.Filled.List
    "sun"            -> Icons.Filled.WbSunny
    else             -> Icons.Filled.Home
}
