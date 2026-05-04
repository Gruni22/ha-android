package io.homeassistant.btdashboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── HA brand palette (mirrors Lovelace web frontend tokens) ───────────────────
private val HaPrimary40 = Color(0xFF03A9F4)   // primary brand blue (button fill)
private val HaPrimary50 = Color(0xFF18BCF2)   // selected tab indicator
private val HaPrimary60 = Color(0xFF37C8FD)   // dark-mode primary
private val HaPrimary90 = Color(0xFFDFF3FC)   // soft container
private val HaPrimary95 = Color(0xFFEFF9FE)

// Light theme — clean white background, light grey card variants
private val LightColors = lightColorScheme(
    primary               = HaPrimary40,
    onPrimary             = Color.White,
    primaryContainer      = HaPrimary90,
    onPrimaryContainer    = Color(0xFF001F2A),
    secondary             = Color(0xFF006787),
    onSecondary           = Color.White,
    secondaryContainer    = HaPrimary95,
    onSecondaryContainer  = Color(0xFF001F2A),
    background            = Color(0xFFFAFAFA),
    onBackground          = Color(0xFF1A1A1A),
    surface               = Color.White,
    onSurface             = Color(0xFF1A1A1A),
    surfaceVariant        = Color(0xFFEEF1F4),    // tile card bg (off state)
    onSurfaceVariant      = Color(0xFF45474E),
    surfaceContainerLow   = Color(0xFFF5F5F5),
    surfaceContainer      = Color(0xFFEFEFEF),
    surfaceContainerHigh  = Color(0xFFE8E8E8),
    outline               = Color(0xFFE0E0E0),
)

// Dark theme — HA Lovelace dark mode: card-grey on near-black, blue accent
private val DarkColors = darkColorScheme(
    primary               = HaPrimary60,
    onPrimary             = Color(0xFF002E3E),
    primaryContainer      = Color(0xFF004156),
    onPrimaryContainer    = HaPrimary90,
    secondary             = Color(0xFF82CFF2),
    onSecondary           = Color(0xFF003547),
    secondaryContainer    = Color(0xFF1C4F66),
    onSecondaryContainer  = Color(0xFFD3EAF5),
    background            = Color(0xFF111111),     // HA Lovelace dark page bg
    onBackground          = Color(0xFFE8E8E8),
    surface               = Color(0xFF1C1C1C),     // app bar / sheet
    onSurface             = Color(0xFFE8E8E8),
    surfaceVariant        = Color(0xFF2A2A2A),     // tile card bg (off state)
    onSurfaceVariant      = Color(0xFFB0B0B0),
    surfaceContainerLow   = Color(0xFF1A1A1A),
    surfaceContainer      = Color(0xFF222222),
    surfaceContainerHigh  = Color(0xFF2C2C2C),
    outline               = Color(0xFF3A3A3A),
)

@Composable
fun HaBluetoothTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
