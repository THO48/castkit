package com.dsh.castkit.sender.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.media.BrowserIconSize
import com.dsh.castkit.sender.media.FolderEntry
import com.dsh.castkit.sender.media.VideoDurations
import com.dsh.castkit.sender.media.VideoItem
import com.dsh.castkit.sender.media.VideoLibrary
import com.dsh.castkit.sender.media.VideoLibraryCache
import com.dsh.castkit.sender.media.VideoSortField
import com.dsh.castkit.sender.media.VideoThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 栅格基准列数：能被 1/2/3/4/5/6 整除，方便文件夹与视频用不同列数。 */
private const val BASE_COLUMNS = 60

private fun mergeVideos(store: List<VideoItem>, extras: List<VideoItem>): List<VideoItem> {
    if (extras.isEmpty()) return store
    val known = store.mapTo(HashSet()) { it.key }
    return store + extras.filter { it.key !in known }
}

/**
 * 重写后的本机视频库浏览器：
 * - 实时搜索（全局/当前目录按文件名检索）
 * - 交互式面包屑导航栏（点击任意上级目录直接跳转）
 * - 真实文件夹封面缩略图 + 数量角标
 * - 视频卡片带分辨率角标（4K / 1080P / 720P）、断点续播进度条、以及右上角「一键投屏」入口
 * - 点击视频进入播放器并携带当前目录播放列表（支持上一集/下一集连播）
 */
