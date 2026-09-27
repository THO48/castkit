package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import com.dsh.castkit.sender.ui.theme.ImmersiveColors
import com.dsh.castkit.sender.ui.theme.LibraryColors

/**
 * 视频网格单元：16:9 缩略图 + 时长角标 + 文件名 + 「分辨率 · 大小」。
 *
 * 缩略图圆角 12dp（`shapes.medium`）；时长角标 4dp 圆角、`#CC000000` 底 +
 * 11sp 白字，贴右下角；文件名单行截断，第二行 12sp 灰色辅助信息。
 *
 * @param thumbnail 已解码的缩略图。传 null 时显示占位底色。缩略图的**加载**
 *        （三级缓存 / 并发限制 / 滚出屏幕取消）不在本组件内，由页面驱动——
 *        这样本组件保持纯展示、可预览、可测试。
 * @param marqueeTitle 是否让文件名滚动。
 *        ★ 默认 **false**（单行省略）。文件列表页会显式传 false：网格里同时有
 *        6–8 个可见单元，每个都跑无限循环的 marquee 是持续动画开销，而这个
 *        项目的滚动性能本身是被专门优化过的（缩略图三级缓存 + 并发上限 2 +
 *        滚出屏幕中断抽帧）。播放器页的文件名会传 true——那里只有一个标题。
 *        如果你希望网格也滚，把 [VideoTile] 的调用点改成 true 即可。
 * @param progressFraction 已播进度 0..1；**null = 没有记录，不画**这条。
 *        画在缩略图正下方（很窄的橙色条，见 [PlaybackProgressBar]）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoTile(
    title: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: ImageBitmap? = null,
    durationText: String? = null,
    showMeta: Boolean = true,
    marqueeTitle: Boolean = false,
    aspectRatio: Float = 16f / 9f,
    progressFraction: Float? = null,
) {
    val placeholder = CastKitTheme.colorScheme.surfaceContainerHighest

    Column(
        modifier = modifier
            .clip(CastKitTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(CastKitTheme.shapes.medium)
                .background(placeholder),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            if (durationText != null) {
                Text(
                    text = durationText,
                    style = CastKitTheme.typography.labelSmall,
                    color = ImmersiveColors.OnDurationBadge,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(CastKitSpacing.space1)
                        .clip(RoundedCornerShape(CastKitSizes.durationBadgeCorner))
                        .background(ImmersiveColors.DurationBadge)
                        .padding(horizontal = CastKitSpacing.space1, vertical = 1.dp),
                )
            }
        }

        // 缩略图与标题之间原本是一条 8dp 的空白。有播放进度时把这条空白改成
        // 「2dp + 进度条 3dp + 3dp」——总高一样，网格不会因为这个条子变得参差。
        if (progressFraction != null) {
            Spacer(Modifier.height(2.dp))
            PlaybackProgressBar(progressFraction, Modifier.fillMaxWidth())
            Spacer(Modifier.height(3.dp))
        } else {
            Spacer(Modifier.height(CastKitSpacing.space2))
        }

        Text(
            text = title,
            style = CastKitTheme.typography.titleSmall,
            color = CastKitTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (marqueeTitle) Modifier.basicMarquee() else Modifier,
        )

        if (showMeta) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                style = CastKitTheme.typography.bodySmall,
                color = CastKitTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 缩略图下方那条「看到哪了」的进度条：很窄（3dp），橙色已播 + 一层淡淡的轨道。
 *
 * 有轨道是为了让人一眼看出"这是一条进度条"而不是装饰线 —— 只画一截橙色短线的话，
 * 看到 20% 的人会以为那是 UI 元素画歪了。轨道用 `onSurface` 低透明度，
 * 深浅主题都自适应；橙色不跟着动态取色变，理由见 [LibraryColors]。
 */
@Composable
private fun PlaybackProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(CastKitSizes.tileProgressHeight)
            .clip(shape)
            .background(CastKitTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(LibraryColors.PlaybackProgress),
        )
    }
}
