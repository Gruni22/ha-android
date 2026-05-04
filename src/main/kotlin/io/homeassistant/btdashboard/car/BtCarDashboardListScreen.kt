package io.homeassistant.btdashboard.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import io.homeassistant.btdashboard.dashboard.HaDashboardInfo

/**
 * Lists every "AA" dashboard so the driver can switch between them.
 * Tap pops back to the entity screen with the new dashboard active.
 */
class BtCarDashboardListScreen(
    carContext: CarContext,
    private val dashboards: List<HaDashboardInfo>,
    private val currentIndex: Int,
    private val onSelect: (Int) -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().apply {
            dashboards.forEachIndexed { i, dash ->
                val aaViews = dash.views.filter {
                    it.title.contains("aa", ignoreCase = true) ||
                        it.path.contains("aa", ignoreCase = true)
                }
                val entityCount = aaViews.sumOf { it.entityIds.size }
                val viewNames = aaViews.joinToString { it.title }
                addItem(
                    Row.Builder()
                        .setTitle(if (i == currentIndex) "✓  ${dash.title}" else dash.title)
                        .addText(if (viewNames.isNotBlank()) viewNames else "Keine AA-Views")
                        .addText("$entityCount Geräte")
                        .setOnClickListener {
                            onSelect(i)
                            screenManager.pop()
                        }
                        .build()
                )
            }
        }.build()

        return ListTemplate.Builder()
            .setHeaderAction(Action.BACK)
            .setTitle("Dashboard wählen")
            .setSingleList(list)
            .build()
    }
}
