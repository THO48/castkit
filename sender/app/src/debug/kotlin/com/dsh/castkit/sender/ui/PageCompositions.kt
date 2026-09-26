package com.dsh.castkit.sender.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.ui.components.CastKitCard
import com.dsh.castkit.sender.ui.components.CastKitTopBar
import com.dsh.castkit.sender.ui.components.CastStatusRow
import com.dsh.castkit.sender.ui.components.EmptyState
import com.dsh.castkit.sender.ui.components.FolderTile
import com.dsh.castkit.sender.ui.components.OptionChipRow
import com.dsh.castkit.sender.ui.components.PrimaryBottomButton
import com.dsh.castkit.sender.ui.components.SectionHeader
import com.dsh.castkit.sender.ui.components.SectionHeaderLevel
import com.dsh.castkit.sender.ui.components.SettingRow
import com.dsh.castkit.sender.ui.components.VideoTile
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import com.dsh.castkit.sender.ui.theme.ImmersiveColors
import androidx.compose.material.icons.filled.VideoLibrary

/*
 * ============================================================================
 * 页面组合预览（debug 源集，Q17=C 里的"跨状态 / 跨尺寸组合预览"）
 * ============================================================================
 *
 * ## 这个文件是什么、不是什么
 *
 * **是**：把三个页面的结构，用**真实的公共组件**按**与页面相同的顺序和留白**搭出来，
 * 用于审阅"页面级"的观感（区块节奏、组件配比、深浅色、窄屏/平板）。
 *
 * **不是**：不是那两个 Tab 页面本身。`CastSettingsScreen` 与 `FileListScreen` 需要
 * ViewModel、`LanCastDiscovery`、系统权限（`READ_MEDIA_VIDEO`）与真实设备发现结果，
 * 这些在 layoutlib 预览里都拿不到。所以这里传的是代表性假数据。
 *
 * 它跟被删掉的旧 `UiPreview.kt` 有本质区别：旧文件用的是**一堆手写骨架**
 * （`PlayerSkeleton` 之类），和真屏幕完全脱节，最后连"三个大色块按钮"这种
 * 早已不存在的布局都还在预览里画着，把人误导了。这里**一个自绘骨架都没有**，
 * 全部是生产组件。
 *
 * 播放器那三个内部 composable（`PlayerTopBar` / `PlayerBottomControls` /
 * `CastingImmersivePanel`）是**直接调用生产代码**，不存在漂移问题——
 * 因为播放页本来就只依赖普通值，不需要 ViewModel。
 *
 * 预览一律 `dynamicColor = false`：动态取色需要真实设备的壁纸种子，
 * 预览里拿不到；而且这里要审的就是品牌 fallback 色板本身。
 */

// ---------------------------------------------------------------------------
// 页面 2 结构预览
// ---------------------------------------------------------------------------

