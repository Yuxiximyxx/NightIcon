package com.nighticon.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nighticon.R
import com.nighticon.data.Keys
import com.nighticon.theme.ThemeIconExtractor
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 设置页：总开关、白天/夜间主题选择、状态、立即应用、重启桌面、关于。
 * 选文件、解包、远程下发和重启桌面的行为与 0.1.0 相同，只换了 Miuix 界面。
 */
class SettingsActivity : ComponentActivity() {

    private val TAG = "NightIcon"
    private val local by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    /** 临时状态（覆盖默认状态文字），null = 按配置推算 */
    private var transientStatus: String? = null
    private var uiState by mutableStateOf(SettingsUiState())

    private val serviceListener: (XposedService?) -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiState = uiState.copy(enabled = local.getBoolean(Keys.ENABLED, false))
        render()
        NightIconApp.listeners += serviceListener
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect { applySystemBars(dark) }
            MiuixTheme(colors = nightIconColors(dark)) {
                SettingsScreen(
                    state = uiState,
                    dark = dark,
                    onEnabledChange = { checked ->
                        local.edit().putBoolean(Keys.ENABLED, checked).apply()
                        NightIconApp.service?.getRemotePreferences(Keys.REMOTE_GROUP)
                            ?.edit()?.putBoolean(Keys.ENABLED, checked)?.apply()
                        transientStatus = null
                        uiState = uiState.copy(enabled = checked)
                        render()
                    },
                    onPickLight = { pick(Keys.Slot.LIGHT) },
                    onPickDark = { pick(Keys.Slot.DARK) },
                    onApply = { applyNow() },
                    onRestart = { restartLauncher() },
                    onAbout = { openRepo() },
                )
            }
        }
    }

    override fun onDestroy() {
        NightIconApp.listeners -= serviceListener
        super.onDestroy()
    }

    /** 状态栏、导航栏跟页面底色走，浅色用深色图标。 */
    private fun applySystemBars(dark: Boolean) {
        val color = if (dark) 0xFF000000.toInt() else 0xFFF7F7F7.toInt()
        window.statusBarColor = color
        window.navigationBarColor = color
        val appearance = if (dark) {
            0
        } else {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        }
        window.insetsController?.setSystemBarsAppearance(
            appearance,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    // ---------------- 选择主题 ----------------
    private fun pick(slot: Keys.Slot) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // .hwt/.hnt 没有标准 MIME，放开类型，选完再按扩展名校验
            // 尽量把初始目录定位到 /storage/emulated/0/Honor/Themes
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Honor/Themes"),
            )
        }
        runCatching { startActivityForResult(intent, slot.ordinal) }
            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }

    @Deprecated("沿用平台 API，避免再包一层 Activity Result 合约")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val slot = Keys.Slot.entries.getOrNull(requestCode) ?: return
        val name = displayName(uri)
        if (!ThemeIconExtractor.isThemeFileName(name)) {
            Toast.makeText(this, R.string.toast_bad_ext, Toast.LENGTH_LONG).show()
            return
        }
        importTheme(slot, uri, name!!)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** 只读打开所选主题，解出 icons / icons_backup 到私有目录；原主题文件不改动 */
    private fun importTheme(slot: Keys.Slot, uri: Uri, name: String) {
        transientStatus = getString(R.string.st_extracting); render()
        thread(name = "nighticon-extract") {
            val outDir = slotDir(slot)
            val staging = File(cacheDir, "staging_${slot.id}")
            val result = runCatching {
                staging.deleteRecursively()
                val input = contentResolver.openInputStream(uri) ?: error("无法打开所选文件")
                ThemeIconExtractor.extract(input, File(cacheDir, "extract"), staging)
            }
            result.onSuccess {
                // 解包成功才替换旧内容
                outDir.deleteRecursively(); outDir.parentFile?.mkdirs()
                if (!staging.renameTo(outDir)) { staging.copyRecursively(outDir, true); staging.deleteRecursively() }
                local.edit()
                    .putString(Keys.displayNameKey(slot), name)
                    .putLong(localVersionKey(slot), System.currentTimeMillis())
                    .putBoolean(Keys.hasBackupKey(slot), it.iconsBackup != null)
                    .apply()
                Log.i(TAG, "${slot.id} 解包完成：$name，png ${it.pngCount} 个，icons_backup=${it.iconsBackup != null}")
            }.onFailure { staging.deleteRecursively() }
            runOnUiThread {
                result.onSuccess { transientStatus = null }.onFailure {
                    if (it is ThemeIconExtractor.NoIconsException) {
                        transientStatus = getString(R.string.st_no_icons)
                        Toast.makeText(this, R.string.toast_no_icons, Toast.LENGTH_LONG).show()
                    } else {
                        transientStatus = null
                        Toast.makeText(this, getString(R.string.toast_extract_fail, it.message), Toast.LENGTH_LONG).show()
                    }
                }
                render()
            }
        }
    }

    // ---------------- 立即应用 ----------------
    /** 把本地解出的文件写成 libxposed 远程文件，再写远程偏好（版本号 + 令牌），Hook 侧收到后同步并刷新 */
    private fun applyNow() {
        val service = NightIconApp.service
        if (service == null) { transientStatus = getString(R.string.st_no_service); render(); return }
        transientStatus = getString(R.string.st_pushing); render()
        val enabled = uiState.enabled
        thread(name = "nighticon-push") {
            val r = runCatching {
                val remote = service.getRemotePreferences(Keys.REMOTE_GROUP)
                val edit = remote.edit()
                for (slot in Keys.Slot.entries) {
                    val v = local.getLong(localVersionKey(slot), 0)
                    val icons = File(slotDir(slot), "icons")
                    if (v == 0L || !icons.exists()) { edit.putLong(Keys.versionKey(slot), 0); continue }
                    if (local.getLong(pushedVersionKey(slot), 0) != v) {
                        pushFile(service, Keys.remoteFile(slot, "icons"), icons)
                        val backup = File(slotDir(slot), "icons_backup")
                        if (backup.exists()) pushFile(service, Keys.remoteFile(slot, "icons_backup"), backup)
                        else service.deleteRemoteFile(Keys.remoteFile(slot, "icons_backup"))
                        local.edit().putLong(pushedVersionKey(slot), v).apply()
                    }
                    edit.putLong(Keys.versionKey(slot), v)
                        .putBoolean(Keys.hasBackupKey(slot), local.getBoolean(Keys.hasBackupKey(slot), false))
                }
                edit.putBoolean(Keys.ENABLED, enabled)
                    .putLong(Keys.APPLY_TOKEN, System.currentTimeMillis())
                check(edit.commit()) { "远程偏好写入失败" }
            }
            runOnUiThread {
                r.onSuccess { transientStatus = getString(R.string.st_applied) }
                    .onFailure { transientStatus = "下发失败：${it.message}"; Log.e(TAG, "下发失败", it) }
                render()
            }
        }
    }

    private fun pushFile(service: XposedService, name: String, src: File) {
        runCatching { service.deleteRemoteFile(name) }
        val pfd = service.openRemoteFile(name)
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                out.channel.truncate(0)
                src.inputStream().use { input -> input.copyTo(out) }
            }
        }
        Log.i(TAG, "远程文件已写入：$name（${src.length()} 字节）")
    }

    // ---------------- 重启桌面 ----------------
    /**
     * 首选 root：`am force-stop` 不依赖 Hook 是否生效，桌面卡死/Hook 失败时也能用；系统会自动拉起默认桌面。
     * 无 root（或未授权）时兜底：写远程偏好令牌，由 Hook 侧在桌面进程内 killProcess 自身。
     */
    private fun restartLauncher() {
        thread(name = "nighticon-restart") {
            val rootOk = runCatching {
                val p = ProcessBuilder("su", "-c", "am force-stop com.hihonor.android.launcher")
                    .redirectErrorStream(true).start()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
            val msg = when {
                rootOk -> R.string.toast_restart_root
                NightIconApp.service?.getRemotePreferences(Keys.REMOTE_GROUP)?.edit()
                    ?.putLong(Keys.RESTART_TOKEN, System.currentTimeMillis())?.commit() == true -> R.string.toast_restart_hook
                else -> R.string.toast_restart_fail
            }
            Log.i(TAG, "重启桌面：root=$rootOk")
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun openRepo() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.toast_open_repo_fail, Toast.LENGTH_LONG).show() }
    }

    // ---------------- 渲染 ----------------
    private fun render() {
        val notSelected = getString(R.string.not_selected)
        val light = local.getString(Keys.displayNameKey(Keys.Slot.LIGHT), null) ?: notSelected
        val dark = local.getString(Keys.displayNameKey(Keys.Slot.DARK), null) ?: notSelected
        val both = Keys.Slot.entries.all { local.getLong(localVersionKey(it), 0) != 0L }
        val enabled = uiState.enabled
        val transient = transientStatus
        val (status, tone) = if (transient != null) {
            transient to toneOf(transient)
        } else {
            when {
                NightIconApp.service == null -> getString(R.string.st_no_service) to StatusTone.Idle
                !both -> getString(R.string.st_need_pick) to StatusTone.Idle
                !enabled -> getString(R.string.st_disabled) to StatusTone.Idle
                else -> getString(R.string.st_ready) to StatusTone.Ready
            }
        }
        uiState = uiState.copy(lightName = light, darkName = dark, status = status, tone = tone)
    }

    private fun toneOf(text: String): StatusTone = when (text) {
        getString(R.string.st_ready), getString(R.string.st_applied) -> StatusTone.Ready
        getString(R.string.st_extracting), getString(R.string.st_pushing) -> StatusTone.Busy
        else -> StatusTone.Idle
    }

    private fun slotDir(slot: Keys.Slot) = File(filesDir, "slots/${slot.id}")
    private fun localVersionKey(slot: Keys.Slot) = "${slot.id}_local_version"
    private fun pushedVersionKey(slot: Keys.Slot) = "${slot.id}_pushed_version"

    companion object {
        const val REPO_URL = "https://github.com/Yuxiximyxx/NightIcon"
    }
}
