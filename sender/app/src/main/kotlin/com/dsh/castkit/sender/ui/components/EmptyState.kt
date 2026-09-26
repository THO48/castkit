package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 空状态：居中图标 + 标题 + 说明 + 可选操作。
 *
 * 整块占满可用高度（`fillMaxSize`），所以放进 `LazyVerticalGrid` 之外的
 * 容器时才有"居中"的效果——页面负责把它放在正确的容器里。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CastKitSpacing.pageHorizontal, vertical = CastKitSpacing.space7),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CastKitTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(CastKitSizes.emptyIcon),
        )

        Spacer(Modifier.height(CastKitSpacing.space4))

        Text(
            text = title,
            style = CastKitTheme.typography.titleSmall,
            color = CastKitTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        if (description != null) {
            Spacer(Modifier.height(CastKitSpacing.space2))
            Text(
                text = description,
                style = CastKitTheme.typography.bodySmall,
                color = CastKitTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (action != null) {
            Spacer(Modifier.height(CastKitSpacing.space6))
            action()
        }
    }
}
