package com.nighticon.xposed

import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.nighticon.data.Keys
import com.nighticon.logic.HookLogic
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * 桌面进程（com.hihonor.android.launcher）内的全部 Hook。
 *
 * 只用反编译中确认存在的类/方法（见 docs/ANALYSIS.md）：
 * - hihonor.android.hwutil.ZipFileCache.getAndCheckCachedZipFile(String,String) / (String,String,String)
 * - android.content.res.HwResourcesImpl.updateConfiguration(Configuration,int)
 * - android.content.res.HwResourcesImpl.getDarkerBitmap(Bitmap)（private）
 * - HwResourcesImpl 静态字段 needReloadMask/needReloadBorder/needReloadBg/isBackgroundLoaded/sBgList
 * - hihonor.android.hncube.ZipResourcesAdapter.sIsNeedCheckMultiLevel
 * - 桌面：com.hihonor.android.launcher.IconCache.getInstace()/clearCacheAndDb(boolean)、
 *   Launcher.getLauncher()/updateIconCache()、utils.LoadIconUtil.launchUpdateIconTask(List,Runnable)
 * 任一类/方法缺失只打日志，不抛异常。
 */
class IconSwitchHook(private val module: XposedModule) {

    companion object {
        const val TAG = "NightIcon"
        private const val ZIP_FILE_CACHE = "hihonor.android.hwutil.ZipFileCache"
        private const val HW_RES_IMPL = "android.content.res.HwResourcesImpl"
        private const val ZIP_RES_ADAPTER = "hihonor.android.hncube.ZipResourcesAdapter"
        private const val L_ICON_CACHE = "com.hihonor.android.launcher.IconCache"
        private const val L_LAUNCHER = "com.hihonor.android.launcher.Launcher"
        private const val L_LOAD_ICON_UTIL = "com.hihonor.android.launcher.utils.LoadIconUtil"

        /** getDarkerBitmap 的调用方（反编译所见），可能被 ART 内联，需要 deoptimize */
        private val DARKER_CALLERS = setOf(
            "getThemeIcon", "handleRoundCornerAndBg", "handleAddIconBackground",
            "resizeAdaptiveIcon", "getDrawableForDynamic",
        )
    }

    // ---------------- 状态 ----------------
    @Volatile private var enabled = false
    /** null = 尚未确定（首次使用时从全局配置读） */
    @Volatile private var night: Boolean? = null
    /** 已落地并校验过的槽位目录；null = 未就绪，此时不改道（走系统原主题） */
    private val readyDir = arrayOfNulls<String>(Keys.Slot.entries.size)

