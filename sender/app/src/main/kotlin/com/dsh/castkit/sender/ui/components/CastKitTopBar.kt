package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.dsh.castkit.sender.ui.CastKitTheme

/**
 * 设计系统统一的顶栏。
 *
 * 单独拆出来（而不是只用 `CastKitScaffold`）是因为三个页面的骨架不一样：
 * 投屏页与视频页需要在**顶栏之下**各自维护"滚动区 + 吸底按钮"的结构，
 * 嵌套两层 `Scaffold` 会把 insets 和 padding 算两遍。所以外壳只挂底部导航与
 * Snackbar 宿主，顶栏由页面自己作为普通 composable 放在 Column 的第一格——
 * 这样它天然固定在顶部，不随内容滚动。
 *
 * ★ **播放页也必须用这个组件**，不要自绘顶栏。
 * 自绘过一次的后果是实测出来的：播放页的返回箭头落在 28.2dp，而本组件
 * （也就是文件列表页）落在 16.4dp，差了 11.8dp；标题差了 15.6dp。
 * 原因是 M3 `TopAppBar` 内部有自己的起始内边距（`TopAppBarHorizontalPadding = 4dp`）
 * 与 48dp 导航图标槽，自绘时按页面的 16dp 内边距铺，就必然对不上。
 * 复用本组件后对齐由结构保证，不会再漂。
 *
 * 顶栏**只**渲染传入的图标，不内置任何装饰性入口（设计系统 §7）。
 *
 * @param titleContent 自定义标题内容。传了就用它替代默认的 `Text`——
 *        播放页需要跑马灯标题与更小的字号，但内边距仍走同一套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastKitTopBar(
    title: String = "",
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    containerColor: Color = CastKitTheme.colorScheme.background,
    contentColor: Color = CastKitTheme.colorScheme.onBackground,
) {
    TopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = CastKitTheme.typography.titleLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
            navigationIconContentColor = contentColor,
            titleContentColor = contentColor,
            actionIconContentColor = contentColor,
        ),
    )
}
