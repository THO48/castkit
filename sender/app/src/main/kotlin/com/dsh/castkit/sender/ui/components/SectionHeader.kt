package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 区块标题层级。
 *
 * - [Page] = 20sp / W500，用于页面内的主区块（"接收端" / "画面参数"）。
 * - [Section] = 18sp / W500，用于次级分组（"文件夹" / "视频"）。
 */
enum class SectionHeaderLevel {
    Page,
    Section,
}

/**
 * 区块标题。
 *
 * 纵向留白（上 24dp / 下 12dp）来自设计系统 §4.1 的区块间距，不要在各页面
 * 自己再叠 `Spacer`——重复叠加是改造前"间距散落各处"的老问题的来源。
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    level: SectionHeaderLevel = SectionHeaderLevel.Section,
) {
    val style = when (level) {
        SectionHeaderLevel.Page -> CastKitTheme.typography.titleLarge
        SectionHeaderLevel.Section -> CastKitTheme.typography.titleMedium
    }

    Text(
        text = text,
        style = style,
        color = CastKitTheme.colorScheme.onBackground,
        modifier = modifier.padding(
            start = CastKitSpacing.pageHorizontal,
            end = CastKitSpacing.pageHorizontal,
            top = CastKitSpacing.space6,
            bottom = CastKitSpacing.space3,
        ),
    )
}
