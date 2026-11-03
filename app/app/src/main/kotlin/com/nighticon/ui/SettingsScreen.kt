package com.nighticon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nighticon.BuildConfig
import com.nighticon.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 状态行右侧圆点的语义：就绪为绿，进行中为蓝，其余为灰。 */
enum class StatusTone { Ready, Busy, Idle }

/** 设置页展示用的快照。业务逻辑仍在 [SettingsActivity]，这里只负责画。 */
data class SettingsUiState(
    val enabled: Boolean = false,
    val lightName: String = "",
    val darkName: String = "",
    val status: String = "",
    val tone: StatusTone = StatusTone.Idle,
)

private val Accent = Color(0xFF3482FF)
private val ReadyDot = Color(0xFF34C759)
private val PageLight = Color(0xFFF7F7F7)
private val PageDark = Color(0xFF000000)
private val CardLight = Color(0xFFFFFFFF)
private val CardDark = Color(0xFF242424)
private val Text2Light = Color(0xFF8C8C8C)
private val Text2Dark = Color(0xFF9A9A9A)
private val SectionDark = Color(0xFF8A8A8A)
private val Button2Light = Color(0xFFE8E8E8)
private val Button2Dark = Color(0xFF2E2E2E)
private val TrackOffLight = Color(0xFFE6E6E6)
private val TrackOffDark = Color(0xFF3A3A3A)

/** 按效果图收一版 Miuix 配色：浅灰底 / 纯黑底，主色保持 #3482FF。 */
fun nightIconColors(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White,
        background = CardDark,
        surface = PageDark,
        surfaceContainer = CardDark,
        onSurfaceVariantSummary = Text2Dark,
        onSurfaceVariantActions = Text2Dark,
        onBackgroundVariant = SectionDark,
        secondary = TrackOffDark,
    )
} else {
    lightColorScheme(
        primary = Accent,
        background = CardLight,
        surface = PageLight,
        surfaceContainer = CardLight,
        onSurfaceVariantSummary = Text2Light,
        onSurfaceVariantActions = Text2Light,
        onBackgroundVariant = Text2Light,
        secondary = TrackOffLight,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    dark: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPickLight: () -> Unit,
    onPickDark: () -> Unit,
    onApply: () -> Unit,
    onRestart: () -> Unit,
    onAbout: () -> Unit,
) {
    val page = if (dark) PageDark else PageLight
    val titleColor = if (dark) Color.White else Color.Black
    val secondaryText = if (dark) Text2Dark else Text2Light
    val sectionColor = if (dark) SectionDark else Text2Light
    val button2 = if (dark) Button2Dark else Button2Light
    val button2Text = if (dark) Color.White else Color.Black
    val (dotColor, statusColor) = when (state.tone) {
        StatusTone.Ready -> ReadyDot to Accent
        StatusTone.Busy -> Accent to Accent
        StatusTone.Idle -> secondaryText to secondaryText
    }
    val sectionPadding = PaddingValues(start = 30.dp, top = 14.dp, end = 30.dp, bottom = 8.dp)

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(page)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        // 效果图在状态栏下留了一段空白，再放大标题
        Spacer(Modifier.height(56.dp))
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 10.dp, bottom = 14.dp),
            color = titleColor,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
        )

        Card(
            modifier = Modifier.padding(horizontal = 14.dp),
            cornerRadius = 18.dp,
        ) {
            SuperSwitch(
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
                title = stringResource(R.string.switch_enabled),
                summary = stringResource(R.string.switch_summary),
                switchColors = SwitchDefaults.switchColors(
                    checkedTrackColor = Accent,
                    uncheckedTrackColor = if (dark) TrackOffDark else TrackOffLight,
                    checkedThumbColor = Color.White,
                    uncheckedThumbColor = Color.White,
                ),
            )
        }

        SmallTitle(
            text = stringResource(R.string.section_theme),
            textColor = sectionColor,
            insideMargin = sectionPadding,
        )
        Card(
            modifier = Modifier.padding(horizontal = 14.dp),
            cornerRadius = 18.dp,
        ) {
            ThemeRow(
                title = stringResource(R.string.label_light),
                current = stringResource(R.string.current_fmt, state.lightName),
                action = stringResource(R.string.pick_action),
                actionColor = secondaryText,
                onClick = onPickLight,
            )
            ThemeRow(
                title = stringResource(R.string.label_dark),
                current = stringResource(R.string.current_fmt, state.darkName),
                action = stringResource(R.string.pick_action),
                actionColor = secondaryText,
                onClick = onPickDark,
            )
        }

        SmallTitle(
            text = stringResource(R.string.section_status),
            textColor = sectionColor,
            insideMargin = sectionPadding,
        )
        Card(
            modifier = Modifier.padding(horizontal = 14.dp),
            cornerRadius = 18.dp,
        ) {
            BasicComponent(
                title = stringResource(R.string.label_status),
                rightActions = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.status,
                        modifier = Modifier.widthIn(max = 210.dp),
                        color = statusColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                    )
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onApply,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                cornerRadius = 16.dp,
                minHeight = 52.dp,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = stringResource(R.string.btn_apply),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Button(
                onClick = onRestart,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                cornerRadius = 16.dp,
                minHeight = 52.dp,
                colors = ButtonDefaults.buttonColors(color = button2),
            ) {
                Text(
                    text = stringResource(R.string.btn_restart),
                    color = button2Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        SmallTitle(
            text = stringResource(R.string.section_other),
            textColor = sectionColor,
            insideMargin = PaddingValues(start = 30.dp, top = 24.dp, end = 30.dp, bottom = 8.dp),
        )
        Card(
            modifier = Modifier.padding(horizontal = 14.dp),
            cornerRadius = 18.dp,
        ) {
            SuperArrow(
                title = stringResource(R.string.about_title),
                summary = stringResource(R.string.about_summary, BuildConfig.VERSION_NAME),
                onClick = onAbout,
            )
        }
    }
}

@Composable
private fun ThemeRow(
    title: String,
    current: String,
    action: String,
    actionColor: Color,
    onClick: () -> Unit,
) {
    SuperArrow(
        title = title,
        summary = current,
        onClick = onClick,
        rightActions = {
            Text(
                text = action,
                color = actionColor,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        },
    )
}
