package com.dsh.castkit.sender.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.dsh.castkit.sender.CastConfig

/**
 * MediaCodec H.264 编码器：输入 Surface 由 VirtualDisplay 喂画面，输出访问单元（AU）通过回调交给网络层。
 *
 * 分辨率/码率/帧率全部来自 [CastConfig]，可在开始投屏前任意调整（改动后重新开始会话即可）。
 */
class ScreenEncoder(
    private val config: CastConfig,
    private val onCsd: (csd: ByteArray, width: Int, height: Int) -> Unit,
    private val onFrame: (data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyframe: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var csdSent = false

    @Volatile
    private var surface: Surface? = null

    private val bufferInfo = MediaCodec.BufferInfo()

    /**
     * 编码器输入面。在 [start] 里、编码线程启动**之前**创建并缓存，原因有二：
     *  1) `createInputSurface()` 与 `codec.start()` 并发调用会踩 MediaCodec 的非线程安全；
     *  2) 屏幕旋转时若挂面失败需要回滚，必须能拿回**同一个** Surface 重新挂到 VirtualDisplay。
     */
    fun inputSurface(): Surface = requireNotNull(surface) { "编码器未启动" }

    @Throws(java.io.IOException::class)
    fun start() {
        val fmt = MediaFormat.createVideoFormat(MIME, config.width, config.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            // 低延迟相关提示（部分编码器忽略）
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }
        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec = c
        surface = c.createInputSurface()
        running = true
        thread = Thread({ loop(c) }, "lancast-encoder").apply {
            isDaemon = true
            start()
        }
    }

    /** 请求下一帧为 I 帧（接收端丢帧花屏时调用）。 */
    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Throwable) {
            Log.w(TAG, "requestKeyFrame failed", e)
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        // 输入面随编码器一起释放；调用方必须先把它从 VirtualDisplay 上摘掉
        try {
            surface?.release()
        } catch (_: Throwable) {
        }
        surface = null
    }

    private fun loop(c: MediaCodec) {
        var started = false
        try {
            c.start()
            started = true
            csdSent = false
            while (running) {
                val index = c.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!csdSent) {
                            val fmt = c.outputFormat
                            val csd = readCsd(fmt)
                            if (csd != null) {
                                csdSent = true
                                val w = formatInt(fmt, MediaFormat.KEY_WIDTH, config.width)
                                val h = formatInt(fmt, MediaFormat.KEY_HEIGHT, config.height)
                                onCsd(csd, w, h)
                            }
                        }
                    }
                    index >= 0 -> {
                        val buf = c.getOutputBuffer(index)
                        if (buf != null && bufferInfo.size > 0) {
                            val key = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val data = ByteArray(bufferInfo.size)
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            buf.get(data, 0, bufferInfo.size)
                            onFrame(data, 0, data.size, bufferInfo.presentationTimeUs, key)
                        }
                        c.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Throwable) {
            if (running) {
                Log.e(TAG, "编码循环异常", e)
                onError(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            if (started) {
                try {
                    c.stop()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun formatInt(fmt: MediaFormat, key: String, fallback: Int): Int =
        try {
            if (fmt.containsKey(key)) fmt.getInteger(key) else fallback
        } catch (_: Throwable) {
            fallback
        }

    private fun readCsd(fmt: MediaFormat): ByteArray? {
        val sps = fmt.getByteBuffer("csd-0")?.let { b ->
            ByteArray(b.remaining()).also { b.get(it) }
        }
        val pps = fmt.getByteBuffer("csd-1")?.let { b ->
            ByteArray(b.remaining()).also { b.get(it) }
        }
        if (sps == null || pps == null) return null
        return com.dsh.castkit.sender.net.LanCast.packCsd(sps, pps)
    }

    private companion object {
        const val TAG = "ScreenEncoder"
        const val MIME = "video/avc"
    }
}
