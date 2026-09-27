package io.github.jqssun.airplay.net

/**
 * LANCast v1 协议常量与编解码（见 docs/LANCast-v1.md）。
 *
 * 注意：发送端 `com.dsh.castkit.sender.net.LanCast` 是本文件的镜像拷贝（包名不同），
 * 改动协议时两边必须同步修改并更新 magic 尾字节。
 */
object LanCast {

    val MAGIC = byteArrayOf(0x4C, 0x43, 0x41, 0x53, 0x54, 0x31, 0x00, 0x00) // "LCAST1\0\0"

    const val DEFAULT_PORT = 8123
    const val DISCOVERY_PORT = 8124
    const val HANDSHAKE_SIZE = 24
    const val FRAME_HEADER_SIZE = 12

    /** 发现协议（UDP 广播，见 docs/LANCast-v1.md §5） */
    const val PROBE = "LCAST1-PROBE"
    const val HERE = "LCAST1-HERE"
    const val MAX_PAYLOAD = 8 * 1024 * 1024
    const val HANDSHAKE_TIMEOUT_MS = 10_000
    const val IDLE_TIMEOUT_MS = 15_000
    const val PING_INTERVAL_MS = 5_000

    const val TYPE_VIDEO_CSD = 0
    const val TYPE_VIDEO_AU = 1
    const val TYPE_AUDIO_CSD = 2
    const val TYPE_AUDIO_AU = 3
    const val TYPE_PING = 4

    /** 中途改分辨率（屏幕旋转等）：payload = u16 width | u16 height，随后跟一帧 CSD。 */
    const val TYPE_RESIZE = 5

    /**
     * 投视频文件（v1.1）：payload = UTF-8 的 http(s) 地址，接收端用系统原生播放器直接播放。
     * 该模式下不发送任何 H.264 帧，TCP 连接仅作为控制/心跳通道。
     */
    const val TYPE_PLAY_URL = 6

    /** 发送端要求结束投视频（用户点了停止）。连接意外断开不会发这个，播放不受影响。 */
    const val TYPE_STOP = 7

    /**
     * 投屏控制（发送端 → 接收端）：payload = u8 action | u32 value。
     * 投视频文件时发送端当遥控器用（播放/暂停/切换/seek）。
     */
    const val TYPE_CTRL = 8

    /**
     * 反向扩展消息前缀（接收端 → 发送端）。格式：`0x7F | u16 len | payload[len]`，
     * payload[0] 是类型。用 0x7F 当转义符是为了向后兼容：旧发送端会把它当成未知控制码直接忽略，
     * 镜像链路的原有裸字节控制（READY/REQUEST_KEYFRAME/BYE）不受影响。
     */
    const val EXT_BYTE = 0x7F
    const val EXT_STATUS = 9

    /**
     * 接收端用户在本机**主动断开**投送（接收端 → 发送端，payload 为空）。
     *
     * 没有这条消息时，接收端按返回只能停掉自己的播放器，要等发送端 8 秒收不到状态回报
     * 才自己收尾 —— 用户感知就是"接收端断不干净"。
     */
    const val EXT_STOP = 10

    /** `TYPE_CTRL` 的动作。 */
    const val ACTION_PLAY = 1
    const val ACTION_PAUSE = 2
    const val ACTION_TOGGLE = 3
    const val ACTION_SEEK = 4

    /** 扩展消息负载上限（防御异常长度）。 */
    const val MAX_EXT_PAYLOAD = 1024

    /** 状态回报间隔。 */
    const val STATUS_INTERVAL_MS = 1000L

    const val FLAG_KEYFRAME = 0x01

    const val CTRL_READY = 0
    const val CTRL_REQUEST_KEYFRAME = 1
    const val CTRL_BYE = 2

    const val VIDEO_CODEC_H264 = 1

    const val ERR_PROTOCOL = 1
    const val ERR_UNSUPPORTED = 2
    const val ERR_BUSY = 3
    const val ERR_INTERNAL = 4

    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /** 握手包解析结果。 */
    data class Handshake(
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoCodec: Int,
        val hasAudio: Boolean,
        val bitrateBps: Int,
        val csd: ByteArray?,
    )

    fun parseHandshake(buf: ByteArray, len: Int): Handshake? {
        if (len < HANDSHAKE_SIZE) return null
        for (i in MAGIC.indices) {
            if (buf[i] != MAGIC[i]) return null
        }
        val width = u16(buf, 8)
        val height = u16(buf, 10)
        val fps = u16(buf, 12)
        val codec = buf[14].toInt() and 0xFF
        val flags = buf[15].toInt() and 0xFF
        val bitrate = u32(buf, 16).toInt()
        val csdLen = u16(buf, 20)
        if (codec != VIDEO_CODEC_H264) return null
        if (csdLen > 0 && len < HANDSHAKE_SIZE + csdLen) return null
        val csd = if (csdLen > 0) buf.copyOfRange(HANDSHAKE_SIZE, HANDSHAKE_SIZE + csdLen) else null
        return Handshake(width, height, fps, codec, flags and 0x01 != 0, bitrate, csd)
    }

    /** CSD 负载 -> (sps, pps)。 */
    fun unpackCsd(csd: ByteArray): Pair<ByteArray, ByteArray>? {
        if (csd.size < 4) return null
        val spsLen = u16(csd, 0)
        if (csd.size < 2 + spsLen + 2) return null
        val sps = csd.copyOfRange(2, 2 + spsLen)
        val ppsLen = u16(csd, 2 + spsLen)
        if (csd.size < 4 + spsLen + ppsLen) return null
        val pps = csd.copyOfRange(4 + spsLen, 4 + spsLen + ppsLen)
        return sps to pps
    }

    /** 12 字节帧头解析。 */
    data class FrameHeader(val type: Int, val flags: Int, val ptsUs: Long, val len: Int)

    fun parseFrameHeader(b: ByteArray): FrameHeader = FrameHeader(
        type = b[0].toInt() and 0xFF,
        flags = b[1].toInt() and 0xFF,
        ptsUs = u32(b, 4),
        len = u32(b, 8).toInt(),
    )
}
