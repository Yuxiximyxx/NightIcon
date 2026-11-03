package com.nighticon.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed 模块入口（libxposed API 101，列于 META-INF/xposed/java_init.list）。
 * 作用域只有荣耀桌面 com.hihonor.android.launcher；不碰锁屏、壁纸、SystemUI。
 */
class ModuleEntry : XposedModule() {

    companion object {
        const val LAUNCHER_PACKAGE = "com.hihonor.android.launcher"
    }

    private var processName: String? = null
    private var hook: IconSwitchHook? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        log(Log.INFO, IconSwitchHook.TAG, "模块已加载：process=${param.processName} framework=$frameworkName $frameworkVersion")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 只在桌面主进程安装（桌面的其它子进程不处理图标）
        if (param.packageName != LAUNCHER_PACKAGE || !param.isFirstPackage) return
        if (processName != LAUNCHER_PACKAGE) {
            log(Log.INFO, IconSwitchHook.TAG, "跳过非桌面主进程：$processName")
            return
        }
        runCatching { hook = IconSwitchHook(this).also { it.installInLauncher(param) } }
            .onFailure { log(Log.ERROR, IconSwitchHook.TAG, "桌面 Hook 初始化失败", it) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != LAUNCHER_PACKAGE || !param.isFirstPackage) return
        runCatching { hook?.onLauncherReady(param.classLoader) }
    }
}
