package io.homeassistant.btdashboard.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Room
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.HomeWork
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.homeassistant.btdashboard.R

// ── HA design tokens ──────────────────────────────────────────────────────────

// Replaced by MaterialTheme.colorScheme.background / .surface (theme-aware light+dark).

// Domain active colors — matching HA Lovelace
private fun domainActiveColor(domain: String): Color = when (domain) {
    "light"                    -> Color(0xFFFFD600)
    "switch", "input_boolean"  -> Color(0xFF2196F3)
    "climate"                  -> Color(0xFF29B6F6)
    "cover"                    -> Color(0xFF8BC34A)
    "media_player"             -> Color(0xFF26C6DA)
    "lock"                     -> Color(0xFFEF5350)
    "fan"                      -> Color(0xFF26A69A)
    "automation"               -> Color(0xFFFFA726)
    "script"                   -> Color(0xFF9C27B0)
    "humidifier"               -> Color(0xFF5C6BC0)
    "sensor"                   -> Color(0xFF78909C)
    "binary_sensor"            -> Color(0xFF78909C)
    else                       -> Color(0xFF009AC7)
}

private fun domainIcon(domain: String): ImageVector = when (domain) {
    "light"          -> Icons.Filled.Lightbulb
    "switch",
    "input_boolean"  -> Icons.Filled.PowerSettingsNew
    "sensor"         -> Icons.Filled.Sensors
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

private val DOMAIN_FILTER_ORDER = listOf(
    null, "light", "switch", "input_boolean", "climate",
    "cover", "media_player", "sensor", "binary_sensor",
    "lock", "fan", "automation", "script",
)

// Domains rendered as compact 2-column tiles (no sub-controls needed).
// Fan is intentionally excluded — it needs a speed slider.
private val GRID_DOMAINS = setOf(
    "switch", "input_boolean", "automation", "script",
    "sensor", "binary_sensor", "lock",
)

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothDashboardScreen(
    onNavigateToSetup: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: BluetoothDashboardViewModel = hiltViewModel(),
) {
    val connectionState       by viewModel.connectionState.collectAsState()
    val entities              by viewModel.filteredEntities.collectAsState()
    val searchQuery           by viewModel.searchQuery.collectAsState()
    val domainFilter          by viewModel.domainFilter.collectAsState()
    val areas                 by viewModel.areas.collectAsState()
    val areaFilter            by viewModel.areaFilter.collectAsState()
    val dashboards            by viewModel.dashboards.collectAsState()
    val activeDashboardIndex  by viewModel.activeDashboardIndex.collectAsState()
    val views                 by viewModel.views.collectAsState()
    val activeViewIndex       by viewModel.activeViewIndex.collectAsState()

    LaunchedEffect(Unit) { viewModel.init() }
    LaunchedEffect(connectionState) {
        if (connectionState == BluetoothDashboardClient.State.INVALID_AUTH) onNavigateToSetup()
    }

    // Hide HA-side panels we cannot/do not want to mirror via BLE.
    val visibleDashboards = remember(dashboards) {
        val hideTitles = setOf("karte", "verlauf", "kalender", "hacs", "terminal",
                                "studio code server", "to-do-listen", "to-do-listen", "media", "medien")
        dashboards.filter { it.title.lowercase() !in hideTitles }
    }
    val activeDashboard = visibleDashboards.getOrNull(
        activeDashboardIndex.coerceAtMost(visibleDashboards.lastIndex.coerceAtLeast(0))
    ) ?: dashboards.getOrNull(activeDashboardIndex)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DashboardDrawerContent(
                dashboards = visibleDashboards,
                activeIndex = activeDashboardIndex,
                onSelect = { idx ->
                    viewModel.selectDashboard(idx)
                    coroutineScope.launch { drawerState.close() }
                },
                onSettings = {
                    coroutineScope.launch { drawerState.close() }
                    onNavigateToSettings()
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            activeDashboard?.title ?: "Home Assistant",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menü")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor         = MaterialTheme.colorScheme.surface,
                        titleContentColor      = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {

                ConnectionBanner(connectionState)

                // View tabs only when the active dashboard has multiple views
                if (views.size > 1) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = activeViewIndex.coerceIn(0, views.lastIndex),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        edgePadding = 12.dp,
                    ) {
                        views.forEachIndexed { index, view ->
                            Tab(
                                selected = index == activeViewIndex,
                                onClick = { viewModel.selectView(index) },
                                text = {
                                    Text(
                                        view.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (index == activeViewIndex) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }

                val isConnecting = connectionState in setOf(
                    BluetoothDashboardClient.State.CONNECTING,
                    BluetoothDashboardClient.State.AUTHENTICATING,
                    BluetoothDashboardClient.State.DISCONNECTED,
                )
                when {
                    entities.isNotEmpty() -> {
                        DashboardBody(
                            entities = entities,
                            areas = areas,
                            selectedArea = areaFilter,
                            onAreaSelect = viewModel::setAreaFilter,
                            onToggle = viewModel::toggle,
                            onBrightness = { id, pct -> viewModel.setBrightness(id, pct) },
                            onFanSpeed   = { id, pct -> viewModel.setFanSpeed(id, pct) },
                        )
                    }
                    isConnecting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                            )
                            Text(
                                if (connectionState == BluetoothDashboardClient.State.AUTHENTICATING)
                                    stringResource(R.string.bt_attempting_authentication)
                                else
                                    stringResource(R.string.bt_dashboard_connecting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                connectionState == BluetoothDashboardClient.State.ERROR -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                stringResource(R.string.bt_failed_connection),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.bt_dashboard_error_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                    else -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.bt_dashboard_no_entities),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Side drawer (hamburger menu) ─────────────────────────────────────────────

@Composable
private fun DashboardDrawerContent(
    dashboards: List<HaDashboardInfo>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor   = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            "Home Assistant",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(20.dp),
        )
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        dashboards.forEachIndexed { idx, dash ->
            NavigationDrawerItem(
                icon = { Icon(dashboardIconFor(dash.title), contentDescription = null) },
                label = { Text(dash.title) },
                selected = idx == activeIndex,
                onClick = { onSelect(idx) },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor      = MaterialTheme.colorScheme.primary,
                    selectedTextColor      = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        NavigationDrawerItem(
            icon = { Icon(Icons.Filled.Settings, null) },
            label = { Text("Einstellungen") },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// Pick a sensible icon based on the dashboard title. Falls back to a generic icon.
private fun dashboardIconFor(title: String): ImageVector = when {
    title.equals("übersicht", ignoreCase = true) ||
        title.equals("ubersicht", ignoreCase = true) ||
        title.equals("home", ignoreCase = true) -> Icons.Filled.Home
    title.contains("bt", ignoreCase = true) || title.contains("bluetooth", ignoreCase = true)
        -> Icons.Filled.Bluetooth
    title.contains("test", ignoreCase = true)  -> Icons.Filled.Devices
    title.contains("energie", ignoreCase = true) || title.contains("energy", ignoreCase = true)
        -> Icons.Filled.Bolt
    title.contains("aktivität", ignoreCase = true) || title.contains("activity", ignoreCase = true)
        -> Icons.Filled.Sensors
    else -> Icons.Filled.HomeWork
}

// ── Dashboard body: greeting + entity tiles + areas grid ─────────────────────

// Maps an entity domain to a string-resource id for the section header. Some
// domains share a section (sensors+binary_sensors → "Sensors", switches+
// input_boolean → "Switches"). Translations live in res/values{,-de}/strings.xml.
private fun sectionResIdFor(domain: String): Int = when (domain) {
    "light"          -> R.string.bt_section_light
    "switch",
    "input_boolean"  -> R.string.bt_section_switch
    "sensor",
    "binary_sensor"  -> R.string.bt_section_sensor
    "climate"        -> R.string.bt_section_climate
    "cover"          -> R.string.bt_section_cover
    "media_player"   -> R.string.bt_section_media_player
    "lock"           -> R.string.bt_section_lock
    "fan"            -> R.string.bt_section_fan
    "automation"     -> R.string.bt_section_automation
    "script"         -> R.string.bt_section_script
    "button"         -> R.string.bt_section_button
    "humidifier"     -> R.string.bt_section_humidifier
    "update"         -> R.string.bt_section_update
    else             -> R.string.bt_section_other
}

private val DOMAIN_SECTION_ORDER = listOf(
    "light", "switch", "input_boolean", "sensor", "binary_sensor",
    "climate", "cover", "media_player", "lock", "fan",
    "automation", "script", "button", "humidifier", "update",
)

@Composable
private fun DashboardBody(
    entities: List<HaEntityState>,
    areas: List<HaArea>,
    selectedArea: String?,
    onAreaSelect: (String?) -> Unit,
    onToggle: (HaEntityState) -> Unit,
    onBrightness: (String, Int) -> Unit,
    onFanSpeed: (String, Int) -> Unit,
) {
    // Group by section-resource-id so domains that share a label end up together.
    val groupedByResId = entities.groupBy { sectionResIdFor(it.domain) }
    val orderedResIds = DOMAIN_SECTION_ORDER.map { sectionResIdFor(it) }.distinct() +
        listOf(R.string.bt_section_other)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        orderedResIds.forEach { resId ->
            val items = groupedByResId[resId] ?: return@forEach
            if (items.isEmpty()) return@forEach
            item(key = "header_$resId") {
                Text(
                    stringResource(resId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp, start = 4.dp),
                )
            }
            val rows = items.chunked(2)
            items(rows, key = { "row_${resId}_${it.first().entityId}" }) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { entity ->
                        HaTileCard(
                            entity = entity,
                            onToggle = { onToggle(entity) },
                            onBrightness = { onBrightness(entity.entityId, it) },
                            onFanSpeed   = { onFanSpeed(entity.entityId, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // Areas section at the bottom (3-column navigation grid)
        if (areas.isNotEmpty()) {
            item(key = "header_areas") {
                Text(
                    stringResource(R.string.bt_section_areas),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
                )
            }
            val areaRows = areas.chunked(3)
            items(areaRows, key = { "area_${it.first().id}" }) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { area ->
                        AreaNavTile(
                            area = area,
                            selected = selectedArea == area.id,
                            onClick = { onAreaSelect(if (selectedArea == area.id) null else area.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AreaNavTile(
    area: HaArea,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = bg,
        contentColor = fg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = areaIconFor(area.name),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                area.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun areaIconFor(name: String): ImageVector = when {
    name.contains("wohn", ignoreCase = true) || name.contains("living", ignoreCase = true)
        -> Icons.Filled.Weekend
    name.contains("küche", ignoreCase = true) || name.contains("kitchen", ignoreCase = true)
        -> Icons.Filled.Kitchen
    name.contains("schlaf", ignoreCase = true) || name.contains("bed", ignoreCase = true)
        -> Icons.Filled.Bed
    name.contains("garten", ignoreCase = true) || name.contains("garden", ignoreCase = true)
        -> Icons.Filled.Grass
    name.contains("bad", ignoreCase = true) || name.contains("bath", ignoreCase = true)
        -> Icons.Filled.WaterDrop
    else -> Icons.Filled.Room
}

// ── Entity list — HA-Lovelace tile grid (2 columns) ──────────────────────────
// All entities are rendered with the same HaTileCard component. Domains are
// grouped under section headers in the order they appear; lights and fans
// expand a slider inline when on. This matches HA's Lovelace `tile` card
// layout: flat, full-width tiles with a circular icon, name + state.

@Composable
private fun EntityList(
    entities: List<HaEntityState>,
    onToggle: (HaEntityState) -> Unit,
    onBrightness: (String, Int) -> Unit,
    onFanSpeed: (String, Int) -> Unit,
) {
    val grouped = entities.groupBy { it.domain }
    val domainOrder = entities.map { it.domain }.distinct()

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        domainOrder.forEach { domain ->
            val group = grouped[domain] ?: return@forEach
            item(key = "header_$domain") { DomainHeader(domain) }
            val rows = group.chunked(2)
            items(rows, key = { "grid_${domain}_${it.first().entityId}" }) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    row.forEach { entity ->
                        HaTileCard(
                            entity = entity,
                            onToggle = { onToggle(entity) },
                            onBrightness = { onBrightness(entity.entityId, it) },
                            onFanSpeed = { onFanSpeed(entity.entityId, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Domain section header ─────────────────────────────────────────────────────

@Composable
private fun DomainHeader(domain: String) {
    Text(
        text = domain.replace("_", " ").replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
}

// ── Compact 2-column tile (switches, automations, sensors…) ──────────────────

@Composable
private fun CompactTile(
    entity: HaEntityState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOn = entity.isActive
    val activeColor = domainActiveColor(entity.domain)
    val iconBg = if (isOn) activeColor.copy(alpha = 0.18f) else Color(0xFFF0F0F0)
    val iconTint = if (isOn) activeColor else Color(0xFF9E9E9E)

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = entity.isControllable, onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOn) activeColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isOn) 0.dp else 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    domainIcon(entity.domain),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column {
                Text(
                    entity.friendlyName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOn) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                val stateLabel = when {
                    entity.unit != null -> "${entity.state} ${entity.unit}"
                    else -> entity.state.replaceFirstChar { it.uppercase() }
                }
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOn) activeColor else Color(0xFF9E9E9E),
                )
            }
        }
    }
}

// ── Full-width entity card (lights, climate, media, cover, fan…) ──────────────
//
// Interaction model (mirrors HA Lovelace more-info):
//   • Tapping the left side (icon + name) shows/hides the slider when one is available.
//   • The Switch controls only the on/off state — it never toggles the slider.
//
// Slider semantics:
//   • Light: brightness_pct 0–100  → service light.turn_on { brightness_pct }
//   • Fan:   percentage     0–100  → service fan.set_percentage { percentage }
//   • onValueChangeFinished sends to HA once on release, avoiding BLE message floods.

@Composable
private fun EntityCard(
    entity: HaEntityState,
    onToggle: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onFanSpeedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSlider = entity.supportsBrightness || entity.supportsFanSpeed
    var showSlider by remember(entity.entityId) { mutableStateOf(false) }

    // Local slider values update immediately during drag; HA is notified on release only.
    // Keys include the HA attribute value so the slider snaps to server state after updates.
    var localBrightness by remember(entity.entityId, entity.brightnessPercent) {
        mutableFloatStateOf(entity.brightnessPercent.toFloat())
    }
    var localFanPct by remember(entity.entityId, entity.fanPercentage) {
        mutableFloatStateOf((entity.fanPercentage ?: 0).toFloat())
    }

    val isOn = entity.isActive
    val activeColor = domainActiveColor(entity.domain)
    val iconBg = if (isOn) activeColor.copy(alpha = 0.18f) else Color(0xFFF0F0F0)
    val iconTint = if (isOn) activeColor else Color(0xFF9E9E9E)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                // Left area: icon + name. Tapping here opens/closes the slider.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (hasSlider) Modifier.clickable { showSlider = !showSlider }
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(iconBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            domainIcon(entity.domain),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column {
                        Text(
                            entity.friendlyName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Subtitle: show current value for sliders, state+unit for sensors,
                        // entity ID (monospace) otherwise — mirrors EntityRow in the main app.
                        val subtitle = when {
                            entity.supportsBrightness ->
                                "${localBrightness.toInt()} %"
                            entity.supportsFanSpeed ->
                                "${localFanPct.toInt()} %"
                            entity.unit != null ->
                                "${entity.state} ${entity.unit}"
                            else -> entity.entityId
                        }
                        val subtitleFont = if (!entity.supportsBrightness && !entity.supportsFanSpeed && entity.unit == null)
                            FontFamily.Monospace else FontFamily.Default
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = subtitleFont,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Right: Switch (toggle only) or read-only state value
                if (entity.isControllable) {
                    Switch(
                        checked = isOn,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor    = Color.White,
                            checkedTrackColor    = activeColor,
                            uncheckedThumbColor  = Color.White,
                            uncheckedTrackColor  = Color(0xFFE0E0E0),
                            uncheckedBorderColor = Color(0xFFBDBDBD),
                        ),
                    )
                } else {
                    Text(
                        if (entity.unit != null) "${entity.state} ${entity.unit}" else entity.state,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = activeColor.takeIf { isOn } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Brightness slider (light) ─────────────────────────────────────
            // Range 0–100 % → sent as brightness_pct to light.turn_on
            AnimatedVisibility(
                visible = showSlider && entity.supportsBrightness,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.bt_brightness, localBrightness.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = localBrightness,
                        onValueChange = { localBrightness = it },
                        onValueChangeFinished = { onBrightnessChange(localBrightness.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor       = activeColor,
                            activeTrackColor = activeColor,
                        ),
                    )
                }
            }

            // ── Fan speed slider (fan) ────────────────────────────────────────
            // Range 0–100 % → sent as percentage to fan.set_percentage
            AnimatedVisibility(
                visible = showSlider && entity.supportsFanSpeed,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                // steps: discrete positions between 0 and 100 exclusive.
                // E.g. percentage_step=25 → values 0,25,50,75,100 → steps=3
                val steps = if (entity.fanPercentageStep > 1)
                    (100 / entity.fanPercentageStep) - 1 else 0

                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.bt_fan_speed, localFanPct.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = localFanPct,
                        onValueChange = { localFanPct = it },
                        onValueChangeFinished = { onFanSpeedChange(localFanPct.toInt()) },
                        valueRange = 0f..100f,
                        steps = steps,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor       = activeColor,
                            activeTrackColor = activeColor,
                        ),
                    )
                }
            }
        }
    }
}

// ── Connection banner ─────────────────────────────────────────────────────────

@Composable
private fun ConnectionBanner(state: BluetoothDashboardClient.State) {
    val (textRes, bgColor) = when (state) {
        BluetoothDashboardClient.State.CONNECTED      -> return
        BluetoothDashboardClient.State.CONNECTING     -> R.string.bt_dashboard_connecting to Color(0xFFFFF9C4)
        BluetoothDashboardClient.State.AUTHENTICATING -> R.string.bt_attempting_authentication to Color(0xFFFFF9C4)
        BluetoothDashboardClient.State.ERROR          -> R.string.bt_failed_connection to Color(0xFFFFEBEE)
        BluetoothDashboardClient.State.DISCONNECTED   -> R.string.bt_dashboard_connecting to Color(0xFFFFF9C4)
        BluetoothDashboardClient.State.INVALID_AUTH   -> R.string.bt_failed_connection to Color(0xFFFFEBEE)
    }
    val textColor = when (state) {
        BluetoothDashboardClient.State.ERROR,
        BluetoothDashboardClient.State.INVALID_AUTH -> Color(0xFFB71C1C)
        else                                         -> Color(0xFF5D4037)
    }
    Surface(color = bgColor, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

// ── Area filter row (HA Lovelace-style prominent pill chips) ─────────────────

@Composable
private fun AreaFilterRow(
    areas: List<HaArea>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            AreaChip(
                label = "Alle",
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(areas, key = { it.id }) { area ->
            AreaChip(
                label = area.name,
                selected = selected == area.id,
                onClick = { onSelect(if (selected == area.id) null else area.id) },
            )
        }
    }
}

@Composable
private fun AreaChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Room, null, modifier = Modifier.size(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

// ── Domain filter row ─────────────────────────────────────────────────────────

@Composable
private fun DomainFilterRow(selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(DOMAIN_FILTER_ORDER) { domain ->
            val label = domain?.replace("_", " ")?.replaceFirstChar { it.uppercase() }
                ?: stringResource(R.string.bt_dashboard_domain_all)
            FilterChip(
                selected = selected == domain,
                onClick = { onSelect(domain) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (domain != null) ({
                    Icon(domainIcon(domain), null, modifier = Modifier.size(14.dp))
                }) else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor   = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor       = MaterialTheme.colorScheme.primary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == domain,
                    selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    borderColor = Color(0xFFDEDEDE),
                ),
            )
        }
    }
}
