package com.dsh.castkit.sender.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.media.BrowserIconSize
import com.dsh.castkit.sender.media.FolderEntry
import com.dsh.castkit.sender.media.VideoDurations
import com.dsh.castkit.sender.media.VideoItem
import com.dsh.castkit.sender.media.VideoLibrary
import com.dsh.castkit.sender.media.VideoSortField
import com.dsh.castkit.sender.media.VideoThumbnails
import com.dsh.castkit.sender.ui.components.CastKitTopBar
import com.dsh.castkit.sender.ui.components.EmptyState
import com.dsh.castkit.sender.ui.components.FolderTile as GridFolderTile
import com.dsh.castkit.sender.ui.components.SectionHeader
import com.dsh.castkit.sender.ui.components.SectionHeaderLevel
import com.dsh.castkit.sender.ui.components.SettingRow
import com.dsh.castkit.sender.ui.components.VideoTile as GridVideoTile
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing

/**
 * 栅格基准列数：能被 1/2/3/4/5/6 整除，方便文件夹与视频用不同列数
 * （网格列数固定 60，靠每项的 span 实现"文件夹 3 列、视频 2 列"）。
 */
private const val BASE_COLUMNS = 60

/**
 * 页面 1 —— 文件 / 视频列表页（底部导航「视频」Tab）。
 *
 * 与改造前一致的行为，全部 1:1 保留：
 *  - 按目录层级浏览，返回键先在目录树里往上走，到根才交给系统；
 *  - 每个文件夹各记一份滚动位置（在 VM 里）；
 *  - 排序（时间/名称/大小/时长 × 正倒序）与预览大小（四档）持久化；
 *  - 可选的 `.nomedia` 目录显示（需要「所有文件访问」权限）；
 *  - 两段式加载：先出内容、再在后台补扫。
 *
 * 与改造前不同的三处（都在设计文档 §10 里记录过）：
 *  1. **搜索已移除**，顶栏右侧只剩「排序」直连 + 溢出菜单（≡）；
 *  2. 列数用**等高网格**而不是瀑布流（缩略图固定 16:9，瀑布流只有开销没有收益）；
 *  3. 加载/空/权限三态收敛成 `LibraryUiState` + 独立的 `LibraryPermissionState`。
 */
@Composable
fun FileListScreen(
    vm: BrowserViewModel,
    onPlay: (Uri) -> Unit,
    onUseSystemPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onPermissionResult(granted) }

    // 两段式加载的入口：权限、.nomedia 开关、强制重扫三者任一变化都重新走一遍
    LaunchedEffect(vm.permission, vm.includeNoMedia, vm.refreshTick) { vm.load() }

    // 返回键：先在目录树里往上走，到根才交给系统
    BackHandler(enabled = vm.currentPath.isNotEmpty()) { vm.navigateUp() }

    Column(modifier = modifier.fillMaxSize()) {
        CastKitTopBar(
            title = if (vm.currentPath.isEmpty()) {
                stringResource(R.string.title_local_videos)
            } else {
                VideoLibrary.breadcrumb(vm.currentPath)
            },
            navigationIcon = {
                if (vm.currentPath.isNotEmpty()) {
                    IconButton(onClick = { vm.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_up_level),
                        )
                    }
                }
            },
            actions = {
                // 排序是"找东西"的唯一高频手段（搜索已移除），所以给它一个直连图标
                IconButton(onClick = { vm.setShowSortMenu(true) }) {
                    Icon(
                        imageVector = Icons.Filled.SwapVert,
                        contentDescription = stringResource(R.string.cd_sort),
                    )
                }

                // 低频但不可删的两个入口收进溢出菜单，落实"精简顶栏 + 不放装饰性图标"
                Box {
                    IconButton(onClick = { vm.setShowOverflowMenu(true) }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.cd_more),
                        )
                    }
                    DropdownMenu(
                        expanded = vm.showOverflowMenu,
                        onDismissRequest = { vm.setShowOverflowMenu(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cd_view_settings)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Settings, contentDescription = null)
                            },
                            onClick = {
                                // 刚从系统设置回来时刷新一次「所有文件访问」状态
                                vm.refreshPermission()
                                vm.setShowOverflowMenu(false)
                                vm.setShowViewMenu(true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cd_rescan)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                            },
                            onClick = {
                                vm.setShowOverflowMenu(false)
                                vm.refreshPermission()
                                vm.requestLoad()
                            },
                        )
                    }
                }
            },
        )

        SortDialog(vm)
        ViewDialog(
            vm = vm,
            onNeedAllFiles = { openAllFilesSettings(context) },
        )

        if (vm.permission == LibraryPermissionState.Denied) {
            LibraryPermissionPane(
                onRequest = {
                    permissionLauncher.launch(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_VIDEO
                        } else {
                            @Suppress("DEPRECATION")
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        },
                    )
                },
                onUseSystemPicker = onUseSystemPicker,
            )
            return@Column
        }

        if (vm.permission == LibraryPermissionState.Partial) {
            PartialAccessHint(
                onPickMore = { permissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO) },
            )
        }

        if (vm.includeNoMedia && !vm.allFilesGranted) {
            Text(
                text = stringResource(R.string.nomedia_need_all_files),
                style = CastKitTheme.typography.bodySmall,
                color = CastKitTheme.colorScheme.error,
                modifier = Modifier.padding(
                    horizontal = CastKitSpacing.pageHorizontal,
                    vertical = CastKitSpacing.space1,
                ),
            )
        }

        when (val state = vm.state) {
            LibraryUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = CastKitTheme.colorScheme.primary)
            }

            LibraryUiState.Empty -> EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = stringResource(R.string.empty_no_videos_title),
                description = stringResource(
                    if (vm.currentPath.isEmpty()) {
                        R.string.empty_no_videos
                    } else {
                        R.string.empty_folder
                    },
                ),
            )

            is LibraryUiState.Content -> LibraryGrid(
                vm = vm,
                state = state,
                onPlay = onPlay,
            )
        }
    }
}

