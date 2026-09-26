package io.github.jqssun.airplay.ui

import android.app.Activity
import android.view.Surface
import android.view.SurfaceHolder
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
import androidx.compose.ui.platform.LocalContext
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
    onSurfaceHolder: (SurfaceHolder) -> Unit = {},
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    var overlayVisible by remember { mutableStateOf(true) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val position = scrubMs ?: state.positionMs

    // 竖滑调整：亮度只作用于本窗口，音量走系统。两个值都是 0..1 的浮点，
    // 滑动时连续变化；音量那个实际落到系统上会被量化成整数档（见 SystemVolume 注释）。
    val context = LocalContext.current
    val activity = context as? Activity
    var adjusting by remember { mutableStateOf<PlayerAdjustTarget?>(null) }
    var brightness by remember { mutableStateOf(PlayerBrightness.current(context)) }
    var volume by remember { mutableStateOf(SystemVolume.current(context)) }

    /** 进播放页时记下的系统亮度**原始档位**：退出播放、以及切到后台时都要还原成它。 */
    val savedBrightnessRaw = remember { PlayerBrightness.currentRaw(context) }

    /** 用户是否真的调过亮度：没调过就完全不碰系统亮度（否则进页/切后台会白改一下）。 */
    var brightnessAdjusted by remember { mutableStateOf(false) }

    // 按 Home / 切到别的 App 时播放页不会 dispose，所以亮度还得靠生命周期管（见 PlayerBrightnessEffect）
    PlayerBrightnessEffect(activity, context, brightness, savedBrightnessRaw, brightnessAdjusted)

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

    // 亮度只跟本页绑定：退出时按原值还原（有些 ROM 会把窗口亮度写进系统设置；
    // 音量不动，那本来就是系统音量）
    DisposableEffect(Unit) {
        onDispose { if (brightnessAdjusted) PlayerBrightness.restore(activity, context, savedBrightnessRaw) }
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
            onSurfaceHolder = onSurfaceHolder,
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

        // 竖滑调整层：铺在画面之上、控制栏之下。
        // 放在这里（而不是加在父 Box 的手势里）是为了不跟"单击切控制栏"打架，理由见 PlayerAdjustLayer。
        PlayerAdjustLayer(
            onStart = { target ->
                adjusting = target
                // 每次开始滑动都以当前实际值起步，避免上次滑到哪就永远从哪开始
                when (target) {
                    PlayerAdjustTarget.BRIGHTNESS -> brightness = PlayerBrightness.current(context)
                    PlayerAdjustTarget.VOLUME -> volume = SystemVolume.current(context)
                }
            },
            onDelta = { target, delta ->
                when (target) {
                    PlayerAdjustTarget.BRIGHTNESS -> {
                        brightnessAdjusted = true
                        brightness = (brightness + delta).coerceIn(0f, 1f)
                        PlayerBrightness.apply(activity, brightness)
                    }

                    PlayerAdjustTarget.VOLUME -> {
                        volume = (volume + delta).coerceIn(0f, 1f)
                        SystemVolume.apply(context, volume)
                    }
                }
            },
            onEnd = { adjusting = null },
        )

        // 竖滑时的中央提示
        adjusting?.let { target ->
            PlayerAdjustIndicator(
                target = target,
                value = when (target) {
                    PlayerAdjustTarget.BRIGHTNESS -> brightness
                    PlayerAdjustTarget.VOLUME -> volume
                },
                modifier = Modifier.align(Alignment.Center),
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