    private lateinit var baseDir: String
    private var appClassLoader: ClassLoader? = null
    private var prefs: SharedPreferences? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "NightIcon-sync") }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val darkerCalls = AtomicInteger()
    private val darkerSkipped = AtomicInteger()
    @Volatile private var lastSwapLog: String? = null
    @Volatile private var lastRestartToken: Long = 0
    @Volatile private var lastUiModeCheck: String? = null

    /** 必须持有强引用，否则监听器可能被回收 */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        i("远程偏好变化：key=$key")
        if (key == Keys.RESTART_TOKEN) {
            val t = p.getLong(Keys.RESTART_TOKEN, 0)
            if (t != 0L && t != lastRestartToken) {
                w("收到「重启桌面」指令，桌面进程自杀（系统会自动拉起桌面）")
                Process.killProcess(Process.myPid())
            }
            return@OnSharedPreferenceChangeListener
        }
        if (key == Keys.APPLY_TOKEN || key == Keys.ENABLED) {
            worker.execute { syncAndApply(refreshLauncher = true) }
        }
    }

    // ---------------- 入口 ----------------
    fun installInLauncher(param: PackageLoadedParam) {
        baseDir = param.applicationInfo.dataDir + "/files/nighticon"
        appClassLoader = param.defaultClassLoader
        i("开始安装 Hook：baseDir=$baseDir")

        prefs = runCatching { module.getRemotePreferences(Keys.REMOTE_GROUP) }
            .onFailure { e("无法获取远程偏好（模块配置将无法生效）", it) }.getOrNull()
        prefs?.let {
            lastRestartToken = it.getLong(Keys.RESTART_TOKEN, 0)
            enabled = it.getBoolean(Keys.ENABLED, false)
            it.registerOnSharedPreferenceChangeListener(prefListener)
        }
        i("总开关=${enabled}")

        hookZipFileCache()
        hookUpdateConfiguration()
        hookGetDarkerBitmap()

        // 首次同步放后台：几十 MB 拷贝不能卡桌面启动；同步完成前不改道
        worker.execute { syncAndApply(refreshLauncher = false) }
    }

    /** onPackageReady 时拿到真正的 App ClassLoader（桌面类用它反射） */
    fun onLauncherReady(cl: ClassLoader) { appClassLoader = cl }

    // ---------------- Hook 1：ZipFileCache 改道 ----------------
    private fun hookZipFileCache() {
        val cls = findClass(ZIP_FILE_CACHE, null) ?: return
        val targets = cls.declaredMethods.filter {
            it.name == "getAndCheckCachedZipFile" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.all { p -> p == String::class.java } &&
                (it.parameterTypes.size == 2 || it.parameterTypes.size == 3)
        }
        if (targets.isEmpty()) { w("未找到 $ZIP_FILE_CACHE.getAndCheckCachedZipFile，跳过改道"); return }
        for (m in targets) {
            runCatching {
                module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val args = chain.args
                    val path = args[0] as String?
                    val zip = args[1] as String?
                    val target = redirectTarget(path, zip)
                    if (target == null) {
                        chain.proceed()
                    } else {
                        logSwap(args.size, path, zip, target)
                        val newArgs = args.toTypedArray()
                        newArgs[0] = target
                        chain.proceed(newArgs)
                    }
                }
                i("已 Hook ZipFileCache.getAndCheckCachedZipFile(${m.parameterTypes.size} 参)")
            }.onFailure { e("Hook ZipFileCache(${m.parameterTypes.size} 参) 失败", it) }
        }
    }

    private fun redirectTarget(path: String?, zip: String?): String? {
        if (!enabled || !HookLogic.shouldRedirect(path, zip)) return null
        return readyDir[currentSlot().ordinal]
    }

    private fun logSwap(argc: Int, from: String?, zip: String?, to: String) {
        val msg = "路径替换[${argc}参] $from/$zip → $to/$zip"
        // 每次都写 logcat（调试 tag NightIcon）；LSPosed 日志只在内容变化时写，避免刷屏
        Log.d(TAG, msg)
        if (msg != lastSwapLog) { lastSwapLog = msg; module.log(Log.INFO, TAG, msg) }
    }

    // ---------------- Hook 2：深浅翻转 → 软重置 ----------------
    private fun hookUpdateConfiguration() {
        val cls = findClass(HW_RES_IMPL, null) ?: return
        val m = findMethod(cls, "updateConfiguration", Configuration::class.java, Int::class.javaPrimitiveType!!) ?: return
        runCatching {
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                val cfg = chain.args[0] as? Configuration
                if (cfg != null) onConfig(cfg, chain.thisObject)
                result
            }
            i("已 Hook HwResourcesImpl.updateConfiguration")
        }.onFailure { e("Hook updateConfiguration 失败", it) }
    }

    private fun onConfig(cfg: Configuration, impl: Any?) {
        val argNight = HookLogic.isNight(cfg.uiMode)
        // 对比 mResourcesImpl 的 uiMode（ResourcesImpl 可能被强制改回白天，见 ANALYSIS §2.9）
        val implMode = runCatching {
            val f = impl!!.javaClass.getDeclaredField("mResourcesImpl").apply { isAccessible = true }
            val ri = f.get(impl)!!
            (ri.javaClass.getMethod("getConfiguration").invoke(ri) as Configuration).uiMode
        }.getOrNull()
        val check = "入参 uiMode=0x${Integer.toHexString(cfg.uiMode)}，mResourcesImpl uiMode=" +
            (implMode?.let { "0x" + Integer.toHexString(it) } ?: "读取失败") +
            (if (implMode == null) "" else if (HookLogic.isNight(implMode) == argNight) "（一致）" else "（不一致！）")
        if (check != lastUiModeCheck) { lastUiModeCheck = check; i(check) }

        val old = night
        if (old == argNight) return
        synchronized(this) {
            if (night == argNight) return
            night = argNight
        }
        i("深浅翻转：${old?.let { if (it) "夜间" else "白天" } ?: "未知"} → ${if (argNight) "夜间" else "白天"}，" +
            "本轮 getDarkerBitmap 调用 ${darkerCalls.getAndSet(0)} 次（跳过 ${darkerSkipped.getAndSet(0)} 次）")
        softReset("深浅翻转")
        // 桌面自己会在 uiMode 变化时重建 IconCache 并重载全部图标（反编译已确认），这里不额外刷新
    }

    // ---------------- Hook 3：夜间套不再压暗 ----------------
    private fun hookGetDarkerBitmap() {
        val cls = findClass(HW_RES_IMPL, null) ?: return
        val m = findMethod(cls, "getDarkerBitmap", Bitmap::class.java) ?: return
        runCatching {
            module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val n = darkerCalls.incrementAndGet()
                val skip = enabled && currentSlot() == Keys.Slot.DARK && readyDir[Keys.Slot.DARK.ordinal] != null
                if (n <= 3 || n % 200 == 0) Log.d(TAG, "getDarkerBitmap 被调用（第 $n 次），${if (skip) "夜间套生效，原样返回" else "走原逻辑"}")
                if (skip) { darkerSkipped.incrementAndGet(); chain.args[0] } else chain.proceed()
            }
            i("已 Hook HwResourcesImpl.getDarkerBitmap")
        }.onFailure { e("Hook getDarkerBitmap 失败", it) }
        // 私有小方法容易被内联进调用方，对调用方做 deoptimize
        var ok = 0; var fail = 0
        for (c in cls.declaredMethods) {
            if (c.name !in DARKER_CALLERS) continue
            if (runCatching { module.deoptimize(c) }.getOrDefault(false)) ok++ else fail++
        }
        i("deoptimize getDarkerBitmap 调用方：成功 $ok，失败 $fail")
    }

    // ---------------- 软重置 ----------------
    /** 只把静态底板缓存标脏，不 recycle、不调用 clearHwThemeZipsAndSomeIcons */
    private fun softReset(reason: String) {
        val done = mutableListOf<String>()
        findClass(HW_RES_IMPL, null)?.let { c ->
            for (f in listOf("needReloadMask", "needReloadBorder", "needReloadBg")) {
                if (setStatic(c, f, true)) done += f
            }
            if (setStatic(c, "isBackgroundLoaded", false)) done += "isBackgroundLoaded"
            if (setStatic(c, "sBgList", null)) done += "sBgList"
        }
        findClass(ZIP_RES_ADAPTER, null)?.let { c ->
            if (setStatic(c, "sIsNeedCheckMultiLevel", true)) done += "sIsNeedCheckMultiLevel"
        }
        i("软重置（$reason）：$done")
    }

    // ---------------- 文件同步 ----------------
    /** 把模块下发的远程文件拷到桌面私有目录并校验；全部完成后才启用槽位 */
    private fun syncAndApply(refreshLauncher: Boolean) {
        val p = prefs ?: return
        enabled = p.getBoolean(Keys.ENABLED, false)
        for (slot in Keys.Slot.entries) {
            readyDir[slot.ordinal] = runCatching { syncSlot(p, slot) }
                .onFailure { e("同步 ${slot.id} 失败，该槽位不改道", it) }.getOrNull()
        }
        i("同步完成：总开关=$enabled 白天=${readyDir[0] ?: "未就绪"} 夜间=${readyDir[1] ?: "未就绪"}")
        softReset("同步/立即应用")
        if (refreshLauncher) refreshLauncherIcons()
    }

    private fun syncSlot(p: SharedPreferences, slot: Keys.Slot): String? {
        val version = p.getLong(Keys.versionKey(slot), 0)
        val slotRoot = File(baseDir, slot.id)
        if (version == 0L) { slotRoot.deleteRecursively(); return null }
        val dir = File(HookLogic.slotDir(baseDir, slot.id, version))
        val marker = File(dir, ".ok")
        if (!marker.exists()) {
            dir.deleteRecursively(); dir.mkdirs()
            copyRemote(Keys.remoteFile(slot, "icons"), File(dir, "icons"))
            if (p.getBoolean(Keys.hasBackupKey(slot), false)) {
                runCatching { copyRemote(Keys.remoteFile(slot, "icons_backup"), File(dir, "icons_backup")) }
                    .onFailure { File(dir, "icons_backup").delete(); w("icons_backup 拷贝失败，忽略：${it.message}") }
            }
            marker.writeText(version.toString())
            i("${slot.id} v$version 已落地：${File(dir, "icons").length()} 字节")
        }
        // 清理旧版本目录（已被 ZipFileCache 打开的旧文件删掉后句柄仍有效）
        slotRoot.listFiles()?.filter { it.name != dir.name }?.forEach { it.deleteRecursively() }
        return dir.path
    }

    private fun copyRemote(name: String, out: File) {
        val tmp = File(out.path + ".tmp")
        module.openRemoteFile(name).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        // 校验：必须能当 zip 打开且有条目，否则不启用（ZipFileCache 连 null 也会永久缓存）
        val n = ZipFile(tmp).use { it.size() }
        require(n > 0) { "$name 是空 zip" }
        if (!tmp.renameTo(out)) error("重命名失败：$tmp")
    }

    // ---------------- 立即应用时让桌面重载图标 ----------------
    private fun refreshLauncherIcons() {
        val cl = appClassLoader ?: return
        // 清 IconCache 内存与 app_icons.db（同一 uiMode 下 DB 条目仍"新鲜"，不清会继续用旧图）
        runCatching {
            val c = Class.forName(L_ICON_CACHE, false, cl)
            val ic = c.getMethod("getInstace").invoke(null)
            if (ic != null) c.getMethod("clearCacheAndDb", Boolean::class.javaPrimitiveType).invoke(ic, true)
            i("IconCache.clearCacheAndDb(true) 完成${if (ic == null) "（实例为空，跳过）" else ""}")
        }.onFailure { w("IconCache.clearCacheAndDb 调用失败：$it") }
        mainHandler.post {
            runCatching {
                val c = Class.forName(L_LAUNCHER, false, cl)
                val l = c.getMethod("getLauncher").invoke(null)
                if (l != null) c.getMethod("updateIconCache").invoke(l)
                i("Launcher.updateIconCache ${if (l == null) "跳过（无实例）" else "完成"}")
            }.onFailure { w("Launcher.updateIconCache 调用失败：$it") }
            runCatching {
                Class.forName(L_LOAD_ICON_UTIL, false, cl)
                    .getMethod("launchUpdateIconTask", List::class.java, Runnable::class.java)
                    .invoke(null, null, null)
                i("LoadIconUtil.launchUpdateIconTask 已触发")
            }.onFailure { w("LoadIconUtil.launchUpdateIconTask 调用失败（可用「重启桌面」兜底）：$it") }
        }
    }

    // ---------------- 工具 ----------------
    private fun currentSlot(): Keys.Slot {
        val n = night ?: initialNight().also { night = it; i("初始深浅：${if (it) "夜间" else "白天"}") }
        return if (n) Keys.Slot.DARK else Keys.Slot.LIGHT
    }

    /** 初值取 ActivityThread 全局配置，失败退回 Resources.getSystem() */
    private fun initialNight(): Boolean {
        val mode = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val t = at.getMethod("currentActivityThread").invoke(null)!!
            (at.getMethod("getConfiguration").invoke(t) as Configuration).uiMode
        }.getOrElse { Resources.getSystem().configuration.uiMode }
        return HookLogic.isNight(mode)
    }

    private fun findClass(name: String, cl: ClassLoader?): Class<*>? =
        runCatching { Class.forName(name, false, cl ?: javaClass.classLoader) }
            .onFailure { w("类不存在：$name（相关功能跳过）") }.getOrNull()

    private fun findMethod(c: Class<*>, name: String, vararg types: Class<*>): Method? =
        runCatching { c.getDeclaredMethod(name, *types) }
            .onFailure { w("方法不存在：${c.name}.$name（相关功能跳过）") }.getOrNull()

    private fun setStatic(c: Class<*>, field: String, value: Any?): Boolean = runCatching {
        c.getDeclaredField(field).apply { isAccessible = true }.set(null, value); true
    }.getOrElse { w("写静态字段失败：${c.simpleName}.$field：$it"); false }

    private fun i(msg: String) { Log.i(TAG, msg); module.log(Log.INFO, TAG, msg) }
    private fun w(msg: String) { Log.w(TAG, msg); module.log(Log.WARN, TAG, msg) }
    private fun e(msg: String, t: Throwable) { Log.e(TAG, msg, t); module.log(Log.ERROR, TAG, msg, t) }
}