@Composable
private fun CastPageComposition() {
    Surface(color = CastKitTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            CastKitTopBar(title = "投屏")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionHeader(text = "接收端", level = SectionHeaderLevel.Page)
                CastKitCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CastKitSpacing.pageHorizontal),
                ) {
                    CastStatusRow(
                        phase = CastPhase.RUNNING,
                        label = "投屏中",
                        detail = "192.168.1.23:8123",
                        stats = "1920×1080 @30fps 8Mbps · 实测 7.9 Mbps · 30 fps",
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = CastKitSpacing.cardInside),
                        color = CastKitTheme.colorScheme.outlineVariant,
                    )
                    SettingRow(
                        title = "客厅电视",
                        summary = "192.168.1.23",
                        trailing = {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = CastKitTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {},
                    )
                    SettingRow(title = "卧室平板", summary = "192.168.1.31", onClick = {})
                    SettingRow(title = "重新搜索", onClick = {})
                    SettingRow(
                        title = "手动输入地址",
                        trailing = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = CastKitTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {},
                    )
                }

                SectionHeader(text = "画面参数", level = SectionHeaderLevel.Page)
                CastKitCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CastKitSpacing.pageHorizontal),
                ) {
                    Column(modifier = Modifier.padding(vertical = CastKitSpacing.space4)) {
                        PageFieldLabel("分辨率")
                        OptionChipRow(
                            options = listOf("720p", "1080p", "1440p", "跟随本机"),
                            selected = "1080p",
                            onSelect = {},
                            label = { it },
                        )
                        Spacer(Modifier.height(CastKitSpacing.space2))
                        Text(
                            text = "实际投出：1920×1080",
                            style = CastKitTheme.typography.bodySmall,
                            color = CastKitTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = CastKitSpacing.pageHorizontal),
                        )

                        Spacer(Modifier.height(CastKitSpacing.space4))
                        SettingRow(
                            title = "保持屏幕比例",
                            summary = "开启：档位数字表示短边，长边按本机屏幕比例推导；关闭：按标准 16:9 投出",
                            trailing = { Switch(checked = true, onCheckedChange = {}) },
                        )

                        Spacer(Modifier.height(CastKitSpacing.space4))
                        PageFieldLabel("帧率")
                        OptionChipRow(
                            options = listOf("15fps", "24fps", "30fps", "60fps"),
                            selected = "30fps",
                            onSelect = {},
                            label = { it },
                        )

                        Spacer(Modifier.height(CastKitSpacing.space4))
                        SettingRow(title = "码率", summary = "8 Mbps")
                        Slider(
                            value = 8f,
                            onValueChange = {},
                            valueRange = 1f..20f,
                            steps = 18,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CastKitSpacing.pageHorizontal),
                        )
                    }
                }
                Spacer(Modifier.height(CastKitSpacing.space6))
            }
            PrimaryBottomButton(text = "开始投屏", onClick = {})
        }
    }
}

@Composable
private fun PageFieldLabel(text: String) {
    Text(
        text = text,
        style = CastKitTheme.typography.labelLarge,
        color = CastKitTheme.colorScheme.onSurface,
        modifier = Modifier.padding(
            start = CastKitSpacing.pageHorizontal,
            end = CastKitSpacing.pageHorizontal,
            bottom = CastKitSpacing.space2,
        ),
    )
}

// ---------------------------------------------------------------------------
// 页面 1 结构预览
// ---------------------------------------------------------------------------

