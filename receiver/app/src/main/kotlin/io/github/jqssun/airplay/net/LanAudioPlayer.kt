package io.github.jqssun.airplay.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * LANCast v1 镜像音频解码播放器（AAC-LC -> PCM -> AudioTrack）。
 *
 * 运行在独立解码线程中，通过有界队列与 TCP 读取线程解耦：即使音频偶发阻塞，也绝不拖慢 H.264 画面渲染。
 */
class LanAudioPlayer(private val onLog: (String) -> Unit = {}) {

    private data class Packet(val data: ByteArray, val ptsUs: Long)

    private val queue = ArrayBlockingQueue<Packet>(MAX_QUEUE)
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Synchronized
    fun configure(sampleRate: Int, channels: Int, csd0: ByteArray) {
        stop()
        try {
            val fmt = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
            }
            val mc = MediaCodec.createDecoderByType(MIME)
            mc.configure(fmt, null, null, 0)
            mc.start()

            val channelOut = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                channelOut,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOut)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val at = builder.build()
            at.play()

            codec = mc
            track = at
            running = true
            worker = Thread({ decodeLoop(mc, at) }, "lancast-audio-dec").apply {
                isDaemon = true
                start()
            }
            onLog("局域网音频解码就绪 ${sampleRate}Hz ${channels}ch")
        } catch (t: Throwable) {
            Log.w(TAG, "初始化局域网音频解码失败: ${t.message}", t)
            onLog("局域网音频解码失败: ${t.message}")
            stop()
        }
    }

    fun feed(data: ByteArray, len: Int, ptsUs: Long) {
        if (!running || len <= 0) return
        val copy = data.copyOf(len)
        // 队列满时丢弃最旧一包以保持低延迟
        if (!queue.offer(Packet(copy, ptsUs))) {
            queue.poll()
            queue.offer(Packet(copy, ptsUs))
        }
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        queue.clear()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun decodeLoop(mc: MediaCodec, at: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var pcmScratch = ByteArray(8192)
        try {
            while (running) {
                val pkt = queue.poll(50, TimeUnit.MILLISECONDS)
                if (pkt != null) {
                    val inIdx = mc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = mc.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val n = minOf(pkt.data.size, inBuf.remaining())
                            inBuf.put(pkt.data, 0, n)
                            mc.queueInputBuffer(inIdx, 0, n, pkt.ptsUs, 0)
                        }
                    }
                }
                while (running) {
                    val outIdx = mc.dequeueOutputBuffer(info, 0)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        outIdx >= 0 -> {
                            val outBuf = mc.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) {
                                if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(pcmScratch, 0, info.size)
                                at.write(pcmScratch, 0, info.size)
                            }
                            mc.releaseOutputBuffer(outIdx, false)
                        }
                        else -> break
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "音频解码线程结束: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "LanAudioPlayer"
        const val MIME = "audio/mp4a-latm"
        const val MAX_QUEUE = 24
    }
}
