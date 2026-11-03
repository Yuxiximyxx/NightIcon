package com.nighticon.logic

/**
 * 与 Android 无关的纯逻辑，方便在 JVM 单元测试里验证。
 */
object HookLogic {

    /** framework 常量：ICONS_ZIP_FILE = "icons"，备用 "icons_backup" */
    val ICON_ZIP_NAMES = setOf("icons", "icons_backup")

    /**
     * 是否为系统主题目录。反编译所见：
     * - 桌面进程 getThemeDir() = /data/themes/<userId>（软链到 /data/themes/user/<id>/theme/theme_x）
     * - 其它进程 = /data/themes/skin
     */
    private val THEME_DIR = Regex("^/data/themes/(\\d+|skin|user/\\d+/theme/theme_[ab])/?$")

    fun isThemeDir(path: String?): Boolean = path != null && THEME_DIR.matches(path)

    /** 是否应当把这一次 ZipFileCache 查询改道 */
    fun shouldRedirect(path: String?, zip: String?): Boolean =
        zip != null && zip in ICON_ZIP_NAMES && isThemeDir(path)

    /** Configuration.UI_MODE_NIGHT_MASK = 0x30，UI_MODE_NIGHT_YES = 0x20 */
    fun isNight(uiMode: Int): Boolean = (uiMode and 0x30) == 0x20

    /** 槽位目录：<base>/<slot>/v<version>。每个版本独立目录，避开 ZipFileCache 对同一键的永久缓存（包括 null） */
    fun slotDir(base: String, slotId: String, version: Long): String = "$base/$slotId/v$version"
}