@Composable
private fun FileListPageComposition(empty: Boolean = false) {
    Surface(color = CastKitTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            CastKitTopBar(title = "本机视频")
            if (empty) {
                EmptyState(
                    icon = Icons.Filled.VideoLibrary,
                    title = "暂无视频",
                    description = "没有扫描到视频。确认本机有视频文件，或点右上角重新扫描。",
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    SectionHeader(text = "文件夹 · 2", level = SectionHeaderLevel.Section)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CastKitSpacing.pageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(CastKitSizes.gridGap),
                    ) {
                        FolderTile(
                            name = "Movies",
                            countText = "2 个视频",
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        FolderTile(
                            name = "下载",
                            countText = "5 个视频 · 1 个子文件夹",
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        FolderTile(
                            name = "Telegram",
                            countText = "读屏录像",
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                    }

                    SectionHeader(text = "视频 · 2", level = SectionHeaderLevel.Section)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CastKitSpacing.pageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(CastKitSizes.gridGap),
                    ) {
                        VideoTile(
                            title = "sample1.mp4",
                            meta = "00:12 · 1280×720 · 33 KB",
                            durationText = "00:12",
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        VideoTile(
                            title = "一个相当长的视频文件名用于验证单行截断.mp4",
                            meta = "01:02:03 · 3840×2160 · 2.41 GB",
                            durationText = "1:02:03",
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(CastKitSpacing.space7))
                }
            }
            BottomNavComposition()
        }
    }
}

@Composable
private fun BottomNavComposition() {
    NavigationBar(containerColor = CastKitTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = {
                Icon(
                    imageVector = Icons.Filled.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = { Text("投屏", style = CastKitTheme.typography.labelMedium) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = CastKitTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = CastKitTheme.colorScheme.primary,
                indicatorColor = CastKitTheme.colorScheme.primaryContainer,
                unselectedIconColor = CastKitTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = CastKitTheme.colorScheme.onSurfaceVariant,
            ),
        )
        NavigationBarItem(
            selected = true,
            onClick = {},
            icon = {
                Icon(
                    imageVector = Icons.Filled.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = { Text("视频", style = CastKitTheme.typography.labelMedium) },
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

// ---------------------------------------------------------------------------
// 预览
// ---------------------------------------------------------------------------

@Preview(name = "投屏页 · 浅色", showBackground = true, widthDp = 411, heightDp = 1000)
@Composable
private fun CastPageLight() {
    CastKitTheme(darkTheme = false, dynamicColor = false) { CastPageComposition() }
}

@Preview(
    name = "投屏页 · 深色",
    showBackground = true,
    widthDp = 411,
    heightDp = 1000,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CastPageDark() {
    CastKitTheme(darkTheme = true, dynamicColor = false) { CastPageComposition() }
}

@Preview(name = "投屏页 · 360dp 窄屏（Chip 溢出）", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
private fun CastPageNarrow() {
    CastKitTheme(darkTheme = false, dynamicColor = false) { CastPageComposition() }
}

@Preview(name = "投屏页 · 平板", showBackground = true, widthDp = 800, heightDp = 1100)
@Composable
private fun CastPageTablet() {
    CastKitTheme(darkTheme = false, dynamicColor = false) { CastPageComposition() }
}

@Preview(name = "视频页 · 浅色", showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun FileListLight() {
    CastKitTheme(darkTheme = false, dynamicColor = false) { FileListPageComposition() }
}

@Preview(
    name = "视频页 · 深色",
    showBackground = true,
    widthDp = 411,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun FileListDark() {
    CastKitTheme(darkTheme = true, dynamicColor = false) { FileListPageComposition() }
}

@Preview(name = "视频页 · 空态 · 深色", showBackground = true, widthDp = 411, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FileListEmptyDark() {
    CastKitTheme(darkTheme = true, dynamicColor = false) { FileListPageComposition(empty = true) }
}

// ---------------------------------------------------------------------------
// 播放器：直接预览生产 composable，不存在漂移
// ---------------------------------------------------------------------------

@Composable
private fun PlayerComposition(casting: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ImmersiveColors.Background),
    ) {
        if (casting) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                CastingImmersivePanel(receiverName = "客厅电视")
            }
        }
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            PlayerTopBar(title = "青海湖环湖骑行 4K.mp4", onBack = {})
            // 悬浮钮在真机上是"顶栏下方左对齐 / 底栏上方左对齐"，
            // 画廊里也要摆成一样，否则截图对比失去意义。
            PlayerFloatingAction(
                icon = Icons.Filled.ScreenRotation,
                label = "切到横屏",
                text = "横屏",
                onClick = {},
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
        }
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            PlayerFloatingAction(
                icon = if (casting) Icons.Filled.Stop else Icons.Filled.Cast,
                label = if (casting) "结束投送" else "投屏",
                text = if (casting) "停止" else null,
                active = casting,
                onClick = {},
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            PlayerBottomControls(
                positionMs = 252_000L,
                durationMs = 754_000L,
                playing = !casting,
                hasPrev = true,
                hasNext = false,
                onScrub = {},
                onScrubFinished = {},
                onPrev = {},
                onSeekBack = {},
                onPlayPause = {},
                onSeekForward = {},
                onNext = {},
            )
        }
    }
}

@Preview(name = "播放器 · 本机播放", widthDp = 411, heightDp = 900, backgroundColor = 0xFF000000)
@Composable
private fun PlayerLocalPreview() {
    CastKitTheme(darkTheme = true, dynamicColor = false) { PlayerComposition(casting = false) }
}

@Preview(name = "播放器 · 投送中（沉浸态）", widthDp = 411, heightDp = 900, backgroundColor = 0xFF000000)
@Composable
private fun PlayerCastingPreview() {
    CastKitTheme(darkTheme = true, dynamicColor = false) { PlayerComposition(casting = true) }
}
