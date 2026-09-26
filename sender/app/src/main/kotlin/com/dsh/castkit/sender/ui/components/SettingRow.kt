package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/** M3 规范的禁用内容不透明度。 */
private const val DisabledContentAlpha = 0.38f

/**
 * 通用设置行：左图标（可选）+ 标题 + 说明（可选）+ 右侧动作（可选）。
 *
 * 它是"接收端设备列表 / 手动输入地址入口 / 显示设置 / 排序选择"这些行的统一底座。
 * 行高下限 56dp，整行可点，点击区天然 ≥48dp。
 *
 * @param onClick 传 null 表示这一行不可点（纯展示），此时不挂 ripple。
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val titleColor: Color = if (enabled) {
        CastKitTheme.colorScheme.onSurface
    } else {
        CastKitTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha)
    }
    val summaryColor: Color = if (enabled) {
        CastKitTheme.colorScheme.onSurfaceVariant
    } else {
        CastKitTheme.colorScheme.onSurfaceVariant.copy(alpha = DisabledContentAlpha)
    }

    val interactive: Modifier = if (onClick != null && enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interactive)
            .heightIn(min = CastKitSizes.listRowMinHeight)
            .padding(
                horizontal = CastKitSpacing.cardInside,
                vertical = CastKitSpacing.space3,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(CastKitSpacing.space3))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = CastKitTheme.typography.titleSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = CastKitTheme.typography.bodySmall,
                    color = summaryColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            Spacer(Modifier.width(CastKitSpacing.space3))
            trailing()
        }
    }
}
