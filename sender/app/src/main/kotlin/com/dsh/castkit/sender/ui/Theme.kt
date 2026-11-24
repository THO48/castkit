package com.dsh.castkit.sender.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** CastKit Studio 色彩系统（明暗双套，与接收端统一视觉语言）。 */
object CastColors {
    // Dark palette
    val DarkBg = Color(0xFF0A0D13)
    val DarkSurface = Color(0xFF131822)
    val DarkSurfaceHigh = Color(0xFF1C2331)
    val DarkSurfaceHighest = Color(0xFF252E40)
    val DarkOutline = Color(0xFF283246)
    val DarkPrimary = Color(0xFF4F96FF)
    val DarkOnPrimary = Color(0xFF04142B)
    val DarkPrimaryContainer = Color(0xFF172D52)
    val DarkOnPrimaryContainer = Color(0xFFD5E6FF)
    val DarkText = Color(0xFFF2F5FA)
    val DarkSubtext = Color(0xFF96A2B8)

    // Light palette
    val LightBg = Color(0xFFF3F6FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEBF0F8)
    val LightSurfaceHighest = Color(0xFFDFE7F2)
    val LightOutline = Color(0xFFD8E1EE)
    val LightPrimary = Color(0xFF256EF6)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFFDDEAFF)
    val LightOnPrimaryContainer = Color(0xFF092A66)
    val LightText = Color(0xFF111827)
    val LightSubtext = Color(0xFF5D6A82)

    // Semantic accents
    val LiveGreenDark = Color(0xFF34D399)
    val LiveGreenLight = Color(0xFF059669)
    val WarnAmberDark = Color(0xFFFBBF24)
    val WarnAmberLight = Color(0xFFD97706)
    val ErrorRedDark = Color(0xFFF87171)
    val ErrorRedLight = Color(0xFFDC2626)
    val CyanAccent = Color(0xFF22D3EE)
}

private val CastDarkColors = darkColorScheme(
    primary = CastColors.DarkPrimary,
    onPrimary = CastColors.DarkOnPrimary,
    primaryContainer = CastColors.DarkPrimaryContainer,
    onPrimaryContainer = CastColors.DarkOnPrimaryContainer,
    secondary = CastColors.CyanAccent,
    onSecondary = Color(0xFF042229),
    secondaryContainer = Color(0xFF12323D),
    onSecondaryContainer = Color(0xFFCFFAFE),
    background = CastColors.DarkBg,
    onBackground = CastColors.DarkText,
    surface = CastColors.DarkSurface,
    onSurface = CastColors.DarkText,
    surfaceVariant = CastColors.DarkSurfaceHigh,
    onSurfaceVariant = CastColors.DarkSubtext,
    surfaceContainerLowest = CastColors.DarkBg,
    surfaceContainerLow = CastColors.DarkSurface,
    surfaceContainer = CastColors.DarkSurface,
    surfaceContainerHigh = CastColors.DarkSurfaceHigh,
    surfaceContainerHighest = CastColors.DarkSurfaceHighest,
    outline = CastColors.DarkOutline,
    outlineVariant = Color(0xFF1E2636),
    error = CastColors.ErrorRedDark,
    onError = Color(0xFF3B0909),
    errorContainer = Color(0xFF42161B),
    onErrorContainer = Color(0xFFFECACA),
)

private val CastLightColors = lightColorScheme(
    primary = CastColors.LightPrimary,
    onPrimary = CastColors.LightOnPrimary,
    primaryContainer = CastColors.LightPrimaryContainer,
    onPrimaryContainer = CastColors.LightOnPrimaryContainer,
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF083344),
    background = CastColors.LightBg,
    onBackground = CastColors.LightText,
    surface = CastColors.LightSurface,
    onSurface = CastColors.LightText,
    surfaceVariant = CastColors.LightSurfaceHigh,
    onSurfaceVariant = CastColors.LightSubtext,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = CastColors.LightSurface,
    surfaceContainer = CastColors.LightSurface,
    surfaceContainerHigh = CastColors.LightSurfaceHigh,
    surfaceContainerHighest = CastColors.LightSurfaceHighest,
    outline = CastColors.LightOutline,
    outlineVariant = Color(0xFFE5ECF5),
    error = CastColors.ErrorRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val CastTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CastKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CastDarkColors else CastLightColors,
        typography = CastTypography,
        content = content,
    )
}

@Composable
fun liveGreenColor(): Color =
    if (isSystemInDarkTheme()) CastColors.LiveGreenDark else CastColors.LiveGreenLight

@Composable
fun warnAmberColor(): Color =
    if (isSystemInDarkTheme()) CastColors.WarnAmberDark else CastColors.WarnAmberLight

/** 带细边框与柔和圆角的统一卡片容器。 */
@Composable
fun CastCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(content = content)
    }
}

/** 区块小标题行（支持右侧放刷新/操作按钮）。 */
@Composable
fun SectionHeaderRow(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

/** 带呼吸波纹动画的在线/投屏状态点。 */
@Composable
fun StatusPulseDot(
    color: Color,
    pulsing: Boolean,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size * 2), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "pulse")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 2.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "pulseScale",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "pulseAlpha",
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .scale(scale)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** 胶囊状态标签。 */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    leadingDot: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
