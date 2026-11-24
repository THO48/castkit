package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.net.RemoteFolderEntry
import io.github.jqssun.airplay.net.RemoteLibraryState
import io.github.jqssun.airplay.net.RemoteThumbCache
import io.github.jqssun.airplay.net.RemoteVideoItem
import io.github.jqssun.airplay.net.RemoteVideoLibrary
import io.github.jqssun.airplay.ui.theme.CastColors
import io.github.jqssun.airplay.ui.theme.StatusPill
import io.github.jqssun.airplay.ui.theme.StatusPulseDot
import io.github.jqssun.airplay.ui.theme.liveGreenColor

private const val REMOTE_BASE_COLS = 60

/**
 * 接收端「发送端视频库」浏览选片页：
 * 当用户在平板退出当前播放的视频、或发送端直接共享视频库时展示，
 * 支持目录浏览、全局搜索、实时封面缩略图与一键点播切换。
 */
@Composable
fun RemoteLibraryScreen(
    library: RemoteLibraryState,
    currentPath: String,
    onPathChange: (String) -> Unit,
    onSelectVideo: (RemoteVideoItem) -> Unit,
    onRefresh: () -> Unit,
    onMinimizeToHub: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val config = LocalConfiguration.current
    val isWide = config.screenWidthDp >= 720
    val folderCols = if (isWide) 5 else 3
    val videoCols = if (isWide) 4 else 2

    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    val trimmed = query.trim()
    val folders = remember(library.items, currentPath, trimmed) {
        if (trimmed.isNotEmpty()) emptyList()
        else RemoteVideoLibrary.foldersIn(library.items, currentPath)
    }
    val videos = remember(library.items, currentPath, trimmed) {
        if (trimmed.isNotEmpty()) {
            library.items.filter {
                it.name.contains(trimmed, ignoreCase = true) ||
                    it.folder.contains(trimmed, ignoreCase = true)
            }
        } else {
            RemoteVideoLibrary.videosIn(library.items, currentPath)
        }
    }

    // 进入该视频所在文件夹时自动滚动到上次播放的视频附近
    LaunchedEffect(currentPath, library.activeId, videos.size, folders.size) {
        if (trimmed.isEmpty() && library.activeId.isNotBlank()) {
            val idx = videos.indexOfFirst { it.id == library.activeId }
            if (idx >= 0) {
                val offset = (if (folders.isNotEmpty()) folders.size + 1 else 0) + 1
                gridState.scrollToItem((offset + idx).coerceAtLeast(0))
            }
        }
    }

    BackHandler {
        when {
            trimmed.isNotEmpty() -> query = ""
            currentPath.isNotEmpty() -> onPathChange(currentPath.substringBeforeLast('/', ""))
            else -> onMinimizeToHub()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isWide) 24.dp else 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(
                    onClick = {
                        if (currentPath.isNotEmpty()) {
                            onPathChange(currentPath.substringBeforeLast('/', ""))
                        } else {
                            onMinimizeToHub()
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusPulseDot(color = liveGreenColor(), pulsing = true, size = 7.dp)
                        Text(
                            text = if (currentPath.isEmpty()) "发送端视频库" else currentPath.substringAfterLast('/'),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusPill(
                            text = "${library.deviceName.ifBlank { "手机" }} · ${library.items.size} 个视频",
                            color = liveGreenColor(),
                        )
                    }
                }

                if (isWide) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索发送端视频…") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空")
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.width(280.dp),
                    )
                }

                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新列表")
                }

                FilledTonalButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CastColors.ErrorRedLight.copy(alpha = 0.16f),
                        contentColor = CastColors.ErrorRedDark,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("断开连接")
                }
            }

            if (!isWide) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索发送端所有视频…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空")
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // ---- 面包屑 ----
            if (currentPath.isNotEmpty() && trimmed.isEmpty()) {
                RemoteBreadcrumbBar(
                    currentPath = currentPath,
                    onNavigate = { onPathChange(it) },
                    modifier = Modifier.padding(horizontal = if (isWide) 24.dp else 16.dp, vertical = 4.dp),
                )
            }

            // ---- 主体网格 ----
            when {
                library.loading && library.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("正在读取发送端视频列表…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                library.error != null && library.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("读取失败：${library.error}", color = MaterialTheme.colorScheme.error)
                            FilledTonalButton(onClick = onRefresh) {
                                Text("重试")
                            }
                        }
                    }
                }

                folders.isEmpty() && videos.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Rounded.VideoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(52.dp),
                            )
                            Text(
                                if (trimmed.isNotEmpty()) "未找到匹配「$trimmed」的视频" else "发送端当前目录没有视频",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(REMOTE_BASE_COLS),
                        contentPadding = PaddingValues(
                            horizontal = if (isWide) 24.dp else 14.dp,
                            vertical = 10.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (folders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "文件夹 · ${folders.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(
                                items = folders,
                                key = { "rf:" + it.path },
                                span = { GridItemSpan(REMOTE_BASE_COLS / folderCols) },
                            ) { folder ->
                                RemoteFolderTile(folder = folder, onClick = { onPathChange(folder.path) })
                            }
                        }

                        if (videos.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    if (trimmed.isNotEmpty()) "搜索结果 · ${videos.size}" else "视频 · ${videos.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = if (folders.isNotEmpty()) 6.dp else 0.dp),
                                )
                            }
                            items(
                                items = videos,
                                key = { "rv:" + it.id },
                                span = { GridItemSpan(REMOTE_BASE_COLS / videoCols) },
                            ) { video ->
                                RemoteVideoTile(
                                    video = video,
                                    isActive = video.id == library.activeId,
                                    showFolder = trimmed.isNotEmpty(),
                                    onClick = { onSelectVideo(video) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteBreadcrumbBar(
    currentPath: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(currentPath) { currentPath.split('/').filter { it.isNotEmpty() } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onNavigate("") },
        ) {
            Text(
                "全部目录",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        var acc = ""
        segments.forEachIndexed { idx, seg ->
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            val target = acc
            val isLast = idx == segments.lastIndex
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isLast) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isLast) { onNavigate(target) },
            ) {
                Text(
                    seg,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLast) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoteFolderTile(
    folder: RemoteFolderEntry,
    onClick: () -> Unit,
) {
    val coverUrl = folder.coverThumbUrl ?: ""
    val thumb by produceState<Bitmap?>(initialValue = RemoteThumbCache.cached(coverUrl), coverUrl) {
        value = RemoteThumbCache.load(coverUrl)
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = folder.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0x28000000), Color(0xAA000000)))),
                )
            } else {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                Text("${folder.totalCount}", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }

            if (folder.subFolderCount > 0) {
                Text(
                    "+${folder.subFolderCount} 目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x88000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RemoteVideoTile(
    video: RemoteVideoItem,
    isActive: Boolean,
    showFolder: Boolean,
    onClick: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(initialValue = RemoteThumbCache.cached(video.thumbUrl), video.thumbUrl) {
        value = RemoteThumbCache.load(video.thumbUrl)
    }
    val qualityBadge = remember(video.width, video.height) {
        val shortSide = minOf(video.width, video.height)
        when {
            shortSide >= 2100 -> "4K"
            shortSide >= 1400 -> "2K"
            shortSide >= 1000 -> "1080P"
            shortSide >= 700 -> "720P"
            else -> null
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isActive) 1.8.dp else 1.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF141820)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = video.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(34.dp),
                    )
                }

                if (isActive) {
                    Text(
                        "上次播放",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                } else if (qualityBadge != null) {
                    Text(
                        qualityBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xAA000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                if (video.durationMs > 0) {
                    Text(
                        RemoteVideoLibrary.formatDuration(video.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xB8000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    video.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    val sz = RemoteVideoLibrary.formatSize(video.sizeBytes)
                    if (sz.isNotEmpty()) append(sz)
                    if (video.width > 0 && video.height > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${video.width}×${video.height}")
                    }
                    if (showFolder && video.folder.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(video.folder)
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
