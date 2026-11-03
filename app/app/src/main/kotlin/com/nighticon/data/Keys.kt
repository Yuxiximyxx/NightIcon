package com.nighticon.data

/**
 * 模块 App 与桌面进程（Hook 侧）共享的配置键。
 *
 * 传递方式：
 * - 开关、版本号、指令令牌 → libxposed 远程偏好 [REMOTE_GROUP]
 *   （模块 App 经 XposedService.getRemotePreferences 写，Hook 侧 XposedInterface.getRemotePreferences 读）
 * - 解出的 icons / icons_backup 内层 zip → libxposed 远程文件（XposedService.openRemoteFile 写，
 *   Hook 侧 XposedInterface.openRemoteFile 只读打开后拷到桌面私有目录）
 */
object Keys {
    const val REMOTE_GROUP = "nighticon"

    /** 总开关 */
    const val ENABLED = "enabled"

    /** 每个槽位的内容版本号（每次重新选主题都会变）；0 / 缺省 = 未选择 */
    fun versionKey(slot: Slot) = "${slot.id}_version"

    /** 槽位是否带 icons_backup */
    fun hasBackupKey(slot: Slot) = "${slot.id}_has_backup"

    /** 「立即应用」令牌：值变化即触发 Hook 侧重新同步 + 软重置 + 刷新图标 */
    const val APPLY_TOKEN = "apply_token"

    /** 「重启桌面」兜底令牌：值变化即让桌面进程自杀（仅在 root 不可用时使用） */
    const val RESTART_TOKEN = "restart_token"

    /** 主题槽位：白天 / 夜间 */
    enum class Slot(val id: String) { LIGHT("light"), DARK("dark") }

    /** 远程文件名 */
    fun remoteFile(slot: Slot, zipName: String) = "${slot.id}_$zipName"

    // 仅模块 App 本地使用的展示字段
    fun displayNameKey(slot: Slot) = "${slot.id}_display_name"
}
