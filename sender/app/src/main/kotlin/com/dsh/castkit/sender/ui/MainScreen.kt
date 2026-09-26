package com.dsh.castkit.sender.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.castkit.sender.CastConfig
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.CastMode
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.net.LanCastFileServer

/**
 * 底部导航的两个 Tab。它们是**两个独立页面**，各有独立的顶栏与滚动位置，
 * 唯一的共享部分是这条导航栏本身。
 */
private enum class HomeTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    CAST(R.string.tab_cast, Icons.AutoMirrored.Filled.Send),

    // 用 VideoLibrary 而不是 PlayArrow：这一页是"视频库"，不是播放动作
    VIDEO(R.string.tab_video, Icons.Filled.VideoLibrary),
}

/**
 * 应用外壳（全部 Material 3）。
 *
 * 结构：
 * ```
 * Box
 *  ├── Scaffold(bottomBar = NavigationBar, snackbarHost = ...)
 *  │    └── when (tab) { CAST -> CastSettingsScreen ; VIDEO -> FileListScreen }
 *  └── if (playing != null) VideoPlayerScreen        ← 覆盖层，不是导航目的地
 * ```
 *
 * 播放页做成**覆盖层**而不是替换整棵界面树：下面的视频库保持组合，
 * 退出播放时才能还停在原来打开的文件夹（连滚动位置都在）。这是"1:1 保留"的一部分。
 *
 * 外壳只负责底部导航与 Snackbar 宿主，**不挂顶栏**——各页面的 `TopAppBar`
 * 由页面自己放在 Column 的第一格，这样它固定不动而内容可滚，也避免
 * `Scaffold` 嵌套导致 insets 被算两遍。
 */
@Composable
fun MainScreen(
    initial: CastConfig,
    onPickVideo: () -> Unit,
    onPickVideoUri: (Uri) -> Unit,
    onQuickCast: (Uri, String, Int, Long) -> Unit,
    onRemoteToggle: () -> Unit,
    onRemoteSeek: (Long) -> Unit,
    onStopVideo: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    discovery: LanCastDiscovery,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(HomeTab.CAST) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 投屏页的状态提升到 ViewModel：配置变更 / 页面重建后不再丢失
    val castVm: CastViewModel = viewModel()
    LaunchedEffect(Unit) { castVm.applyInitial(initial) }

    // 视频库页同理：当前目录、每目录滚动位置、排序与显示设置都在 VM 里
    val browserVm: BrowserViewModel = viewModel()

    // 播放页：把 LocalVideoPlayer 实例从 remember 提升进 VM（Q13=C 里唯一真正的状态提升）
    val playerVm: PlayerViewModel = viewModel()

    // 不订阅 CastBus（避免每秒码率变化触发整棵重建），按需读一次即可
    val isVideoCasting: () -> Boolean = {
        val st = CastBus.state.value
        st.mode == CastMode.VIDEO &&
            (st.phase == CastPhase.RUNNING || st.phase == CastPhase.CONNECTING)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = CastKitTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    containerColor = CastKitTheme.colorScheme.surface,
                    contentColor = CastKitTheme.colorScheme.onSurfaceVariant,
                ) {
                    HomeTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(item.labelRes),
                                    style = CastKitTheme.typography.labelMedium,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CastKitTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = CastKitTheme.colorScheme.primary,
                                indicatorColor = CastKitTheme.colorScheme.primaryContainer,
                                unselectedIconColor = CastKitTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = CastKitTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            // 只吃掉底部内边距：顶栏自己带 statusBars inset，再吃一次会重复
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                when (tab) {
                    HomeTab.CAST -> CastSettingsScreen(
                        vm = castVm,
                        discovery = discovery,
                        onStart = onStart,
                        onStop = onStop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    HomeTab.VIDEO -> FileListScreen(
                        vm = browserVm,
                        onPlay = { uri ->
                            onPickVideoUri(uri)
                            playing = uri
                        },
                        onUseSystemPicker = onPickVideo,
                    )
                }
            }
        }

        // 播放页盖在最上层（不透明背景 + 自己消费点击），下层的视频库继续保留状态
        val playingUri = playing
        if (playingUri != null) {
            val title = remember(playingUri) {
                runCatching { LanCastFileServer.describe(context, playingUri).name }.getOrDefault("")
            }
            VideoPlayerScreen(
                uri = playingUri,
                title = title,
                vm = playerVm,
                discovery = discovery,
                onCastTo = { host, port, positionMs -> onQuickCast(playingUri, host, port, positionMs) },
                onStopCast = onStopVideo,
                onRemoteToggle = onRemoteToggle,
                onRemoteSeek = onRemoteSeek,
                onClose = {
                    // 退出播放页即结束投送：否则播放页关了，接收端还在自己播
                    if (isVideoCasting()) onStopVideo()
                    playing = null
                },
            )
        }
    }
}