/**
 * 文件夹 + 视频混排的等高网格。
 *
 * 用统一的基准列数配合不同 span 实现"文件夹 N 列、视频 M 列"——改造前就是这么做的，
 * 保持不变。**刻意不用 `LazyVerticalStaggeredGrid`**：缩略图固定 16:9，
 * 瀑布流里每项高度完全一样，只有额外测量开销没有视觉收益。
 */
@Composable
private fun LibraryGrid(
    vm: BrowserViewModel,
    state: LibraryUiState.Content,
    onPlay: (Uri) -> Unit,
) {
    val iconSize = vm.iconSize
    val avatarSize = when (iconSize) {
        BrowserIconSize.TINY -> 40.dp
        BrowserIconSize.SMALL -> 48.dp
        BrowserIconSize.MEDIUM -> CastKitSizes.folderAvatar
        BrowserIconSize.LARGE -> 72.dp
    }

    LazyVerticalGrid(
        state = vm.gridState(),
        columns = GridCells.Fixed(BASE_COLUMNS),
        contentPadding = PaddingValues(CastKitSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(CastKitSizes.gridGap),
        verticalArrangement = Arrangement.spacedBy(CastKitSizes.gridGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.folders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    text = stringResource(R.string.browser_folder_count, state.folders.size),
                    level = SectionHeaderLevel.Section,
                )
            }
            items(
                items = state.folders,
                key = { "folder:" + it.path },
                span = { GridItemSpan(BASE_COLUMNS / iconSize.folderCols) },
            ) { folder ->
                LibraryFolderTile(
                    folder = folder,
                    iconSize = iconSize,
                    avatarSize = avatarSize,
                    onClick = { vm.openFolder(folder.path) },
                )
            }
        }

        if (state.videos.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    text = stringResource(R.string.browser_video_count, state.videos.size),
                    level = SectionHeaderLevel.Section,
                )
            }
            items(
                items = state.videos,
                key = { "video:" + it.key },
                span = { GridItemSpan(BASE_COLUMNS / iconSize.videoCols) },
            ) { video ->
                LibraryVideoTile(video = video, iconSize = iconSize, onPlay = onPlay)
            }
        }
    }
}

@Composable
private fun LibraryFolderTile(
    folder: FolderEntry,
    iconSize: BrowserIconSize,
    avatarSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    GridFolderTile(
        name = folder.name,
        countText = if (folder.subFolderCount > 0) {
            stringResource(
                R.string.folder_count_with_subfolder,
                folder.totalCount,
                folder.subFolderCount,
            )
        } else {
            stringResource(R.string.folder_count, folder.totalCount)
        },
        // 列数多的时候格子窄，数量行和两行标题都放不下
        showCount = iconSize.folderCols <= 4,
        nameMaxLines = if (iconSize.folderCols >= 4) 2 else 1,
        avatarSize = avatarSize,
        glyphSize = avatarSize / 2,
        onClick = onClick,
    )
}

