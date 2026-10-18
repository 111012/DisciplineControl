package com.discipline.control

import android.app.Application
import com.discipline.control.core.Notifier
import com.discipline.control.core.Store
import com.discipline.control.core.Overlay
import com.discipline.control.service.GuardService

class ControlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Overlay.hide()
        Store.init(this)
        Notifier.init(this)
        GuardService.startSafely(this)
        GuardService.scheduleKeepAlive(this)
    }
}
