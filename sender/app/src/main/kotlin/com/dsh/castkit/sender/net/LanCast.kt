package com.dsh.castkit.sender.net

/**
 * LANCast v1 协议常量与编解码（见 docs/LANCast-v1.md）。
 *
 * 注意：接收端 `com.dsh.castkit.receiver.net.LanCast` 是本文件的镜像拷贝（包名不同），
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

    /** 中途改分辨率（如屏幕旋转）：payload = u16 width | u16 height，随后必须跟一帧 CSD。 */
    const val TYPE_RESIZE = 5

    /**
     * 投视频文件（v1.1）：payload = UTF-8 的 http(s) 地址，接收端用系统原生播放器直接播放。
     * 该模式下不发送任何 H.264 帧，TCP 连接仅作为控制/心跳通道。
     */
    const val TYPE_PLAY_URL = 6

    /** 发送端要求结束投视频（用户点了停止）。连接意外断开不会发这个。 */
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
     * 接收端用户在本机**主动断开**投送：payload 为空。
     *
     * 之前没有这条消息，接收端按返回键只能停掉自己的播放器，发送端要等 8 秒收不到状态回报
     * 才反应过来 —— 用户看到的就是"接收端断不干净"。旧发送端读到 `0x7F` 会当未知控制码忽略，
     * 于是自动退回原来的超时兜底，不会出错。
     */
    const val EXT_STOP = 10

    /** `TYPE_CTRL` 的动作。 */
    const val ACTION_PLAY = 1
    const val ACTION_PAUSE = 2
    const val ACTION_TOGGLE = 3
    const val ACTION_SEEK = 4

    /** 扩展消息负载上限（防御异常长度）。 */
    const val MAX_EXT_PAYLOAD = 1024

    const val FLAG_KEYFRAME = 0x01

    const val CTRL_READY = 0
    const val CTRL_REQUEST_KEYFRAME = 1
    const val CTRL_BYE = 2

    const val VIDEO_CODEC_H264 = 1

    const val ERR_PROTOCOL = 1
    const val ERR_UNSUPPORTED = 2
    const val ERR_BUSY = 3
    const val ERR_INTERNAL = 4

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /** 握手包：24 字节头 + CSD。 */
    fun handshake(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        hasAudio: Boolean,
        csd: ByteArray?,
    ): ByteArray {
        val csdLen = csd?.size ?: 0
        require(csdLen <= 0xFFFF) { "CSD too large" }
        val out = ByteArray(HANDSHAKE_SIZE + csdLen)
        MAGIC.copyInto(out, 0)
        putU16(out, 8, width)
        putU16(out, 10, height)
        putU16(out, 12, fps)
        out[14] = VIDEO_CODEC_H264.toByte()
        out[15] = if (hasAudio) 1 else 0
        putU32(out, 16, bitrateBps.toLong())
        putU16(out, 20, csdLen)
        putU16(out, 22, 0)
        csd?.copyInto(out, HANDSHAKE_SIZE)
        return out
    }

    /** 把 MediaCodec 的 csd-0 / csd-1 组装成协议里的 CSD 负载。 */
    fun packCsd(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArray(4 + sps.size + pps.size)
        putU16(out, 0, sps.size)
        sps.copyInto(out, 2)
        putU16(out, 2 + sps.size, pps.size)
        pps.copyInto(out, 4 + sps.size)
        return out
    }

    /** `TYPE_RESIZE` 负载：u16 宽 | u16 高。 */
    fun resizePayload(width: Int, height: Int): ByteArray {
        val out = ByteArray(4)
        putU16(out, 0, width)
        putU16(out, 2, height)
        return out
    }

    /** 写帧头到 [out] 的前 12 字节。 */
    fun writeFrameHeader(out: ByteArray, type: Int, flags: Int, ptsUs: Long, len: Int) {
        out[0] = type.toByte()
        out[1] = flags.toByte()
        putU16(out, 2, 0)
        putU32(out, 4, ptsUs)
        putU32(out, 8, len.toLong())
    }
}
