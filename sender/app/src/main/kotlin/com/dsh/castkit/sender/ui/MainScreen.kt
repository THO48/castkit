package com.dsh.castkit.sender.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.CastConfig
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.ResolutionPreset
import com.dsh.castkit.sender.SavedReceiver
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.CastMode
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.cast.CastState
import com.dsh.castkit.sender.media.VideoItem
import com.dsh.castkit.sender.media.VideoLibrary
import com.dsh.castkit.sender.media.VideoThumbnails
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.net.LanCastFileServer
import kotlinx.coroutines.delay

private enum class HomeTab(val label: String, val icon: ImageVector) {
    CAST("投屏", Icons.Rounded.Cast),
    VIDEO("视频库", Icons.Rounded.VideoLibrary),
}

private data class ActivePlayerSession(
    val uri: Uri,
    val title: String,
    val playlist: List<VideoItem>,
    val openPickerOnStart: Boolean = false,
)

@Composable
fun MainScreen(
    initial: CastConfig,
    pickedVideo: Uri?,
    onPickVideo: () -> Unit,
    onPickVideoUri: (Uri) -> Unit,
    onStartVideo: (Uri) -> Unit,
    onShareLibrary: () -> Unit,
    onQuickCast: (Uri, String, Int, String, Long) -> Unit,
    onRemoteToggle: () -> Unit,
    onRemoteSeek: (Long) -> Unit,
    onRemoteSeekBy: (Long) -> Unit,
    onRemoteSpeed: (Float) -> Unit,
    onRemoteVolumeDelta: (Boolean) -> Unit,
    onStopVideo: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestKeyFrame: () -> Unit,
    onUpdateBitrate: (Int) -> Unit,
    onRequestAudioPermission: () -> Unit,
    discovery: LanCastDiscovery,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(HomeTab.CAST) }
    var playerSession by remember { mutableStateOf<ActivePlayerSession?>(null) }
    val castState by CastBus.state.collectAsState()

    val showMiniRemote = playerSession == null &&
        castState.mode == CastMode.VIDEO &&
        castState.isActive &&
        (castState.videoUri.isNotBlank() || castState.remoteBrowsing)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    // 后台投视频时的全局迷你遥控条（点击回到完整遥控台）
                    AnimatedVisibility(
                        visible = showMiniRemote,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        MiniCastRemoteBar(
                            state = castState,
                            onOpenConsole = {
                                if (castState.videoUri.isNotBlank()) {
                                    val u = Uri.parse(castState.videoUri)
                                    playerSession = ActivePlayerSession(
                                        uri = u,
                                        title = castState.videoTitle,
                                        playlist = emptyList(),
                                    )
                                } else {
                                    tab = HomeTab.VIDEO
                                }
                            },
                            onToggle = onRemoteToggle,
                            onStop = onStopVideo,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        HomeTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                when (tab) {
                    HomeTab.CAST -> CastTab(
                        initial = initial,
                        pickedVideo = pickedVideo,
                        onPickVideo = onPickVideo,
                        onGoToLibrary = { tab = HomeTab.VIDEO },
                        onOpenPickedInPlayer = { uri ->
                            val title = runCatching { LanCastFileServer.describe(context, uri).name }.getOrDefault("")
                            playerSession = ActivePlayerSession(uri, title, emptyList())
                        },
                        onStartVideo = onStartVideo,
                        onShareLibrary = onShareLibrary,
                        onStopVideo = onStopVideo,
                        onStart = onStart,
                        onStop = onStop,
                        onRequestKeyFrame = onRequestKeyFrame,
                        onUpdateBitrate = onUpdateBitrate,
                        onRequestAudioPermission = onRequestAudioPermission,
                        discovery = discovery,
                    )

                    HomeTab.VIDEO -> VideoBrowser(
                        onPlay = { video, list ->
                            onPickVideoUri(video.uri)
                            playerSession = ActivePlayerSession(video.uri, video.name, list, openPickerOnStart = false)
                        },
                        onQuickCastVideo = { video, list ->
                            onPickVideoUri(video.uri)
                            val online = discovery.receivers.value
                            if (online.size == 1) {
                                // 只有一台在线接收端：一键直投，无需再弹窗
                                val target = online.first()
                                val resumeMs = Prefs.getVideoProgress(context, video.uri.toString())
                                onQuickCast(video.uri, target.host, target.port, target.name, resumeMs)
                                playerSession = ActivePlayerSession(video.uri, video.name, list, openPickerOnStart = false)
                            } else {
                                playerSession = ActivePlayerSession(video.uri, video.name, list, openPickerOnStart = true)
                            }
                        },
                        onUseSystemPicker = onPickVideo,
                    )
                }
            }
        }

        // 播放器与投屏遥控台覆盖层（保留底层视频库滚动位置与目录树）
        val session = playerSession
        if (session != null) {
            LocalPlayerScreen(
                uri = session.uri,
                title = session.title,
                playlist = session.playlist,
                openPickerOnStart = session.openPickerOnStart,
                discovery = discovery,
                onSelectVideo = { newUri, newTitle ->
                    onPickVideoUri(newUri)
                    playerSession = session.copy(uri = newUri, title = newTitle, openPickerOnStart = false)
                },
                onCastTo = { castUri, host, port, name, posMs ->
                    onQuickCast(castUri, host, port, name, posMs)
                },
                onStopCast = onStopVideo,
                onRemoteToggle = onRemoteToggle,
                onRemoteSeek = onRemoteSeek,
                onRemoteSeekBy = onRemoteSeekBy,
                onRemoteSpeed = onRemoteSpeed,
                onRemoteVolumeDelta = onRemoteVolumeDelta,
                onMinimize = { playerSession = null },
                onClose = {
                    val st = CastBus.state.value
                    if (st.mode == CastMode.VIDEO && st.isActive) onStopVideo()
                    playerSession = null
                },
            )
        }
    }
}

