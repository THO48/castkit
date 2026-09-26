package io.github.jqssun.airplay.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// shared surface for mirroring (VideoRenderer) and airplay video (AirPlayVideoPlayer)
@Composable
fun VideoSurfaceView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    aspectRatio: Float = 16f / 9f,
    // false = caller sizes the surface (content-scale modes)
    applyAspectRatio: Boolean = true,
    // 需要知道渲染区域真实像素尺寸的调用方用这个（libVLC 的 vout 必须拿到正确窗口尺寸）
    onSurfaceHolder: ((SurfaceHolder) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val callbacks = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceHolder?.invoke(holder)
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                onSurfaceHolder?.invoke(holder)
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed(holder.surface)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also {
                it.holder.addCallback(callbacks)
            }
        },
        modifier = if (applyAspectRatio) {
            modifier
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f)
                .fillMaxSize()
        } else {
            modifier
        }
    )
}
