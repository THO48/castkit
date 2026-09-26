package com.dsh.castkit.sender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 应用统一的对话框，基于 M3 的 `Surface` + 系统 `Dialog`。
 *
 * 保留系统 `Dialog`（而不是换成 M3 的 `AlertDialog`）的原因：本项目的对话框内容是
 * **任意 composable 列表**（设备列表、排序项、显示设置），`AlertDialog` 的
 * `title`/`text`/`confirmButton` 三段式套不下可滚动的行列表。
 * 系统 `Dialog` 是独立窗口，返回键与点击外部由系统负责——这两点在改造前
 * 用 Miuix `SuperDialog` 时是坏的（弹窗关不掉、返回键直接退到桌面），换回 M3 后同样安全。
 *
 * 圆角取 `shapes.large`（16dp），容器色取 `surfaceContainerHigh`（浅色 `#E8ECF2` /
 * 深色 `#2C2C2C`），落实 §5 的"浮层比卡片高一级"。
 */
@Composable
internal fun AppDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    // 标准 Dialog 只要被组合就显示，所以这里必须自己按状态短路
    if (!show) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .padding(horizontal = CastKitSpacing.space6),
            shape = CastKitTheme.shapes.large,
            color = CastKitTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(CastKitSpacing.cardInside)) {
                title?.let {
                    Text(
                        text = it,
                        style = CastKitTheme.typography.titleMedium,
                        color = CastKitTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = CastKitSpacing.space3),
                    )
                }
                summary?.let {
                    Text(
                        text = it,
                        style = CastKitTheme.typography.bodySmall,
                        color = CastKitTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = CastKitSpacing.space3),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(CastKitSpacing.space1),
                ) {
                    content()
                }

                Spacer(Modifier.height(CastKitSpacing.space2))

                // 兜底出口：任何情况下都关得掉
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        text = stringResource(R.string.action_done),
                        style = CastKitTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
