package io.github.jqssun.airplay.ui

import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.renderer.LanVideoState
import kotlinx.coroutines.delay

/**
 * CastKit: 局域网「投视频文件」播放页。
 *
 * 发送端只给出视频地址，这里用系统播放器播放原始文件，所以画质/声音/进度都是原生的，
 * 控件只需要最基础的播放暂停、进度条和退出。
 */
@Composable
fun LanVideoScreen(
    state: LanVideoState,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    var overlayVisible by remember { mutableStateOf(true) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val position = scrubMs ?: state.positionMs

    // 播放中自动收起控件；正在拖动进度条时不收
    LaunchedEffect(state.playing, overlayVisible, scrubMs) {
        if (state.playing && overlayVisible && scrubMs == null) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        }
    }

    // 播放时保持屏幕常亮
    val view = LocalView.current
    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }

    BackHandler { onStop() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { overlayVisible = !overlayVisible },
        contentAlignment = Alignment.Center,
    ) {
        VideoSurfaceView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = if (state.aspect > 0f) state.aspect else 16f / 9f,
        )

        if (state.buffering) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
        }

        state.error?.let { msg ->
            Text(
                msg,
                color = Color(0xFFFF6B6B),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }

        // 播放源消失（发送端退出/断网）：中性提示，随后自动收起本页
        state.ended?.let { msg ->
            Text(
                msg,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        if (overlayVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    state.title.ifBlank { "投屏视频" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = position.toFloat(),
                        onValueChange = { scrubMs = it.toLong() },
                        onValueChangeFinished = {
                            scrubMs?.let(onSeek)
                            scrubMs = null
                        },
                        valueRange = 0f..(if (state.durationMs > 0) state.durationMs.toFloat() else 1f),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    Text(
                        formatTime(state.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = onToggle) {
                        Icon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.playing) "暂停" else "播放")
                    }
                    OutlinedButton(onClick = onStop) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("退出")
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private const val OVERLAY_HIDE_MS = 4000L
