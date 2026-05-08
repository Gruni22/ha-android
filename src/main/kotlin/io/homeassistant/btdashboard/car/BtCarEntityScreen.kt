package io.homeassistant.btdashboard.car

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import io.homeassistant.btdashboard.dashboard.BluetoothDashboardClient
import io.homeassistant.btdashboard.dashboard.HaDashboardInfo
import io.homeassistant.btdashboard.dashboard.HaEntityState
import io.homeassistant.btdashboard.dashboard.toggleAction
import io.homeassistant.btdashboard.service.BleConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main Android Auto screen.
 *
 * Layout:
 *   - Title bar shows the active AA dashboard name
 *   - Action strip: [Dashboard] (switch between AA dashboards) + [Refresh]
 *   - List of entities from the active AA dashboard's views
 *   - Tap on a Light row → BtCarLightDetailScreen with brightness +/- controls
 *
 * Filter rule: only dashboards whose title/url-path/view-title/view-path contain
 * "aa" (case-insensitive) are shown. If no AA dashboard exists, falls back to
 * showing every entity so the head unit isn't empty.
 */
class BtCarEntityScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var service: BleConnectionService? = null

    private var status = Status.CONNECTING
    private var aaDashboards: List<HaDashboardInfo> = emptyList()
    private var allEntities: List<HaEntityState> = emptyList()
    private var activeIndex: Int = 0  // index into aaDashboards

    private enum class Status { CONNECTING, CONNECTED, ERROR, NO_SERVICE }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BleConnectionService.BleConnectionBinder).getService()
            Timber.d("BtCar: service connected")
            observeService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            status = Status.NO_SERVICE
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val intent = Intent(carContext, BleConnectionService::class.java)
                carContext.startForegroundService(intent)
                carContext.bindService(intent, serviceConn, CarContext.BIND_AUTO_CREATE)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
                runCatching { carContext.unbindService(serviceConn) }
            }
        })
    }

    private fun observeService() {
        val svc = service ?: return
        scope.launch {
            svc.connectionState.collect { state ->
                status = when (state) {
                    BluetoothDashboardClient.State.CONNECTED -> Status.CONNECTED
                    BluetoothDashboardClient.State.ERROR -> Status.ERROR
                    else -> Status.CONNECTING
                }
                invalidate()
            }
        }
        scope.launch {
            // Use allEntities (full DB) instead of svc.entities (phone-view-filtered)
            // so AA's dashboard selection is independent from the phone's.
            kotlinx.coroutines.flow.combine(svc.allEntities, svc.dashboards) { ents, dashes ->
                ents to filterAaDashboards(dashes)
            }.collect { (ents, aa) ->
                allEntities = ents
                aaDashboards = aa
                if (activeIndex >= aaDashboards.size) activeIndex = 0
                invalidate()
            }
        }
    }

    /** Match "aa" anywhere in dashboard title/url-path/view title/view path. */
    private fun filterAaDashboards(dashboards: List<HaDashboardInfo>): List<HaDashboardInfo> {
        fun isAa(s: String) = s.contains("aa", ignoreCase = true)
        return dashboards.filter { dash ->
            isAa(dash.title) || isAa(dash.urlPath) ||
                dash.views.any { isAa(it.title) || isAa(it.path) }
        }
    }

    private fun visibleEntities(): List<HaEntityState> {
        val activeDash = aaDashboards.getOrNull(activeIndex)
        val aaViews = activeDash?.views?.filter {
            it.title.contains("aa", ignoreCase = true) ||
                it.path.contains("aa", ignoreCase = true)
        }.orEmpty()
        val ids: Set<String> = aaViews.flatMap { it.entityIds }.toSet()
        val filtered = if (ids.isNotEmpty()) {
            allEntities.filter { it.entityId in ids }
        } else if (aaDashboards.isEmpty()) {
            allEntities  // no AA dashboard at all → fallback
        } else {
            emptyList()  // selected AA dashboard has no entities yet
        }
        return filtered
            .filter { it.domain in SUPPORTED_DOMAINS }
            .sortedWith(compareBy(
                { DOMAIN_ORDER.indexOf(it.domain).takeIf { i -> i >= 0 } ?: 99 },
                { it.friendlyName },
            ))
    }

    override fun onGetTemplate(): Template = when {
        status == Status.NO_SERVICE -> noServiceTemplate()
        status == Status.CONNECTING && allEntities.isEmpty() -> connectingTemplate()
        status == Status.ERROR && allEntities.isEmpty() -> errorTemplate()
        else -> entityListTemplate()
    }

    private fun connectingTemplate(): Template = ListTemplate.Builder()
        .setTitle("Home Assistant")
        .setLoading(true)
        .setHeaderAction(Action.APP_ICON)
        .build()

    private fun noServiceTemplate(): Template = ListTemplate.Builder()
        .setTitle("Home Assistant")
        .setHeaderAction(Action.APP_ICON)
        .setSingleList(
            ItemList.Builder()
                .addItem(Row.Builder().setTitle("Nicht eingerichtet")
                    .addText("Öffne die Phone-App, um Home Assistant zu verbinden.").build())
                .build()
        )
        .build()

    private fun errorTemplate(): Template = ListTemplate.Builder()
        .setTitle("Home Assistant")
        .setHeaderAction(Action.APP_ICON)
        .setActionStrip(ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Erneut")
                .setOnClickListener { service?.connect() }.build())
            .build())
        .setSingleList(ItemList.Builder()
            .addItem(Row.Builder().setTitle("Verbindung fehlgeschlagen").build())
            .build())
        .build()

    private fun entityListTemplate(): Template {
        val entities = visibleEntities()
        val title = aaDashboards.getOrNull(activeIndex)?.title ?: "Home Assistant"
        val itemList = ItemList.Builder()
        // Controllable entities. AA disallows a row with BOTH toggle and browsable
        // — so lights with brightness become browsable (tap opens a detail screen
        // with toggle + brightness +/- buttons). Other entities get an inline toggle.
        entities.filter { it.isControllable }.forEach { entity ->
            val isOn = entity.isActive
            val isLightWithBrightness = entity.domain == "light" && entity.supportsBrightness
            val row = Row.Builder()
                .setTitle(entity.friendlyName)
                .addText(stateText(entity))
            if (isLightWithBrightness) {
                row.setBrowsable(true)
                row.setOnClickListener {
                    val svc = service ?: return@setOnClickListener
                    screenManager.push(BtCarLightDetailScreen(carContext, svc, entity.entityId))
                }
            } else {
                // Use the shared toggleAction() helper so AA matches the phone
                // dashboard exactly (cover open/close, scene one-shot, vacuum
                // start/pause, …). Pass entity.sourceDeviceId so multi-instance
                // routing sends the call to the right gateway.
                val action = entity.toggleAction()
                if (action != null) {
                    row.setToggle(Toggle.Builder { _ ->
                        scope.launch {
                            val svc = service ?: return@launch
                            runCatching {
                                svc.callService(
                                    action.first, action.second, entity.entityId,
                                    sourceDeviceId = entity.sourceDeviceId,
                                )
                            }.onFailure { Timber.e(it, "BtCar: toggle ${entity.entityId} failed") }
                        }
                    }.setChecked(isOn).build())
                }
            }
            itemList.addItem(row.build())
        }
        // Read-only sensors below
        entities.filter { !it.isControllable }.take(MAX_SENSOR_ROWS).forEach { entity ->
            itemList.addItem(
                Row.Builder()
                    .setTitle(entity.friendlyName)
                    .addText(stateText(entity))
                    .build()
            )
        }
        if (itemList.build().items.isEmpty()) {
            itemList.addItem(
                Row.Builder()
                    .setTitle("Keine Geräte")
                    .addText("Im Dashboard \"$title\" sind noch keine Entities konfiguriert.")
                    .build()
            )
        }

        // Android Auto's ListTemplate allows at most ONE action with a custom title
        // in its ActionStrip. We prioritise the dashboard switcher (only meaningful
        // when there are several AA dashboards). Refresh is dropped — STATE_CHANGE
        // pushes from HA keep the entity list up-to-date automatically anyway.
        val actionStrip = if (aaDashboards.size > 1) {
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Dashboard")
                        .setOnClickListener {
                            screenManager.push(
                                BtCarDashboardListScreen(
                                    carContext, aaDashboards, activeIndex,
                                ) { newIndex ->
                                    activeIndex = newIndex
                                    invalidate()
                                }
                            )
                        }.build()
                )
                .build()
        } else null

        val builder = ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemList.build())
        if (actionStrip != null) builder.setActionStrip(actionStrip)
        return builder.build()
    }

    private fun stateText(e: HaEntityState): String = when (e.domain) {
        "light" -> if (e.isActive) {
            if (e.supportsBrightness) "An · ${e.brightnessPercent}%" else "An"
        } else "Aus"
        "switch", "input_boolean", "automation", "fan", "humidifier", "script"
            -> if (e.isActive) "An" else "Aus"
        "lock" -> if (e.isActive) "Entriegelt" else "Verriegelt"
        "cover" -> if (e.isActive) "Offen" else "Geschlossen"
        "binary_sensor" -> if (e.isActive) "Erkannt" else "Klar"
        "climate" -> {
            val cur = e.currentTemperature
            val tgt = e.targetTemperature
            buildString {
                if (cur != null) append("${"%.1f".format(cur)}°")
                if (tgt != null) {
                    if (isNotEmpty()) append(" → ")
                    append("${"%.1f".format(tgt)}°")
                }
                if (isEmpty()) append(e.state)
            }
        }
        "vacuum" -> when (e.state) {
            "cleaning"  -> "Saugt"
            "returning" -> "Fährt zur Station"
            "paused"    -> "Pausiert"
            "docked"    -> "An Station"
            "idle"      -> "Bereit"
            "error"     -> "Fehler"
            else        -> e.state
        }
        "scene" -> "Szene"
        "media_player" -> when (e.state) {
            "playing" -> e.attributes["media_title"]?.toString() ?: "Spielt"
            "paused"  -> "Pausiert"
            "idle"    -> "Bereit"
            "off"     -> "Aus"
            else      -> e.state
        }
        else -> if (e.unit != null) "${e.state} ${e.unit}" else e.state
    }

    companion object {
        private const val MAX_SENSOR_ROWS = 6
        // Domains that AA's row-toggle can sensibly drive (binary on/off semantic).
        // Excludes number/select/humidifier-target/cover-position — those need
        // sliders/dropdowns AA can't render in a ListTemplate row.
        private val SUPPORTED_DOMAINS = setOf(
            "light", "switch", "input_boolean", "cover",
            "lock", "fan", "climate", "media_player",
            "automation", "script", "scene", "vacuum",
            "humidifier",
            "sensor", "binary_sensor",
        )
        private val DOMAIN_ORDER = listOf(
            "light", "switch", "input_boolean",
            "climate", "fan", "humidifier",
            "cover", "lock", "media_player",
            "vacuum", "scene", "script", "automation",
            "sensor", "binary_sensor",
        )
    }
}