@Composable
private fun LibraryVideoTile(
    video: VideoItem,
    iconSize: BrowserIconSize,
    onPlay: (Uri) -> Unit,
) {
    val context = LocalContext.current

    // 缩略图三级缓存（内存 LRU → 磁盘 → 现抽帧）与并发上限都在 VideoThumbnails 里，
    // 这里只负责把它换成 Compose 能画的 ImageBitmap。滚出屏幕时 produceState 取消，
    // 抽帧任务随之中断（改造前依赖的就是这个机制）。
    val thumb by produceState<Bitmap?>(
        initialValue = VideoThumbnails.cached(video.uri),
        video.uri,
    ) {
        value = VideoThumbnails.load(context, video.uri)
    }

    // 不在媒体库里的条目（.nomedia）扫描时可能没探到时长，这里按需补
    val duration by produceState(initialValue = video.durationMs, video.key) {
        value = if (video.durationMs > 0) video.durationMs else VideoDurations.resolve(video.uri)
    }

    val meta = buildString {
        append(VideoLibrary.formatDuration(duration))
        if (video.width > 0 && video.height > 0) {
            append(" · ${video.width}×${video.height}")
        }
        val size = VideoLibrary.formatSize(video.sizeBytes)
        if (size.isNotEmpty()) append(" · $size")
    }

    GridVideoTile(
        title = video.name,
        meta = meta,
        onClick = { onPlay(video.uri) },
        thumbnail = thumb?.asImageBitmap(),
        durationText = if (duration > 0) VideoLibrary.formatDuration(duration) else null,
        showMeta = iconSize.videoCols <= 2,
        // 网格里同时有 6–8 个可见单元，每个都跑无限循环 marquee 是持续动画开销，
        // 所以网格保持单行省略；播放器页的标题会传 true。
        marqueeTitle = false,
    )
}

@Composable
private fun SortDialog(vm: BrowserViewModel) {
    AppDialog(
        show = vm.showSortMenu,
        onDismiss = { vm.setShowSortMenu(false) },
        title = stringResource(R.string.sort_dialog_title),
    ) {
        VideoSortField.entries.forEach { field ->
            SettingRow(
                title = stringResource(R.string.sort_by_field, field.label),
                trailing = {
                    if (vm.sortField == field) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.cd_selected),
                            tint = CastKitTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { vm.setSortField(field) },
            )
        }
        SettingRow(
            title = stringResource(if (vm.sortAsc) R.string.sort_to_desc else R.string.sort_to_asc),
            summary = stringResource(
                if (vm.sortAsc) R.string.sort_current_asc else R.string.sort_current_desc,
            ),
            onClick = { vm.setSortAsc(!vm.sortAsc) },
        )
    }
}

@Composable
private fun ViewDialog(vm: BrowserViewModel, onNeedAllFiles: () -> Unit) {
    AppDialog(
        show = vm.showViewMenu,
        onDismiss = { vm.setShowViewMenu(false) },
        title = stringResource(R.string.cd_view_settings),
    ) {
        BrowserIconSize.entries.forEach { size ->
            SettingRow(
                title = stringResource(
                    R.string.icon_size_format,
                    size.label,
                    size.folderCols,
                    size.videoCols,
                ),
                trailing = {
                    if (vm.iconSize == size) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.cd_selected),
                            tint = CastKitTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { vm.setIconSize(size) },
            )
        }
        SettingRow(
            title = stringResource(R.string.nomedia_switch_title),
            summary = if (vm.includeNoMedia && !vm.allFilesGranted) {
                stringResource(R.string.nomedia_need_grant)
            } else {
                stringResource(R.string.nomedia_hint)
            },
            trailing = {
                Switch(
                    checked = vm.includeNoMedia,
                    onCheckedChange = { if (!vm.toggleIncludeNoMedia()) onNeedAllFiles() },
                )
            },
            onClick = { if (!vm.toggleIncludeNoMedia()) onNeedAllFiles() },
        )
    }
}

@Composable
private fun PartialAccessHint(onPickMore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = CastKitSpacing.pageHorizontal,
                vertical = CastKitSpacing.space1,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.partial_access_hint),
            style = CastKitTheme.typography.bodySmall,
            color = CastKitTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(CastKitSpacing.space2))
        TextButton(onClick = onPickMore) {
            Text(
                text = stringResource(R.string.action_pick_more),
                style = CastKitTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 权限引导。用 `EmptyState` + 两个动作，而不是原来那种"占满整屏的文字面板"。
 */
@Composable
private fun LibraryPermissionPane(onRequest: () -> Unit, onUseSystemPicker: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.VideoLibrary,
        title = stringResource(R.string.permission_title),
        description = stringResource(R.string.permission_desc),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onRequest,
                    shape = CastKitTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.action_grant),
                        style = CastKitTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.padding(top = CastKitSpacing.space2))
                TextButton(onClick = onUseSystemPicker) {
                    Text(
                        text = stringResource(R.string.action_use_system_picker),
                        style = CastKitTheme.typography.labelLarge,
                    )
                }
            }
        },
    )
}

/** 跳系统设置里的「所有文件访问」页。 */
private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val app = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(app) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
