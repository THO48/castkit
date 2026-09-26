package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 吸底主操作按钮：56dp 高、28dp 圆角（`shapes.extraLarge`）、主色填充。
 *
 * 文字色跟随 `onPrimary`——**深色模式下是近黑而不是白色**。这是设计系统
 * 最关键的一条：`#6FA8F5` 配白字只有 2.44:1，连 3:1 都不到。
 *
 * 自带一层 `background` 色的 `Surface`，所以贴在滚动内容底部时会遮住滑过的内容，
 * 不需要页面再包一层。不处理 window insets——底部是 `NavigationBar` 还是
 * 系统手势条，由页面决定往哪里放。
 *
 * @param hint 按钮上方的说明文字（例如"投屏中，无法开始"）。传 null 不占位。
 */
@Composable
fun PrimaryBottomButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    hint: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CastKitTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.padding(
                start = CastKitSpacing.pageHorizontal,
                end = CastKitSpacing.pageHorizontal,
                top = CastKitSpacing.space3,
                bottom = CastKitSpacing.space4,
            ),
        ) {
            if (hint != null) {
                Text(
                    text = hint,
                    style = CastKitTheme.typography.bodySmall,
                    color = CastKitTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(CastKitSpacing.space2))
            }

            Button(
                onClick = onClick,
                enabled = enabled && !loading,
                shape = CastKitTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CastKitTheme.colorScheme.primary,
                    contentColor = CastKitTheme.colorScheme.onPrimary,
                    disabledContainerColor = CastKitTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = CastKitTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CastKitSizes.primaryButtonHeight),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (loading) {
                        CircularProgressIndicator(
                            color = CastKitTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Text(
                            text = text,
                            style = CastKitTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