@Composable
private fun MiniCastRemoteBar(
    state: CastState,
    onOpenConsole: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenConsole),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusPulseDot(color = liveGreenColor(), pulsing = true, size = 7.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.remoteBrowsing) {
                        "接收端正在浏览本机视频库…"
                    } else {
                        state.videoTitle.ifBlank { "正在投送视频" }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (state.remoteBrowsing) {
                        "${state.targetName.ifBlank { state.host }} 可随时自由选片播放"
                    } else {
                        "投送至 ${state.targetName.ifBlank { state.host }} · 点击展开遥控器"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!state.remoteBrowsing) {
                IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (state.remotePlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.remotePlaying) "暂停" else "播放",
                    )
                }
            }
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "停止投送")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CastTab(
    initial: CastConfig,
    pickedVideo: Uri?,
    onPickVideo: () -> Unit,
    onGoToLibrary: () -> Unit,
    onOpenPickedInPlayer: (Uri) -> Unit,
    onStartVideo: (Uri) -> Unit,
    onShareLibrary: () -> Unit,
    onStopVideo: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestKeyFrame: () -> Unit,
    onUpdateBitrate: (Int) -> Unit,
    onRequestAudioPermission: () -> Unit,
    discovery: LanCastDiscovery,
) {
    val context = LocalContext.current
    val state by CastBus.state.collectAsState()
    val found by discovery.receivers.collectAsState()

    var preset by remember { mutableStateOf(Prefs.preset(context)) }
    var customWText by remember { mutableStateOf(Prefs.customWidth(context).toString()) }
    var customHText by remember { mutableStateOf(Prefs.customHeight(context).toString()) }
    var fps by remember { mutableIntStateOf(if (initial.fps > 0) initial.fps else Prefs.DEF_FPS) }
    var mbps by remember { mutableIntStateOf(Prefs.mbps(context)) }
    var host by remember { mutableStateOf(initial.host) }
    var portText by remember { mutableStateOf((if (initial.port > 0) initial.port else Prefs.DEF_PORT).toString()) }
    var targetName by remember { mutableStateOf(Prefs.targetName(context)) }
    var keepAspect by remember { mutableStateOf(Prefs.keepAspect(context)) }
    var mirrorAudio by remember { mutableStateOf(Prefs.mirrorAudio(context)) }
    var mode by remember { mutableStateOf(CastMode.MIRROR) }
    var showManual by remember { mutableStateOf(initial.host.isBlank() && !Prefs.autoDiscovered(context)) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var recentList by remember { mutableStateOf(Prefs.recentReceivers(context)) }

    fun persist() {
        val cw = customWText.toIntOrNull()?.coerceIn(128, 4096) ?: Prefs.DEF_CUSTOM_SHORT_SIDE
        val ch = customHText.toIntOrNull()?.coerceIn(128, 4096) ?: 720
        Prefs.persistConfig(
            context = context,
            preset = preset,
            customW = cw,
            customH = ch,
            fps = fps,
            mbps = mbps,
            host = host.trim(),
            port = portText.toIntOrNull() ?: Prefs.DEF_PORT,
            keepAspect = keepAspect,
            mirrorAudio = mirrorAudio,
            targetName = targetName,
        )
    }

    val running = state.isActive
    val effectiveMode = if (running) state.mode else mode

    // 自动选中发现的第一台设备
    LaunchedEffect(found) {
        if (selectedId == null || found.none { it.id == selectedId }) {
            found.firstOrNull()?.let { r ->
                selectedId = r.id
                host = r.host
                portText = r.port.toString()
                targetName = r.name
                Prefs.markAutoDiscovered(context, true)
                persist()
            }
        }
        recentList = Prefs.recentReceivers(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- 顶部品牌栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, CastColors.CyanAccent),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Cast,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column {
                    Text("CastKit", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (found.isNotEmpty()) "已发现 ${found.size} 台局域网接收端" else "局域网高速投屏与视频直播",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusPill(
                text = when (state.phase) {
                    CastPhase.RUNNING -> "投屏中"
                    CastPhase.CONNECTING -> "连接中"
                    CastPhase.RECONNECTING -> "重连中"
                    CastPhase.ERROR -> "异常"
                    CastPhase.IDLE -> if (found.isNotEmpty()) "就绪" else "待机"
                },
                color = when (state.phase) {
                    CastPhase.RUNNING -> liveGreenColor()
                    CastPhase.CONNECTING, CastPhase.RECONNECTING -> warnAmberColor()
                    CastPhase.ERROR -> MaterialTheme.colorScheme.error
                    CastPhase.IDLE -> if (found.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                },
                leadingDot = true,
            )
        }

        // ---- 主滚动内容区 ----
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1) 状态与实时遥测仪表盘
            HeroSessionCard(
                state = state,
                targetName = targetName,
                host = host,
                portText = portText,
                onRequestKeyFrame = onRequestKeyFrame,
            )

            // 2) 模式切换（分段胶囊）
            ModeSegmentedControl(
                selected = effectiveMode,
                enabled = !running,
                onSelect = { mode = it },
            )

            // 3) 接收端设备卡片
            SectionHeaderRow(
                title = "目标接收端",
                trailing = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { discovery.probeNow() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            "刷新扫描",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )

            CastCard {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (found.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatusPulseDot(color = MaterialTheme.colorScheme.primary, pulsing = true)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("正在搜索局域网接收端…", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "请确认平板已打开 CastKit 接收端并处于同一 Wi-Fi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        found.forEach { r ->
                            val isSelected = selectedId == r.id || (host == r.host && portText == r.port.toString())
                            val bg by animateColorAsState(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                label = "devBg",
                            )
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = bg,
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = !running) {
                                        selectedId = r.id
                                        host = r.host
                                        portText = r.port.toString()
                                        targetName = r.name
                                        Prefs.markAutoDiscovered(context, true)
                                        persist()
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(liveGreenColor().copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Rounded.TabletAndroid,
                                            contentDescription = null,
                                            tint = liveGreenColor(),
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                                            StatusPill(text = "在线", color = liveGreenColor())
                                        }
                                        Text(
                                            "${r.host}:${r.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = "已选中",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 最近连接历史（排除已在上面展示的在线设备）
                    val offlineRecent = remember(recentList, found) {
                        val ids = found.mapTo(HashSet()) { it.id }
                        recentList.filter { it.id !in ids }
                    }
                    if (offlineRecent.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                "最近连接",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            offlineRecent.forEach { saved: SavedReceiver ->
                                val isSelected = host == saved.host && portText == saved.port.toString()
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (!running) {
                                            selectedId = saved.id
                                            host = saved.host
                                            portText = saved.port.toString()
                                            targetName = saved.name
                                            persist()
                                        }
                                    },
                                    label = { Text("${saved.name} (${saved.host})") },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = "移除记录",
                                            modifier = Modifier
                                                .size(15.dp)
                                                .clickable {
                                                    Prefs.removeRecentReceiver(context, saved.id)
                                                    recentList = Prefs.recentReceivers(context)
                                                },
                                        )
                                    },
                                )
                            }
                        }
                    }

                    // 手动输入地址折叠行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showManual = !showManual }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Rounded.Lan,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                if (showManual) "收起手动输入地址" else "手动输入 IP 地址（AP 隔离兜底）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (showManual) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(visible = showManual) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = {
                                    host = it
                                    targetName = it.trim()
                                    selectedId = null
                                    Prefs.markAutoDiscovered(context, false)
                                    persist()
                                },
                                label = { Text("接收端 IP") },
                                singleLine = true,
                                enabled = !running,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { v ->
                                    portText = v.filter(Char::isDigit).take(5)
                                    Prefs.markAutoDiscovered(context, false)
                                    persist()
                                },
                                label = { Text("端口") },
                                singleLine = true,
                                enabled = !running,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 4) 按模式展示内容：投视频文件 vs 屏幕镜像参数
            if (effectiveMode == CastMode.VIDEO) {
                SectionHeaderRow(title = "视频源（原画质直播 & 平板自由选片）")
                VideoSourceCard(
                    pickedVideo = pickedVideo,
                    running = running,
                    canShareLibrary = host.isNotBlank(),
                    onGoToLibrary = onGoToLibrary,
                    onPickSystem = onPickVideo,
                    onShareLibrary = onShareLibrary,
                    onPreviewPlay = { pickedVideo?.let(onOpenPickedInPlayer) },
                )
            } else {
                // ---- 快捷画质预设 ----
                SectionHeaderRow(title = "画质与声音")
                CastCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // 快捷档位一行：省流 / 均衡 / 高清 / 原画
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val quickProfiles = listOf(
                                Triple("流畅", ResolutionPreset.P720, Pair(30, 5)),
                                Triple("高清", ResolutionPreset.P1080, Pair(60, 10)),
                                Triple("超清", ResolutionPreset.P1440, Pair(60, 16)),
                                Triple("原画", ResolutionPreset.NATIVE, Pair(60, 22)),
                            )
                            quickProfiles.forEach { (label, qPreset, fpsMbps) ->
                                val active = preset == qPreset && fps == fpsMbps.first && mbps == fpsMbps.second
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !running) {
                                            preset = qPreset
                                            fps = fpsMbps.first
                                            mbps = fpsMbps.second
                                            persist()
                                        },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            "${fpsMbps.second}M",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 分辨率选择
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Text("分辨率", style = MaterialTheme.typography.titleSmall)
                                }
                                val effective = remember(preset, customWText, customHText, keepAspect) { Prefs.config(context) }
                                StatusPill(
                                    text = "${effective.width}×${effective.height}",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                ResolutionPreset.entries.forEach { p ->
                                    FilterChip(
                                        selected = preset == p,
                                        enabled = !running,
                                        onClick = {
                                            preset = p
                                            persist()
                                        },
                                        label = { Text(p.label) },
                                    )
                                }
                            }
                            if (preset == ResolutionPreset.CUSTOM) {
                                if (keepAspect) {
                                    OutlinedTextField(
                                        value = customWText,
                                        onValueChange = { v ->
                                            customWText = v.filter(Char::isDigit).take(4)
                                            persist()
                                        },
                                        label = { Text("自定义短边像素（如 900）") },
                                        singleLine = true,
                                        enabled = !running,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = customWText,
                                            onValueChange = { v ->
                                                customWText = v.filter(Char::isDigit).take(4)
                                                persist()
                                            },
                                            label = { Text("宽") },
                                            singleLine = true,
                                            enabled = !running,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.weight(1f),
                                        )
                                        OutlinedTextField(
                                            value = customHText,
                                            onValueChange = { v ->
                                                customHText = v.filter(Char::isDigit).take(4)
                                                persist()
                                            },
                                            label = { Text("高") },
                                            singleLine = true,
                                            enabled = !running,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                            val effective = remember(preset, customWText, customHText, keepAspect) { Prefs.config(context) }
                            effective.sizeNote?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = warnAmberColor(),
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 帧率
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text("目标帧率", style = MaterialTheme.typography.titleSmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(15, 24, 30, 60).forEach { f ->
                                    FilterChip(
                                        selected = fps == f,
                                        enabled = !running,
                                        onClick = {
                                            fps = f
                                            persist()
                                        },
                                        label = { Text("${f}fps") },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 码率（支持投屏中途实时动态调节！）
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Text(
                                        if (running && state.mode == CastMode.MIRROR) "实时码率调节" else "编码码率",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                Text(
                                    "$mbps Mbps",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Slider(
                                value = mbps.toFloat(),
                                onValueChange = { mbps = it.toInt().coerceIn(1, Prefs.MAX_BITRATE_MBPS) },
                                onValueChangeFinished = {
                                    persist()
                                    if (running && state.mode == CastMode.MIRROR) {
                                        onUpdateBitrate(mbps)
                                    }
                                },
                                valueRange = 1f..Prefs.MAX_BITRATE_MBPS.toFloat(),
                                steps = Prefs.MAX_BITRATE_MBPS - 2,
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 同步投送系统声音（Android 10+ AudioPlaybackCapture）
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !running) {
                                        val next = !mirrorAudio
                                        mirrorAudio = next
                                        persist()
                                        if (next) onRequestAudioPermission()
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.VolumeUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("同步投送系统声音", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "同时采集媒体与游戏音频发送到接收端（需音频权限）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = mirrorAudio,
                                    enabled = !running,
                                    onCheckedChange = { next ->
                                        mirrorAudio = next
                                        persist()
                                        if (next) onRequestAudioPermission()
                                    },
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }

                        // 保持屏幕比例
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !running) {
                                    keepAspect = !keepAspect
                                    persist()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.AspectRatio,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("保持本机屏幕比例", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "按屏幕长宽比自动推算分辨率，防止画面被拉伸变形",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = keepAspect,
                                enabled = !running,
                                onCheckedChange = {
                                    keepAspect = it
                                    persist()
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        // ---- 吸底主操作按钮栏（无需滚动即可随时启停） ----
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    when {
                        running && state.mode == CastMode.VIDEO -> {
                            Button(
                                onClick = onStopVideo,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("停止投送视频", style = MaterialTheme.typography.titleSmall)
                            }
                        }

                        running -> {
                            Button(
                                onClick = onStop,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Rounded.StopScreenShare, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("停止屏幕镜像", style = MaterialTheme.typography.titleSmall)
                            }
                        }

                        effectiveMode == CastMode.VIDEO -> {
                            Button(
                                onClick = { pickedVideo?.let(onStartVideo) },
                                enabled = pickedVideo != null && host.isNotBlank(),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Rounded.Cast, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (pickedVideo == null) "请先选择视频文件" else "开始原画质投送",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }

                        else -> {
                            Button(
                                onClick = onStart,
                                enabled = host.isNotBlank(),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Rounded.ScreenShare, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (host.isBlank()) "请先选择接收端" else "开始镜像投屏",
                                    style = MaterialTheme.typography.titleSmall,
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
private fun HeroSessionCard(
    state: CastState,
    targetName: String,
    host: String,
    portText: String,
    onRequestKeyFrame: () -> Unit,
) {
    val isLive = state.phase == CastPhase.RUNNING
    val isBusy = state.phase == CastPhase.CONNECTING || state.phase == CastPhase.RECONNECTING
    val isError = state.phase == CastPhase.ERROR

    // 实时会话计时器
    var elapsedSec by remember(state.startedAtMs, isLive) { mutableLongStateOf(0L) }
    LaunchedEffect(state.startedAtMs, isLive) {
        if (!isLive || state.startedAtMs <= 0L) {
            elapsedSec = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - state.startedAtMs) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }

    val accent = when {
        isLive -> liveGreenColor()
        isBusy -> warnAmberColor()
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    CastCard(
        borderColor = if (isLive || isError) accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                    ),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusPulseDot(color = accent, pulsing = isLive || isBusy, size = 10.dp)
                    Column {
                        Text(
                            text = when (state.phase) {
                                CastPhase.RUNNING -> if (state.mode == CastMode.VIDEO) "正在投送原画视频" else "屏幕镜像传输中"
                                CastPhase.CONNECTING -> "正在握手连接…"
                                CastPhase.RECONNECTING -> "连接中断，正在重连…"
                                CastPhase.ERROR -> "投屏异常"
                                CastPhase.IDLE -> if (host.isNotBlank()) "已就绪 · ${targetName.ifBlank { host }}" else "等待选择接收端"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val subtitle = when {
                            isError && state.message.isNotBlank() -> state.message
                            isLive && state.mode == CastMode.VIDEO -> state.videoTitle.ifBlank { state.message }
                            state.host.isNotBlank() && state.isActive -> "${state.targetName.ifBlank { "接收端" }} · ${state.host}:${state.port}"
                            host.isNotBlank() -> "$host:$portText"
                            else -> "同一 Wi-Fi 下自动发现接收端，零配置开投"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isLive && elapsedSec > 0) {
                    StatusPill(text = formatElapsed(elapsedSec), color = accent)
                }
            }

            // 镜像运行时的实时遥测数据与快捷刷新 I 帧
            if (isLive && state.mode == CastMode.MIRROR) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricTile(
                        label = "分辨率",
                        value = "${state.width}×${state.height}",
                        modifier = Modifier.weight(1.2f),
                    )
                    MetricTile(
                        label = "实测码率",
                        value = "%.1f Mbps".format(state.measuredKbps / 1000f),
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "实测帧率",
                        value = "${state.measuredFps.toInt()} fps",
                        modifier = Modifier.weight(0.9f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatusPill(
                        text = if (state.audioActive) "声音已同步" else "仅画面",
                        color = if (state.audioActive) liveGreenColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onRequestKeyFrame,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新画面", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ModeSegmentedControl(
    selected: CastMode,
    enabled: Boolean,
    onSelect: (CastMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                Triple(CastMode.MIRROR, "镜像屏幕", Icons.Rounded.ScreenShare),
                Triple(CastMode.VIDEO, "投视频文件", Icons.Rounded.Movie),
            ).forEach { (m, label, icon) ->
                val isSelected = selected == m
                val bg by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    label = "segBg",
                )
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = bg,
                    shadowElevation = if (isSelected) 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(enabled = enabled) { onSelect(m) },
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSourceCard(
    pickedVideo: Uri?,
    running: Boolean,
    canShareLibrary: Boolean,
    onGoToLibrary: () -> Unit,
    onPickSystem: () -> Unit,
    onShareLibrary: () -> Unit,
    onPreviewPlay: () -> Unit,
) {
    val context = LocalContext.current
    val picked = remember(pickedVideo) {
        pickedVideo?.let { runCatching { LanCastFileServer.describe(context, it) }.getOrNull() }
    }
    val thumb by produceState<Bitmap?>(
        initialValue = pickedVideo?.let { VideoThumbnails.cached(it) },
        pickedVideo,
    ) {
        value = if (pickedVideo != null) VideoThumbnails.load(context, pickedVideo) else null
    }

    CastCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (picked != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .clickable(onClick = onPreviewPlay)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(88.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF151922)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = thumb
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = picked.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            picked.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (picked.size > 0) VideoLibrary.formatSize(picked.size) + " · 点击可本地预览/遥控" else "点击打开播放器",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    "将本地原始视频文件通过局域网 HTTP 直接推给接收端原生硬解播放：零转码损耗、原画质、有声音、无需录屏授权。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onGoToLibrary,
                    enabled = !running,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("从视频库挑选")
                }
                OutlinedButton(
                    onClick = onPickSystem,
                    enabled = !running,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("系统文件选择")
                }
            }

            FilledTonalButton(
                onClick = onShareLibrary,
                enabled = !running && canShareLibrary,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("直接共享本机视频库给接收端选片")
            }
        }
    }
}

private fun formatElapsed(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
