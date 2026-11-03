package com.nighticon.theme

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 主题图标解包。
 *
 * 荣耀主题包（.hwt / .hnt）外层是普通 zip：
 * ```
 * description.xml     标题等信息
 * icons               ★ 内层 zip（无扩展名），桌面图标全部在这里
 * icons_backup        （可选）备用图标 zip，framework 在 icons 缺失时回退
 * unlock/ wallpaper/ com.android.systemui ...   本模块不碰
 * ```
 * 只把 icons / icons_backup 原样拷出并校验能当 zip 打开，不改写任何内容，也不碰原主题文件。
 */
object ThemeIconExtractor {

    const val ICONS_ENTRY = "icons"
    const val ICONS_BACKUP_ENTRY = "icons_backup"
    private const val DESCRIPTION_ENTRY = "description.xml"

    /** 主题里没有 icons 条目 */
    class NoIconsException(msg: String) : Exception(msg)

    data class Result(
        /** 解出的 icons 文件 */
        val icons: File,
        /** 解出的 icons_backup 文件（主题里没有则为 null） */
        val iconsBackup: File?,
        val title: String?,
        /** icons 中的条目数（png 计数） */
        val pngCount: Int,
    )

    fun isThemeFileName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.endsWith(".hwt") || n.endsWith(".hnt")
    }

    /**
     * 从主题包文件解出 icons（和 icons_backup）到 [outDir]。
     * @throws NoIconsException 主题包中没有 icons
     * @throws java.util.zip.ZipException 主题包或 icons 不是合法 zip
     */
    fun extract(themeFile: File, outDir: File): Result {
        ZipFile(themeFile).use { outer ->
            val iconsEntry = outer.getEntry(ICONS_ENTRY)
            if (iconsEntry == null || iconsEntry.isDirectory) {
                throw NoIconsException("该主题不包含图标")
            }
            val title = outer.getEntry(DESCRIPTION_ENTRY)?.let { e ->
                outer.getInputStream(e).use { parseTitle(it) }
            }
            outDir.mkdirs()
            val icons = copyEntry(outer, ICONS_ENTRY, File(outDir, ICONS_ENTRY))!!
            val pngCount = validate(icons)
            if (pngCount == 0) throw NoIconsException("该主题不包含图标")
            val backup = runCatching {
                copyEntry(outer, ICONS_BACKUP_ENTRY, File(outDir, ICONS_BACKUP_ENTRY))
                    ?.also { validate(it) }
            }.getOrElse {
                File(outDir, ICONS_BACKUP_ENTRY).delete(); null // 备用包损坏就忽略，不影响主图标
            }
            if (backup == null) File(outDir, ICONS_BACKUP_ENTRY).delete()
            return Result(icons, backup, title, pngCount)
        }
    }

    /** SAF 只能给 InputStream：先整体落盘到 [workDir]（ZipFile 随机访问对 STORED + 数据描述符更稳） */
    fun extract(themeStream: InputStream, workDir: File, outDir: File): Result {
        workDir.mkdirs()
        val tmp = File(workDir, "theme.tmp")
        try {
            themeStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return extract(tmp, outDir)
        } finally {
            tmp.delete()
        }
    }

    /** 能用 ZipFile 打开并返回 png 条目数；不是 zip 会抛 ZipException */
    fun validate(zip: File): Int = ZipFile(zip).use { z ->
        var n = 0
        val en = z.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (!e.isDirectory && e.name.endsWith(".png", ignoreCase = true)) n++
        }
        n
    }

    private fun copyEntry(outer: ZipFile, name: String, out: File): File? {
        val e = outer.getEntry(name) ?: return null
        if (e.isDirectory) return null
        val tmp = File(out.path + ".tmp")
        outer.getInputStream(e).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        return out
    }

    /** 极简解析 description.xml 的 <title-cn>/<title> */
    private fun parseTitle(input: InputStream): String? {
        val text = input.readBytes().toString(Charsets.UTF_8)
        for (tag in listOf("title-cn", "title")) {
            Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.let {
                return it.groupValues[1].trim().ifEmpty { null }
            }
        }
        return null
    }
}
