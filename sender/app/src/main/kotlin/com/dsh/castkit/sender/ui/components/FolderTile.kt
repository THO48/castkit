package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 文件夹网格单元：圆形品牌色淡底图标 + 名称 + 视频个数。
 *
 * 用 M3 的 `Folder` 图标配 `primaryContainer` 淡底——改造前是手绘两个圆角矩形
 * 拼出来的文件夹形状（因为当时的图标集里没有 Folder），现在 icon-extended
 * 已经在依赖里，不需要再手绘。
 *
 * ★ [avatarSize] / [glyphSize] 可调：预览大小有"最小 5 列 / 小 4 列"这类档位，
 * 5 列时每个格子只有约 62dp 宽，固定 56dp 的圆形头像会撑破网格。
 * 由调用方按当前列数传入，组件自身不感知列数（不把媒体库的枚举耦合进通用组件）。
 */
@Composable
fun FolderTile(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    countText: String? = null,
    showCount: Boolean = true,
    nameMaxLines: Int = 1,
    avatarSize: Dp = CastKitSizes.folderAvatar,
    glyphSize: Dp = CastKitSizes.folderGlyph,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CastKitTheme.shapes.medium)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(CastKitTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = CastKitTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(glyphSize),
            )
        }

        Spacer(Modifier.height(CastKitSpacing.space2))

        Text(
            text = name,
            style = CastKitTheme.typography.titleSmall,
            color = CastKitTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = nameMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        if (showCount && countText != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = countText,
                style = CastKitTheme.typography.bodySmall,
                color = CastKitTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
