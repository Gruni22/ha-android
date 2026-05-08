package io.homeassistant.btdashboard.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import io.homeassistant.btdashboard.dashboard.HaEntityState
import io.homeassistant.btdashboard.service.BleConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Detail screen for a single light. Android Auto disallows scrubbable sliders,
 * so brightness is adjusted with discrete buttons. Layout uses PaneTemplate:
 *   - Pane has 2 actions (−10% / +10%) — Pane allows up to 2 actions
 *   - ActionStrip has 1 action (On/Off toggle) — strip allows 1 with title
 */
class BtCarLightDetailScreen(
    carContext: CarContext,
    private val service: BleConnectionService,
    private val initialEntityId: String,
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var entity: HaEntityState? = null

    init {
        scope.launch {
            service.entities.collect { list ->
                val updated = list.firstOrNull { it.entityId == initialEntityId }
                if (updated != null && updated != entity) {
                    entity = updated
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val e = entity ?: service.entities.value.firstOrNull { it.entityId == initialEntityId }
        entity = e
        if (e == null) {
            return PaneTemplate.Builder(
                Pane.Builder()
                    .addRow(Row.Builder().setTitle("Entity nicht gefunden").build())
                    .build()
            )
                .setHeaderAction(Action.BACK)
                .setTitle(initialEntityId)
                .build()
        }

        val isOn = e.isActive
        val brightness = e.brightnessPercent
        val state = if (isOn) {
            if (e.supportsBrightness) "An · $brightness%" else "An"
        } else "Aus"

        val pane = Pane.Builder().apply {
            addRow(Row.Builder().setTitle("Status").addText(state).build())
            if (isOn && e.supportsBrightness) {
                addAction(
                    Action.Builder()
                        .setTitle("− 10%")
                        .setOnClickListener { adjustBrightness(e, -10) }
                        .build()
                )
                addAction(
                    Action.Builder()
                        .setTitle("+ 10%")
                        .setOnClickListener { adjustBrightness(e, +10) }
                        .build()
                )
            }
        }.build()

        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.BACK)
            .setTitle(e.friendlyName)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(if (isOn) "Aus" else "An")
                            .setOnClickListener { toggle(e) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun adjustBrightness(e: HaEntityState, delta: Int) {
        val target = (e.brightnessPercent + delta).coerceIn(0, 100)
        scope.launch {
            runCatching {
                service.callService(
                    "light", "turn_on", e.entityId,
                    mapOf("brightness_pct" to target), e.sourceDeviceId,
                )
            }.onFailure { Timber.e(it, "BtCar: brightness adjust failed") }
        }
    }

    private fun toggle(e: HaEntityState) {
        val s = if (e.isActive) "turn_off" else "turn_on"
        scope.launch {
            runCatching {
                service.callService(e.domain, s, e.entityId, sourceDeviceId = e.sourceDeviceId)
            }.onFailure { Timber.e(it, "BtCar: toggle failed") }
        }
    }
}
