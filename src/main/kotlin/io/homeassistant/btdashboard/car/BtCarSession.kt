package io.github.gruni22.btdashboard.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class BtCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = BtCarEntityScreen(carContext)
}
