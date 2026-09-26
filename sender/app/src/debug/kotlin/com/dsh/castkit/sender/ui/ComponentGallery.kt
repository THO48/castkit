package com.dsh.castkit.sender.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.cast.CastPhase
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

/*
 * ============================================================================
 * 公共组件画廊（debug 源集）
 * ============================================================================
 *
 * 按 Q17=C 的约定：**跨状态 / 跨尺寸的组合预览集中在 debug 源集**，
 * 各页面自己的主态预览放在页面文件里就地写。
 *
 * 预览一律 `dynamicColor = false`：动态取色需要真实设备的壁纸种子，
 * 在预览里既拿不到也不稳定；而且我们要审的就是 fallback 色板本身。
 *
 * 注意本文件与旧的 `UiPreview.kt` 并存：后者预览的是尚未迁移的三个 Miuix 页面，
 * 会在第三步随页面一起重写。
 */

/**
 * 画廊内容。`internal` 而非 `private`，因为 debug 源集里的 [GalleryActivity]
 * 需要复用它——这样"预览里看到的"和"真机上看到的"是同一份代码，
 * 不会出现预览与实机两套骨架各自漂移（旧的 `UiPreview.kt` 就是栽在这上面）。
 */
@Composable
internal fun GalleryBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader(text = "接收端", level = SectionHeaderLevel.Page)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CastKitSpacing.pageHorizontal),
            shape = CastKitTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = CastKitTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            CastStatusRow(
                phase = CastPhase.RUNNING,
                label = "投屏中",
                detail = "192.168.1.23:8123",
                stats = "1920×1080 @30fps 8Mbps · 实测 7.9Mbps",
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
            SettingRow(
                title = "卧室平板",
                summary = "192.168.1.31",
                onClick = {},
            )
            SettingRow(
                title = "手动输入地址",
                onClick = {},
            )
            SettingRow(
                title = "已断开",
                summary = "接收端未响应",
                onClick = null,
            )
        }

        SectionHeader(text = "画面参数", level = SectionHeaderLevel.Page)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CastKitSpacing.pageHorizontal),
            shape = CastKitTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = CastKitTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = CastKitSpacing.space4)) {
                Text(
                    text = "分辨率",
                    style = CastKitTheme.typography.labelLarge,
                    color = CastKitTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = CastKitSpacing.pageHorizontal,
                        bottom = CastKitSpacing.space2,
                    ),
                )
                OptionChipRow(
                    options = listOf("720p", "1080p", "1440p", "跟随本机"),
                    selected = "1080p",
                    onSelect = {},
                    label = { it },
                )
                Spacer(Modifier.height(CastKitSpacing.space4))

                Text(
                    text = "帧率",
                    style = CastKitTheme.typography.labelLarge,
                    color = CastKitTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = CastKitSpacing.pageHorizontal,
                        bottom = CastKitSpacing.space2,
                    ),
                )
                OptionChipRow(
                    options = listOf("15fps", "24fps", "30fps", "60fps"),
                    selected = "30fps",
                    onSelect = {},
                    label = { it },
                )
                Spacer(Modifier.height(CastKitSpacing.space4))

                SettingRow(
                    title = "保持屏幕比例",
                    summary = "开启：数字表示短边，长边按本机屏幕比例推导",
                    trailing = { Switch(checked = true, onCheckedChange = {}) },
                )
                SettingRow(
                    title = "显示 .nomedia 目录",
                    summary = "这些目录 MediaStore 不索引",
                    trailing = { Switch(checked = false, onCheckedChange = {}) },
                )
            }
        }

        SectionHeader(text = "文件夹", level = SectionHeaderLevel.Section)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CastKitSpacing.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(CastKitSpacing.space3),
        ) {
            FolderTile(
                name = "相机",
                countText = "12 个视频",
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            FolderTile(
                name = "下载",
                countText = "8 个视频",
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            FolderTile(
                name = "影片",
                countText = "31 个视频",
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }

        SectionHeader(text = "视频", level = SectionHeaderLevel.Section)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CastKitSpacing.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(CastKitSizes.gridGap),
        ) {
            VideoTile(
                title = "青海湖环湖骑行 4K.mp4",
                meta = "1080p · 245 MB",
                durationText = "04:12",
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            VideoTile(
                title = "海边日落",
                meta = "720p · 88 MB",
                durationText = "12:34",
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }

        SectionHeader(text = "操作", level = SectionHeaderLevel.Section)

        PrimaryBottomButton(
            text = "开始投屏",
            onClick = {},
        )
        PrimaryBottomButton(
            text = "开始投屏",
            onClick = {},
            enabled = false,
            hint = "投屏中，无法开始新的镜像",
        )

        SectionHeader(text = "空状态", level = SectionHeaderLevel.Section)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            color = CastKitTheme.colorScheme.background,
        ) {
            EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = "暂无视频",
                description = "把视频放进手机，或换个文件夹看看",
            )
        }

        Spacer(Modifier.height(CastKitSpacing.space6))
    }
}

@Preview(name = "组件画廊 · 浅色", showBackground = true, widthDp = 411, heightDp = 1400)
@Composable
private fun GalleryPreviewLight() {
    CastKitTheme(darkTheme = false, dynamicColor = false) {
        GalleryBody()
    }
}

@Preview(
    name = "组件画廊 · 深色",
    showBackground = true,
    widthDp = 411,
    heightDp = 1400,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun GalleryPreviewDark() {
    CastKitTheme(darkTheme = true, dynamicColor = false) {
        GalleryBody()
    }
}

@Preview(
    name = "组件画廊 · 360dp 窄屏（Chip 溢出检查）",
    showBackground = true,
    widthDp = 360,
    heightDp = 900,
)
@Composable
private fun GalleryPreviewNarrow() {
    CastKitTheme(darkTheme = false, dynamicColor = false) {
        GalleryBody()
    }
}
