package io.github.gruni22.btdashboard.car

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
import io.github.gruni22.btdashboard.R
import io.github.gruni22.btdashboard.dashboard.BluetoothDashboardClient
import io.github.gruni22.btdashboard.dashboard.HaDashboardInfo
import io.github.gruni22.btdashboard.dashboard.HaEntityState
import io.github.gruni22.btdashboard.dashboard.toggleAction
import io.github.gruni22.btdashboard.service.BleConnectionService
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
 *   - Title bar shows the active dashboard name
 *   - Action strip: [Dashboard] switcher when more than one dashboard exists
 *   - List of entities from the active dashboard's views
 *   - Tap on a Light row → BtCarLightDetailScreen with brightness +/- controls
 *
 * The Pi-side integration already groups entities by `DASH_*` labels into the
 * `dashboards` flow; we just render those as-is so AA matches the phone's
 * label-derived grouping.
 */
class BtCarEntityScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var service: BleConnectionService? = null

    private var status = Status.CONNECTING
    private var dashboards: List<HaDashboardInfo> = emptyList()
    private var allEntities: List<HaEntityState> = emptyList()
    private var activeIndex: Int = 0  // index into dashboards

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
                ents to dashes
            }.collect { (ents, dashes) ->
                allEntities = ents
                dashboards = dashes
                if (activeIndex >= dashboards.size) activeIndex = 0
                invalidate()
            }
        }
    }

    private fun visibleEntities(): List<HaEntityState> {
        val activeDash = dashboards.getOrNull(activeIndex)
        val ids: Set<String> = activeDash?.views?.flatMap { it.entityIds }?.toSet().orEmpty()
        val filtered = if (ids.isNotEmpty()) {
            allEntities.filter { it.entityId in ids }
        } else if (dashboards.isEmpty()) {
            allEntities  // no dashboards configured → fallback so the screen isn't empty
        } else {
            emptyList()  // selected dashboard has no entities yet
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
        .setTitle(carContext.getString(R.string.bt_dashboard_title))
        .setHeaderAction(Action.APP_ICON)
        .setSingleList(
            ItemList.Builder()
                .addItem(Row.Builder().setTitle(carContext.getString(R.string.bt_aa_setup_not_yet))
                    .addText(carContext.getString(R.string.bt_aa_setup_not_yet_hint)).build())
                .build()
        )
        .build()

    private fun errorTemplate(): Template = ListTemplate.Builder()
        .setTitle(carContext.getString(R.string.bt_dashboard_title))
        .setHeaderAction(Action.APP_ICON)
        .setActionStrip(ActionStrip.Builder()
            .addAction(Action.Builder().setTitle(carContext.getString(R.string.bt_aa_retry))
                .setOnClickListener { service?.connect() }.build())
            .build())
        .setSingleList(ItemList.Builder()
            .addItem(Row.Builder().setTitle(carContext.getString(R.string.bt_failed_connection)).build())
            .build())
        .build()

    private fun entityListTemplate(): Template {
        val entities = visibleEntities()
        val title = dashboards.getOrNull(activeIndex)?.title
            ?: carContext.getString(R.string.bt_dashboard_title)
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
                    .setTitle(carContext.getString(R.string.bt_aa_no_entities_title))
                    .addText(carContext.getString(R.string.bt_aa_no_entities_for_dashboard, title))
                    .build()
            )
        }

        // Android Auto's ListTemplate allows at most ONE action with a custom title
        // in its ActionStrip. We prioritise the dashboard switcher (only meaningful
        // when there are several AA dashboards). Refresh is dropped — STATE_CHANGE
        // pushes from HA keep the entity list up-to-date automatically anyway.
        val actionStrip = if (dashboards.size > 1) {
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.bt_aa_action_dashboard))
                        .setOnClickListener {
                            screenManager.push(
                                BtCarDashboardListScreen(
                                    carContext, dashboards, activeIndex,
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

    private fun stateText(e: HaEntityState): String {
        fun s(id: Int) = carContext.getString(id)
        return when (e.domain) {
            "light" -> if (e.isActive) {
                if (e.supportsBrightness) carContext.getString(R.string.bt_state_on_with_brightness, e.brightnessPercent)
                else s(R.string.bt_state_on)
            } else s(R.string.bt_state_off)
            "switch", "input_boolean", "automation", "fan", "humidifier", "script"
                -> if (e.isActive) s(R.string.bt_state_on) else s(R.string.bt_state_off)
            "lock" -> if (e.isActive) s(R.string.bt_state_unlocked) else s(R.string.bt_state_locked)
            "cover" -> if (e.isActive) s(R.string.bt_state_open) else s(R.string.bt_state_closed)
            "binary_sensor" -> if (e.isActive) s(R.string.bt_state_detected) else s(R.string.bt_state_clear)
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
                "cleaning"  -> s(R.string.bt_vacuum_cleaning)
                "returning" -> s(R.string.bt_vacuum_returning)
                "paused"    -> s(R.string.bt_vacuum_paused)
                "docked"    -> s(R.string.bt_vacuum_docked)
                "idle"      -> s(R.string.bt_vacuum_idle)
                "error"     -> s(R.string.bt_vacuum_error)
                else        -> e.state
            }
            "scene" -> s(R.string.bt_state_scene)
            "media_player" -> when (e.state) {
                "playing" -> e.attributes["media_title"]?.toString() ?: s(R.string.bt_media_playing)
                "paused"  -> s(R.string.bt_media_paused)
                "idle"    -> s(R.string.bt_media_idle)
                "off"     -> s(R.string.bt_state_off)
                else      -> e.state
            }
            else -> if (e.unit != null) "${e.state} ${e.unit}" else e.state
        }
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
