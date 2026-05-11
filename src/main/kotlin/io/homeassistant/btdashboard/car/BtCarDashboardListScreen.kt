package io.github.gruni22.btdashboard.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import io.github.gruni22.btdashboard.R
import io.github.gruni22.btdashboard.dashboard.HaDashboardInfo

/**
 * Lists every dashboard Home Assistant has exposed (DASH_*-label-derived) so the
 * driver can switch between them. Tap pops back to the entity screen with
 * the new dashboard active.
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
                val entityCount = dash.views.sumOf { it.entityIds.size }
                val viewNames = dash.views.joinToString { it.title }
                addItem(
                    Row.Builder()
                        .setTitle(if (i == currentIndex) "✓  ${dash.title}" else dash.title)
                        .addText(if (viewNames.isNotBlank()) viewNames else carContext.getString(R.string.bt_aa_no_views))
                        .addText(carContext.getString(R.string.bt_aa_devices_count, entityCount))
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
            .setTitle(carContext.getString(R.string.bt_aa_dashboard_picker_title))
            .setSingleList(list)
            .build()
    }
}
