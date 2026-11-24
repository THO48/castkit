package io.github.jqssun.airplay.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// shared surface for mirroring (VideoRenderer), airplay video (AirPlayVideoPlayer) and lan video (LanVideoPlayer)
@Composable
fun VideoSurfaceView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    aspectRatio: Float = 16f / 9f,
    // false = caller sizes the surface (content-scale modes)
    applyAspectRatio: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val currentOnAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentOnDestroyed by rememberUpdatedState(onSurfaceDestroyed)

    val callbacks = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                currentOnAvailable(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                if (w > 0 && h > 0) {
                    currentOnAvailable(holder.surface)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                currentOnDestroyed(holder.surface)
            }
        }
    }

    val safeRatio = if (aspectRatio.isFinite() && aspectRatio > 0.05f) aspectRatio else 16f / 9f
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val surfaceModifier = if (applyAspectRatio && maxWidth.value > 0f && maxHeight.value > 0f) {
            val containerRatio = maxWidth.value / maxHeight.value
            if (containerRatio > safeRatio) {
                Modifier.size(width = maxHeight * safeRatio, height = maxHeight)
            } else {
                Modifier.size(width = maxWidth, height = maxWidth / safeRatio)
            }
        } else {
            Modifier.fillMaxSize()
        }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also {
                    it.holder.addCallback(callbacks)
                }
            },
            modifier = surfaceModifier,
        )
    }
}
