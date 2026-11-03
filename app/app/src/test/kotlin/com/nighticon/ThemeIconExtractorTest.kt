package com.nighticon

import com.nighticon.logic.HookLogic
import com.nighticon.theme.ThemeIconExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeIconExtractorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zipBytes(entries: Map<String, ByteArray>, stored: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val iconsZip get() = zipBytes(mapOf(
        "com.android.contacts.png" to png, "icon_mask.png" to png,
        "com.foo/foreground.png" to png, "com.foo/background.png" to png,
    ))

    private fun theme(name: String, entries: Map<String, ByteArray>, stored: Boolean = false): File =
        tmp.newFile(name).apply { writeBytes(zipBytes(entries, stored)) }

    @Test fun extractsIconsAndBackup_hwt() {
        val t = theme("a.hwt", mapOf(
            "description.xml" to "<HwTheme><title-cn>测试主题</title-cn></HwTheme>".toByteArray(),
            "icons" to iconsZip, "icons_backup" to iconsZip, "wallpaper/home.jpg" to png,
        ))
        val before = t.readBytes()
        val r = ThemeIconExtractor.extract(t, tmp.newFolder("out"))
        assertEquals("测试主题", r.title)
        assertEquals(4, r.pngCount)
        assertNotNull(r.iconsBackup)
        assertTrue(r.icons.readBytes().contentEquals(iconsZip))
        assertTrue("原主题文件不得被修改", t.readBytes().contentEquals(before))
    }

    @Test fun storedOuterZip_hnt_viaStream() {
        val t = theme("b.hnt", mapOf("icons" to iconsZip), stored = true)
        val out = tmp.newFolder("out2")
        val r = ThemeIconExtractor.extract(t.inputStream(), tmp.newFolder("work"), out)
        assertNull(r.iconsBackup)
        assertFalse(File(out, "icons_backup").exists())
        assertEquals(4, r.pngCount)
    }

    @Test(expected = ThemeIconExtractor.NoIconsException::class)
    fun noIcons() {
        ThemeIconExtractor.extract(theme("c.hwt", mapOf("description.xml" to "<x/>".toByteArray())), tmp.newFolder("o"))
    }

    @Test(expected = java.util.zip.ZipException::class)
    fun iconsNotZip() {
        ThemeIconExtractor.extract(theme("d.hwt", mapOf("icons" to "not a zip".toByteArray())), tmp.newFolder("o"))
    }

    @Test fun brokenBackupIgnored() {
        val r = ThemeIconExtractor.extract(
            theme("e.hwt", mapOf("icons" to iconsZip, "icons_backup" to "bad".toByteArray())), tmp.newFolder("o"))
        assertNull(r.iconsBackup)
    }

    @Test fun fileNameFilter() {
        assertTrue(ThemeIconExtractor.isThemeFileName("Night.HWT"))
        assertTrue(ThemeIconExtractor.isThemeFileName("a.hnt"))
        assertFalse(ThemeIconExtractor.isThemeFileName("a.zip"))
        assertFalse(ThemeIconExtractor.isThemeFileName(null))
    }

    @Test fun hookLogic() {
        assertTrue(HookLogic.shouldRedirect("/data/themes/0", "icons"))
        assertTrue(HookLogic.shouldRedirect("/data/themes/skin", "icons_backup"))
        assertTrue(HookLogic.shouldRedirect("/data/themes/user/0/theme/theme_b", "icons"))
        assertFalse(HookLogic.shouldRedirect("/data/themes/0", "com.hihonor.android.launcher"))
        assertFalse(HookLogic.shouldRedirect("/data/user/0/com.hihonor.android.launcher/files/nighticon/dark/v1", "icons"))
        assertFalse(HookLogic.shouldRedirect(null, "icons"))
        assertTrue(HookLogic.isNight(0x21)); assertFalse(HookLogic.isNight(0x11))
        assertEquals("/b/dark/v7", HookLogic.slotDir("/b", "dark", 7))
    }

    /** 用侦察拿到的真实 icons（扁平 218px 套 + 自适应套）包成主题再解析；文件不在时跳过 */
    @Test fun realReconIcons() {
        val base = System.getProperty("nighticon.recon")?.let(::File)
        val sources = listOfNotNull(
            base?.resolve("user/0/theme/theme_b/icons"), base?.resolve("128/icons"),
        ).filter { it.isFile }
        assumeTrue("未找到侦察数据，跳过", sources.isNotEmpty())
        for (src in sources) {
            val t = theme("real_${src.parentFile.name}.hwt", mapOf("icons" to src.readBytes()))
            val r = ThemeIconExtractor.extract(t, tmp.newFolder())
            println("侦察主题 ${src.path}：png ${r.pngCount} 个")
            assertTrue(r.pngCount > 0)
            assertEquals(src.length(), r.icons.length())
        }
    }
}
