package com.nighticon.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import java.io.File

/**
 * 把设置页画成浅色 / 深色截图。默认跳过，避免 CI 依赖本机字体和图形环境。
 * 本地：`./gradlew testDebugUnitTest -Dnighticon.screenshots=/tmp/shots`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w392dp-h860dp-xxhdpi")
class SettingsScreenshotTest {

    @get:Rule val rule = createAndroidComposeRule<ScreenshotHostActivity>()

    @Test fun light() = capture(dark = false, name = "ui-light")

    @Test fun dark() = capture(dark = true, name = "ui-dark")

    private fun capture(dark: Boolean, name: String) {
        val outDir = System.getProperty("nighticon.screenshots")
        assumeTrue("未设置 nighticon.screenshots，跳过界面截图", !outDir.isNullOrBlank())
        val dir = File(outDir!!)
        val sample = SettingsUiState(
            enabled = true,
            lightName = "琉光 (动态).hwt",
            darkName = "光曜 (动态).hnt",
            status = "已就绪",
            tone = StatusTone.Ready,
        )
        rule.setContent {
            ScreenshotTheme(dark) {
                SettingsScreen(
                    state = sample,
                    dark = dark,
                    onEnabledChange = {},
                    onPickLight = {},
                    onPickDark = {},
                    onApply = {},
                    onRestart = {},
                    onAbout = {},
                )
            }
        }
        rule.waitForIdle()
        // Robolectric 上 PixelCopy 等不到重绘，直接把窗口画到 Bitmap。
        val view = rule.activity.window.decorView
        check(view.width > 0 && view.height > 0) { "窗口尺寸为 0" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** 截图用系统里的中文字体，避免 Robolectric 把汉字画成方框。正式包仍走系统字体。 */
@Composable
private fun ScreenshotTheme(dark: Boolean, content: @Composable () -> Unit) {
    val family = remember {
        val file = File("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf")
        check(file.exists()) { "缺少中文字体：${file.path}" }
        FontFamily(Typeface.createFromFile(file))
    }
    val base = defaultTextStyles()
    fun TextStyle.useFont() = copy(fontFamily = family)
    MiuixTheme(
        colors = nightIconColors(dark),
        textStyles = base.copy(
            main = base.main.useFont(),
            paragraph = base.paragraph.useFont(),
            body1 = base.body1.useFont(),
            body2 = base.body2.useFont(),
            button = base.button.useFont(),
            footnote1 = base.footnote1.useFont(),
            footnote2 = base.footnote2.useFont(),
            headline1 = base.headline1.useFont(),
            headline2 = base.headline2.useFont(),
            subtitle = base.subtitle.useFont(),
            title1 = base.title1.useFont(),
            title2 = base.title2.useFont(),
            title3 = base.title3.useFont(),
            title4 = base.title4.useFont(),
        ),
        content = content,
    )
}