@Composable
fun VideoBrowser(
    onPlay: (video: VideoItem, playlist: List<VideoItem>) -> Unit,
    onQuickCastVideo: (video: VideoItem, playlist: List<VideoItem>) -> Unit,
    onUseSystemPicker: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasVideoPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok || hasVideoPermission(context) }

    var allVideos by remember { mutableStateOf(VideoLibraryCache.merged ?: emptyList()) }
    var loading by remember { mutableStateOf(allVideos.isEmpty()) }
    var bgScanning by remember { mutableStateOf(false) }
    var currentPath by remember { mutableStateOf(VideoLibrary.ROOT_PATH) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var lastRefreshTick by remember { mutableIntStateOf(0) }

    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var sortField by remember { mutableStateOf(Prefs.browserSortField(context)) }
    var sortAsc by remember { mutableStateOf(Prefs.browserSortAsc(context)) }
    var iconSize by remember { mutableStateOf(Prefs.browserIconSize(context)) }
    var includeNoMedia by remember { mutableStateOf(Prefs.browserIncludeNoMedia(context)) }
    var allFilesGranted by remember { mutableStateOf(VideoLibrary.canReadAllFiles(context)) }
    val showSortSheet = remember { mutableStateOf(false) }
    val showViewSheet = remember { mutableStateOf(false) }

    LaunchedEffect(granted, includeNoMedia, refreshTick) {
        if (!granted) return@LaunchedEffect
        val forced = refreshTick != lastRefreshTick
        lastRefreshTick = refreshTick
        if (allVideos.isEmpty()) loading = true
        allFilesGranted = VideoLibrary.canReadAllFiles(context)

        val store = withContext(Dispatchers.IO) { VideoLibrary.loadFromMediaStore(context) }
        val cachedExtras = if (includeNoMedia && allFilesGranted) {
            withContext(Dispatchers.IO) { VideoLibrary.cachedNoMedia(context) }
        } else {
            emptyList()
        }
        allVideos = mergeVideos(store, cachedExtras)
        VideoLibraryCache.merged = allVideos
        loading = false

        val cacheStale = VideoLibrary.noMediaCacheAgeMs(context) > VideoLibrary.NOMEDIA_TTL_MS
        if (includeNoMedia && allFilesGranted && (forced || cacheStale)) {
            bgScanning = true
            try {
                val extras = withContext(Dispatchers.IO) { VideoLibrary.scanNoMediaFolders(context) }
                if (extras.isNotEmpty() || forced) {
                    withContext(Dispatchers.IO) { VideoLibrary.saveNoMediaCache(context, extras) }
                }
                allVideos = mergeVideos(store, extras)
                VideoLibraryCache.merged = allVideos
            } finally {
                bgScanning = false
            }
        }
    }

    val gridStates = remember { mutableMapOf<String, LazyGridState>() }
    val gridState = gridStates.getOrPut(if (searchQuery.isNotBlank()) "__search__" else currentPath) {
        LazyGridState()
    }

    val trimmedQuery = searchQuery.trim()
    val folders = remember(allVideos, currentPath, sortField, sortAsc, trimmedQuery) {
        if (trimmedQuery.isNotEmpty()) emptyList()
        else VideoLibrary.sortFolders(VideoLibrary.foldersIn(allVideos, currentPath), sortField, sortAsc)
    }
    val videos = remember(allVideos, currentPath, sortField, sortAsc, trimmedQuery) {
        val raw = if (trimmedQuery.isNotEmpty()) {
            allVideos.filter {
                it.name.contains(trimmedQuery, ignoreCase = true) ||
                    it.folderPath.contains(trimmedQuery, ignoreCase = true)
            }
        } else {
            VideoLibrary.videosIn(allVideos, currentPath)
        }
        VideoLibrary.sortVideos(raw, sortField, sortAsc)
    }

    BackHandler(enabled = searchOpen || currentPath.isNotEmpty()) {
        when {
            searchOpen -> {
                searchOpen = false
                searchQuery = ""
            }
            currentPath.isNotEmpty() -> {
                currentPath = currentPath.substringBeforeLast('/', "")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentPath.isNotEmpty() && !searchOpen) {
                IconButton(onClick = { currentPath = currentPath.substringBeforeLast('/', "") }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_up_level))
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索所有视频或文件夹…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (searchQuery.isNotEmpty()) searchQuery = "" else searchOpen = false
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭搜索")
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(modifier = Modifier.weight(1f).padding(start = if (currentPath.isEmpty()) 8.dp else 0.dp)) {
                    Text(
                        text = if (currentPath.isEmpty()) "视频库" else currentPath.substringAfterLast('/'),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (granted && !loading) {
                        Text(
                            text = if (currentPath.isEmpty()) {
                                "${allVideos.size} 个视频 · ${folders.size} 目录"
                            } else {
                                "${folders.size} 目录 · ${videos.size} 视频"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (granted) {
                    IconButton(onClick = { searchOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索", modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { showSortSheet.value = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.cd_sort), modifier = Modifier.size(22.dp))
                    }
                    IconButton(
                        onClick = {
                            allFilesGranted = VideoLibrary.canReadAllFiles(context)
                            showViewSheet.value = true
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Rounded.GridView, contentDescription = stringResource(R.string.cd_view_settings), modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { refreshTick++ }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_rescan), modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = onUseSystemPicker, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = "系统文件选择器", modifier = Modifier.size(22.dp))
                }
            }
        }

        // ---- 交互式面包屑导航栏 ----
        if (granted && currentPath.isNotEmpty() && trimmedQuery.isEmpty()) {
            BreadcrumbBar(
                currentPath = currentPath,
                onNavigate = { currentPath = it },
            )
        }

        // 后台 .nomedia 扫描细进度条
        AnimatedVisibility(visible = bgScanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }

        SortBottomSheet(
            show = showSortSheet,
            field = sortField,
            asc = sortAsc,
            onField = { sortField = it; Prefs.setBrowserSortField(context, it) },
            onAsc = { sortAsc = it; Prefs.setBrowserSortAsc(context, it) },
        )

        ViewSettingsBottomSheet(
            show = showViewSheet,
            iconSize = iconSize,
            includeNoMedia = includeNoMedia,
            allFilesGranted = allFilesGranted,
            onIconSize = { iconSize = it; Prefs.setBrowserIconSize(context, it) },
            onToggleNoMedia = {
                if (includeNoMedia) {
                    includeNoMedia = false
                    Prefs.setBrowserIncludeNoMedia(context, false)
                } else if (VideoLibrary.canReadAllFiles(context)) {
                    allFilesGranted = true
                    includeNoMedia = true
                    Prefs.setBrowserIncludeNoMedia(context, true)
                } else {
                    openAllFilesSettings(context)
                }
            },
        )

        if (!granted) {
            PermissionPane(
                onRequest = {
                    launcher.launch(
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

        if (isPartialAccess(context)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.partial_access_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { launcher.launch(Manifest.permission.READ_MEDIA_VIDEO) }) {
                        Text(stringResource(R.string.action_pick_more))
                    }
                }
            }
        }

        if (includeNoMedia && !allFilesGranted) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { openAllFilesSettings(context) },
            ) {
                Text(
                    stringResource(R.string.nomedia_need_all_files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            folders.isEmpty() && videos.isEmpty() -> EmptyHint(
                text = when {
                    trimmedQuery.isNotEmpty() -> "未找到匹配「$trimmedQuery」的视频"
                    currentPath.isEmpty() -> stringResource(R.string.empty_no_videos)
                    else -> stringResource(R.string.empty_folder)
                },
                onPickSystem = if (trimmedQuery.isEmpty()) onUseSystemPicker else null,
            )

            else -> MediaGrid(
                state = gridState,
                folders = folders,
                videos = videos,
                iconSize = iconSize,
                showFolderHint = trimmedQuery.isNotEmpty(),
                onOpenFolder = { currentPath = it.path },
                onPlay = { video -> onPlay(video, videos) },
                onQuickCast = { video -> onQuickCastVideo(video, videos) },
            )
        }
    }
}

@Composable
private fun BreadcrumbBar(
    currentPath: String,
    onNavigate: (String) -> Unit,
) {
    val segments = remember(currentPath) { currentPath.split('/').filter { it.isNotEmpty() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onNavigate("") },
        ) {
            Text(
                "内部存储",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        var acc = ""
        segments.forEachIndexed { idx, seg ->
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            val targetPath = acc
            val isLast = idx == segments.lastIndex
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isLast) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isLast) { onNavigate(targetPath) },
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
private fun MediaGrid(
    state: LazyGridState,
    folders: List<FolderEntry>,
    videos: List<VideoItem>,
    iconSize: BrowserIconSize,
    showFolderHint: Boolean,
    onOpenFolder: (FolderEntry) -> Unit,
    onPlay: (VideoItem) -> Unit,
    onQuickCast: (VideoItem) -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(BASE_COLUMNS),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (folders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.browser_folder_count, folders.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            items(
                items = folders,
                key = { "folder:" + it.path },
                span = { GridItemSpan(BASE_COLUMNS / iconSize.folderCols) },
            ) { folder ->
                FolderTile(folder, iconSize, onClick = { onOpenFolder(folder) })
            }
        }
        if (videos.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.browser_video_count, videos.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (folders.isNotEmpty()) 8.dp else 4.dp, bottom = 2.dp),
                )
            }
            items(
                items = videos,
                key = { "video:" + it.key },
                span = { GridItemSpan(BASE_COLUMNS / iconSize.videoCols) },
            ) { video ->
                VideoTile(
                    video = video,
                    iconSize = iconSize,
                    showFolderHint = showFolderHint,
                    onPlay = { onPlay(video) },
                    onQuickCast = { onQuickCast(video) },
                )
            }
        }
    }
}

@Composable
private fun FolderTile(
    folder: FolderEntry,
    iconSize: BrowserIconSize,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val coverUri = folder.coverUri
    val thumb by produceState<Bitmap?>(
        initialValue = coverUri?.let { VideoThumbnails.cached(it) },
        coverUri,
    ) {
        value = if (coverUri != null) VideoThumbnails.load(context, coverUri) else null
    }
    val compact = iconSize.folderCols >= 4

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (compact) 1f else 4f / 3f)
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
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x28000000), Color(0xAA000000)),
                            ),
                        ),
                )
            } else {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(if (compact) 30.dp else 40.dp),
                )
            }

            // 左上角文件夹图标徽章
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        "${folder.totalCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }

            if (folder.subFolderCount > 0 && !compact) {
                Text(
                    "+${folder.subFolderCount} 目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x88000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun VideoTile(
    video: VideoItem,
    iconSize: BrowserIconSize,
    showFolderHint: Boolean,
    onPlay: () -> Unit,
    onQuickCast: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = VideoThumbnails.cached(video.uri), video.uri) {
        value = VideoThumbnails.load(context, video.uri)
    }
    val duration by produceState(initialValue = video.durationMs, video.key) {
        value = if (video.durationMs > 0) video.durationMs else VideoDurations.resolve(video.uri)
    }
    val resumeMs = remember(video.uri) { Prefs.getVideoProgress(context, video.uri.toString()) }
    val resumeFraction = if (duration > 0 && resumeMs > 0) (resumeMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

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
    val showMeta = iconSize.videoCols <= 2

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay),
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
                        modifier = Modifier.size(32.dp),
                    )
                }

                // 顶部轻渐变 + 分辨率角标 + 一键投屏按钮
                if (qualityBadge != null) {
                    Text(
                        text = qualityBadge,
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

                // 右上角一键直投按钮
                if (showMeta) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xA6000000))
                            .clickable(onClick = onQuickCast),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Cast,
                            contentDescription = "直接投屏",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                if (duration > 0) {
                    Text(
                        text = VideoLibrary.formatDuration(duration),
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

                // 断点续播进度条
                if (resumeFraction > 0.01f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(resumeFraction)
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = video.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (showMeta) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showMeta) {
                    val metaText = buildString {
                        val size = VideoLibrary.formatSize(video.sizeBytes)
                        if (size.isNotEmpty()) append(size)
                        if (video.width > 0 && video.height > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${video.width}×${video.height}")
                        }
                        if (showFolderHint && video.folderPath.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(video.folderPath)
                        }
                    }
                    if (metaText.isNotEmpty()) {
                        Text(
                            text = metaText,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    show: MutableState<Boolean>,
    field: VideoSortField,
    asc: Boolean,
    onField: (VideoSortField) -> Unit,
    onAsc: (Boolean) -> Unit,
) {
    if (!show.value) return
    ModalBottomSheet(
        onDismissRequest = { show.value = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("排序方式", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            VideoSortField.entries.forEach { f ->
                val selected = field == f
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onField(f) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "按${f.label}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChip(
                    selected = !asc,
                    onClick = { onAsc(false) },
                    label = { Text("降序（最新 / 最大）") },
                    leadingIcon = { Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = asc,
                    onClick = { onAsc(true) },
                    label = { Text("升序（最早 / A-Z）") },
                    leadingIcon = { Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSettingsBottomSheet(
    show: MutableState<Boolean>,
    iconSize: BrowserIconSize,
    includeNoMedia: Boolean,
    allFilesGranted: Boolean,
    onIconSize: (BrowserIconSize) -> Unit,
    onToggleNoMedia: () -> Unit,
) {
    if (!show.value) return
    ModalBottomSheet(
        onDismissRequest = { show.value = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("显示设置", style = MaterialTheme.typography.titleMedium)
            Text(
                "网格密度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrowserIconSize.entries.forEach { s ->
                    FilterChip(
                        selected = iconSize == s,
                        onClick = { onIconSize(s) },
                        label = { Text(s.label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "当前：文件夹每行 ${iconSize.folderCols} 列 · 视频每行 ${iconSize.videoCols} 列",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleNoMedia() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nomedia_switch_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (includeNoMedia && !allFilesGranted) {
                            stringResource(R.string.nomedia_need_grant)
                        } else {
                            stringResource(R.string.nomedia_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = includeNoMedia, onCheckedChange = { onToggleNoMedia() })
            }
        }
    }
}

@Composable
private fun PermissionPane(onRequest: () -> Unit, onUseSystemPicker: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            stringResource(R.string.permission_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.permission_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(stringResource(R.string.action_grant))
        }
        FilledTonalButton(
            onClick = onUseSystemPicker,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(stringResource(R.string.action_use_system_picker))
        }
    }
}

@Composable
private fun EmptyHint(text: String, onPickSystem: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Rounded.VideoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(52.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onPickSystem != null) {
            FilledTonalButton(onClick = onPickSystem, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("从系统文件管理器选择")
            }
        }
    }
}

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

private fun hasVideoPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (check(context, Manifest.permission.READ_MEDIA_VIDEO)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            check(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    }
    @Suppress("DEPRECATION")
    return check(context, Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun isPartialAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    return !check(context, Manifest.permission.READ_MEDIA_VIDEO) &&
        check(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
}

private fun check(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
