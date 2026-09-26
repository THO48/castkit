package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.dsh.castkit.sender.ui.CastKitTheme

/**
 * 页面外壳：顶栏 + 可选的底部栏 + Snackbar 宿主。
 *
 * 与 M3 `Scaffold` 的区别只有三点，都是为了落实设计系统的决定：
 *  - 容器色固定取 `background`，不跟随 `surfaceTint` 抬升（无边框设计）；
 *  - 标题固定用 `titleLarge`（20sp / W500），单行截断；
 *  - Snackbar 宿主由本组件统一挂载，页面不再各自处理。
 *
 * 顶栏**只**通过参数接收图标，避免出现无实际功能的装饰性图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastKitScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = CastKitTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = CastKitTheme.typography.titleLarge,
                        color = CastKitTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CastKitTheme.colorScheme.background,
                    scrolledContainerColor = CastKitTheme.colorScheme.background,
                    navigationIconContentColor = CastKitTheme.colorScheme.onBackground,
                    titleContentColor = CastKitTheme.colorScheme.onBackground,
                    actionIconContentColor = CastKitTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        content = content,
    )
}
