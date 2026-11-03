package com.nighticon.ui

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App：绑定 LSPosed 下发的 XposedService，用于写远程偏好 / 远程文件。
 */
class NightIconApp : Application() {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                NightIconApp.service = service
                listeners.forEach { it(service) }
            }

            override fun onServiceDied(service: XposedService) {
                if (NightIconApp.service === service) NightIconApp.service = null
                listeners.forEach { it(null) }
            }
        })
    }

    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        val listeners = mutableListOf<(XposedService?) -> Unit>()
    }
}
