package com.dsh.castkit.sender.cast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dsh.castkit.sender.net.LanCast

/**
 * 镜像投屏系统音频采集与 AAC-LC 编码器（Android 10+ AudioPlaybackCapture）。
 *
 * 将本机媒体/游戏声音编码为原始 AAC 访问单元（TYPE_AUDIO_CSD / TYPE_AUDIO_AU）推给接收端。
 * 若系统版本 < Android 10、未授 RECORD_AUDIO 权限或底层采集不可用，则平滑返回 false，
 * 镜像画面继续正常运行，不影响视频投屏。
 */
class AudioCaptureEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val onCsd: (ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, offset: Int, length: Int, ptsUs: Long) -> Unit,
) {
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "Android < 10 不支持 AudioPlaybackCapture，跳过音频同步")
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "未授予 RECORD_AUDIO 权限，跳过音频同步")
            return false
        }

        return try {
            val captureCfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(FRAME_BYTES * 2)

            val ar = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureCfg)
                .setAudioFormat(audioFmt)
                .setBufferSizeInBytes(maxOf(minBuf * 2, FRAME_BYTES * 4))
                .build()

            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                Log.w(TAG, "AudioRecord 初始化失败")
                return false
            }

            val encFmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 2)
            }
            val mc = MediaCodec.createEncoderByType(MIME)
            mc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mc.start()

            record = ar
            codec = mc
            running = true
            ar.startRecording()

            thread = Thread({ loop(ar, mc) }, "lancast-audio-enc").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "音频采集已启动 ${SAMPLE_RATE}Hz ${CHANNELS}ch ${BITRATE / 1000}kbps")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "启动音频采集失败: ${t.message}", t)
            stop()
            false
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun loop(ar: AudioRecord, mc: MediaCodec) {
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        val startNs = System.nanoTime()
        var csdSent = false

        try {
            while (running) {
                val read = ar.read(pcm, 0, pcm.size)
                if (read > 0) {
                    val inIdx = mc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = mc.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val n = minOf(read, inBuf.remaining())
                            inBuf.put(pcm, 0, n)
                            val ptsUs = (System.nanoTime() - startNs) / 1000L
                            mc.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                        }
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read 返回错误: $read")
                    break
                }

                // 排空可用 AAC 输出帧
                while (running) {
                    val outIdx = mc.dequeueOutputBuffer(info, 0)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val csd0 = mc.outputFormat.getByteBuffer("csd-0")?.let { b ->
                                ByteArray(b.remaining()).also { b.get(it) }
                            }
                            if (csd0 != null && csd0.isNotEmpty()) {
                                csdSent = true
                                onCsd(LanCast.packAudioCsd(SAMPLE_RATE, CHANNELS, csd0))
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = mc.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) {
                                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val data = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(data, 0, info.size)
                                if (isConfig) {
                                    if (!csdSent) {
                                        csdSent = true
                                        onCsd(LanCast.packAudioCsd(SAMPLE_RATE, CHANNELS, data))
                                    }
                                } else {
                                    onFrame(data, 0, data.size, info.presentationTimeUs)
                                }
                            }
                            mc.releaseOutputBuffer(outIdx, false)
                        }
                        else -> break
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "音频编码循环结束: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "AudioCaptureEncoder"
        private const val MIME = "audio/mp4a-latm"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        private const val BITRATE = 128_000
        // 1024 samples * 2ch * 2 bytes = 4096 bytes per AAC frame (~21.3ms)
        private const val FRAME_BYTES = 4096
    }
}
