package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 投屏状态行。
 *
 * 原来的 `CastStatusCard` 不再是一张独立卡片，而是合并进"接收端"区块的顶部——
 * 它显示的本来就是"当前接收端的连接详情"，和接收端列表是同一件事的两半，
 * 拆成两张卡片正是改造前"碎片化卡片堆叠"的来源。
 *
 * 状态色映射（设计系统 §1.5）：
 * IDLE → onSurfaceVariant / CONNECTING·RECONNECTING → primary /
 * RUNNING → success / ERROR → error。
 *
 * @param label 状态主文字（"投屏中" / "正在连接" / "已断开"）。用 `headlineSmall`
 *        （20sp / W700），这是设计系统里 `emphasis` 仅有的两个使用场景之一。
 * @param detail 目标地址（"192.168.1.23:8123"），跟在状态文字后面，单行截断。
 * @param stats 参数与实测值（"1920×1080 @30fps 8Mbps · 实测 7.9Mbps"）。
 */
@Composable
fun CastStatusRow(
    phase: CastPhase,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    stats: String? = null,
) {
    val dotColor: Color = when (phase) {
        CastPhase.IDLE -> CastKitTheme.colorScheme.onSurfaceVariant
        CastPhase.CONNECTING -> CastKitTheme.colorScheme.primary
        CastPhase.RECONNECTING -> CastKitTheme.colorScheme.primary
        CastPhase.RUNNING -> CastKitTheme.semantic.success
        CastPhase.ERROR -> CastKitTheme.colorScheme.error
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(CastKitSpacing.cardInside),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = dotColor)

        Spacer(Modifier.width(CastKitSpacing.space3))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = CastKitTheme.typography.headlineSmall,
                    color = CastKitTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (detail != null) {
                    Spacer(Modifier.width(CastKitSpacing.space2))
                    Text(
                        text = detail,
                        style = CastKitTheme.typography.bodySmall,
                        color = CastKitTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (stats != null) {
                Spacer(Modifier.height(CastKitSpacing.space1))
                Text(
                    text = stats,
                    style = CastKitTheme.typography.bodySmall,
                    color = CastKitTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 状态圆点。抽成独立 composable 只是为了让主 Row 的层级读起来干净。 */
@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(CastKitSizes.statusDot)
            .clip(CircleShape)
            .background(color),
    )
}
