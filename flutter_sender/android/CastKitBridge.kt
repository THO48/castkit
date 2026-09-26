package com.dsh.castkit.flutter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.dsh.castkit.sender.CaptureSize
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.ResolutionPreset
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.VideoCastService
import com.dsh.castkit.sender.media.VideoDurations
import com.dsh.castkit.sender.media.VideoLibrary
import com.dsh.castkit.sender.media.VideoThumbnails
import com.dsh.castkit.sender.media.VideoSortField
import com.dsh.castkit.sender.net.LanCast
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.net.LanCastFileServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Dart <-> Kotlin 通道层。
 *
 * 设计原则：**引擎不动**（`com.dsh.castkit.sender.*` 下的 cast/net/media 全部原样复用），
 * 这一层只做三件事：把引擎能力暴露成方法、把引擎状态推成事件流、把需要 Activity 的操作转交出去。
 *
 * 方法通道：`com.dsh.castkit/methods`
 * 事件通道：`com.dsh.castkit/state`（投屏状态，约每秒变化）、`com.dsh.castkit/receivers`（发现的设备）
 *
 * 需要 Activity 的三件事（录屏授权、读媒体权限、选文件）由 [MainActivity] 通过 [HostRequests] 回调。
 */
class CastKitBridge(
    private val activity: ComponentActivity,
    private val host: HostRequests,
) {

    /** 需要宿主 Activity 配合的操作。 */
    interface HostRequests {
        fun requestScreenCapture(onResult: (resultCode: Int, data: Intent?) -> Unit)
        fun requestReadMediaVideo(onResult: (granted: Boolean) -> Unit)
        fun openDocument(onResult: (uri: String?) -> Unit)
        fun openAllFilesSettings()
        fun hasReadMediaVideo(): Boolean
        fun hasAllFilesAccess(): Boolean
    }

    private val context: Context get() = activity.applicationContext

    /** 自带作用域，省得你再引 lifecycle-runtime-ktx。 */
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** 设备发现：进程内一个实例，随 Dart 侧 start/stop 生命周期。 */
    private val discovery by lazy { LanCastDiscovery(activity) }

    fun onMethodCall(call: String, args: Map<String, Any?>, result: (Any?) -> Unit, error: (String, String) -> Unit) {
        when (call) {
            // ---------- 配置 ----------
            "getConfig" -> result(configMap())
            "saveConfig" -> {
                Prefs.persistConfig(
                    context,
                    preset = runCatching {
                        ResolutionPreset.valueOf(args["preset"] as? String ?: ResolutionPreset.P720.name)
                    }.getOrDefault(ResolutionPreset.P720),
                    customW = (args["customW"] as? Number)?.toInt() ?: 720,
                    customH = (args["customH"] as? Number)?.toInt() ?: 720,
                    fps = (args["fps"] as? Number)?.toInt() ?: Prefs.DEF_FPS,
                    mbps = (args["mbps"] as? Number)?.toInt() ?: Prefs.DEF_BITRATE_MBPS,
                    host = (args["host"] as? String)?.trim() ?: "",
                    port = (args["port"] as? Number)?.toInt() ?: Prefs.DEF_PORT,
                    keepAspect = args["keepAspect"] as? Boolean ?: Prefs.DEF_KEEP_ASPECT,
                )
                result(null)
            }
            "setTarget" -> {
                Prefs.setTarget(
                    context,
                    host = args["host"] as? String ?: "",
                    port = (args["port"] as? Number)?.toInt() ?: Prefs.DEF_PORT,
                    auto = args["auto"] as? Boolean ?: true,
                )
                result(null)
            }

            // ---------- 镜像投屏 ----------
            "startMirror" -> {
                val cfg = Prefs.config(context)
                if (cfg.host.isBlank()) {
                    error("E_ADDRESS", "接收端地址无效（host 为空）")
                } else {
                    host.requestScreenCapture { code, data ->
                        if (code != 0 && data != null) {
                            com.dsh.castkit.sender.cast.CastService.start(activity, code, data)
                            result(true)
                        } else {
                            result(false)
                        }
                    }
                }
            }
            "stopMirror" -> {
                com.dsh.castkit.sender.cast.CastService.stop(context)
                result(null)
            }

            // ---------- 投视频文件 ----------
            "pickVideo" -> host.openDocument { uri -> result(uri) }
            "describeVideo" -> {
                val uri = (args["uri"] as? String)?.let { Uri.parse(it) }
                if (uri == null) {
                    result(null)
                } else {
                    val src = LanCastFileServer.describe(context, uri)
                    result(
                        mapOf(
                            "uri" to uri.toString(),
                            "name" to src.name,
                            "size" to src.size,
                            "mime" to src.mime,
                        ),
                    )
                }
            }
            "startVideoCast" -> {
                val uri = (args["uri"] as? String)?.let { Uri.parse(it) }
                val hostAddr = args["host"] as? String ?: Prefs.host(context)
                val port = (args["port"] as? Number)?.toInt() ?: Prefs.port(context)
                val startMs = (args["startPositionMs"] as? Number)?.toLong() ?: 0L
                if (uri == null) error("E_URI", "uri 为空") else {
                    VideoCastService.start(context, uri, hostAddr, port, startMs)
                    result(true)
                }
            }
            "stopVideoCast" -> {
                VideoCastService.stop(context)
                result(null)
            }
            "videoControl" -> {
                val action = (args["action"] as? Number)?.toInt() ?: 0
                val value = (args["value"] as? Number)?.toLong() ?: 0L
                VideoCastService.control(context, action, value)
                result(null)
            }

            // ---------- 设备发现 ----------
            "discoveryStart" -> {
                discovery.start()
                result(null)
            }
            "discoveryStop" -> {
                discovery.stop()
                result(null)
            }
            "discoveryProbe" -> {
                discovery.probeNow()
                result(null)
            }

            // ---------- 视频库 ----------
            "listVideos" -> {
                val includeNoMedia = args["includeNoMedia"] as? Boolean ?: false
                val field = runCatching {
                    VideoSortField.valueOf(args["sortBy"] as? String ?: VideoSortField.DATE.name)
                }.getOrDefault(VideoSortField.DATE)
                val asc = args["ascending"] as? Boolean ?: false
                val path = args["folderPath"] as? String
                val all = VideoLibrary.loadAll(context, includeNoMedia)
                val list = if (path == null) {
                    VideoLibrary.sortVideos(all, field, asc)
                } else {
                    VideoLibrary.sortVideos(VideoLibrary.videosIn(all, path), field, asc)
                }
                result(list.map { videoMap(it) })
            }
            "listFolders" -> {
                val includeNoMedia = args["includeNoMedia"] as? Boolean ?: false
                val field = runCatching {
                    VideoSortField.valueOf(args["sortBy"] as? String ?: VideoSortField.DATE.name)
                }.getOrDefault(VideoSortField.DATE)
                val asc = args["ascending"] as? Boolean ?: false
                val path = args["folderPath"] as? String ?: VideoLibrary.ROOT_PATH
                val all = VideoLibrary.loadAll(context, includeNoMedia)
                result(
                    VideoLibrary.sortFolders(VideoLibrary.foldersIn(all, path), field, asc).map {
                        mapOf(
                            "path" to it.path,
                            "name" to it.name,
                            "directCount" to it.directCount,
                            "totalCount" to it.totalCount,
                            "subFolderCount" to it.subFolderCount,
                            "coverUri" to it.coverUri?.toString(),
                            "aggSizeBytes" to it.aggSizeBytes,
                            "aggDurationMs" to it.aggDurationMs,
                        )
                    },
                )
            }
            "loadThumbnail" -> {
                val uri = (args["uri"] as? String)?.let { Uri.parse(it) }
                if (uri == null) {
                    result(null)
                } else {
                    scope.launch {
                        val bmp = VideoThumbnails.load(activity, uri)
                        result(bmp?.let { toJpegBytes(it) })
                    }
                }
            }
            "loadDuration" -> {
                val uri = (args["uri"] as? String)?.let { Uri.parse(it) }
                if (uri == null) {
                    result(0L)
                } else {
                    scope.launch { result(VideoDurations.resolve(uri)) }
                }
            }

            // ---------- 权限 ----------
            "hasMediaPermission" -> result(host.hasReadMediaVideo())
            "requestMediaPermission" -> host.requestReadMediaVideo { granted -> result(granted) }
            "canReadAllFiles" -> result(host.hasAllFilesAccess())
            "openAllFilesSettings" -> {
                host.openAllFilesSettings()
                result(null)
            }
            else -> error("E_UNKNOWN", "未知方法: $call")
        }
    }

    // ---------- 事件流 ----------

    private var stateJob: Job? = null
    private var receiversJob: Job? = null

    fun onStateListen(on: Boolean, emit: (Any?) -> Unit) {
        stateJob?.cancel()
        stateJob = null
        if (on) {
            stateJob = scope.launch {
                CastBus.state.collect { emit(stateMap()) }
            }
        }
    }

    fun onReceiversListen(on: Boolean, emit: (Any?) -> Unit) {
        receiversJob?.cancel()
        receiversJob = null
        if (on) {
            receiversJob = scope.launch {
                discovery.receivers.collect { list ->
                    emit(list.map { mapOf("id" to it.id, "name" to it.name, "host" to it.host, "port" to it.port) })
                }
            }
        }
    }

    // ---------- 序列化 ----------

    private fun configMap(): Map<String, Any?> {
        val cfg = Prefs.config(context)
        return mapOf(
            "host" to cfg.host,
            "port" to cfg.port,
            "preset" to Prefs.preset(context).name,
            "customW" to Prefs.customWidth(context),
            "customH" to Prefs.customHeight(context),
            "fps" to cfg.fps,
            "mbps" to cfg.bitrateBps / 1_000_000,
            "keepAspect" to Prefs.keepAspect(context),
            // 实际投出尺寸（已按屏幕比例与编码器约束算好）
            "effectiveWidth" to cfg.width,
            "effectiveHeight" to cfg.height,
            "sizeNote" to cfg.sizeNote,
            "autoDiscovered" to Prefs.autoDiscovered(context),
            // 解析预设时可直接用
            "presets" to ResolutionPreset.entries.map { it.name to it.label },
            "nativeSize" to Prefs.nativeSize(context).let { listOf(it.first, it.second) },
            "defaults" to mapOf("fps" to Prefs.DEF_FPS, "mbps" to Prefs.DEF_BITRATE_MBPS, "port" to Prefs.DEF_PORT),
        )
    }

    private fun stateMap(): Map<String, Any?> {
        val s = CastBus.state.value
        return mapOf(
            "phase" to s.phase.name,
            "mode" to s.mode.name,
            "message" to s.message,
            "host" to s.host,
            "port" to s.port,
            "width" to s.width,
            "height" to s.height,
            "fps" to s.fps,
            "bitrateBps" to s.bitrateBps,
            "measuredKbps" to s.measuredKbps,
            "measuredFps" to s.measuredFps,
            "localPlayerActive" to s.localPlayerActive,
            "remotePositionMs" to s.remotePositionMs,
            "remoteDurationMs" to s.remoteDurationMs,
            "remotePlaying" to s.remotePlaying,
            "remoteBuffering" to s.remoteBuffering,
        )
    }

    private fun videoMap(v: com.dsh.castkit.sender.media.VideoItem): Map<String, Any?> = mapOf(
        "uri" to v.uri.toString(),
        "name" to v.name,
        "folderPath" to v.folderPath,
        "durationMs" to v.durationMs,
        "sizeBytes" to v.sizeBytes,
        "dateAddedSec" to v.dateAddedSec,
        "width" to v.width,
        "height" to v.height,
    )

    private fun toJpegBytes(bmp: android.graphics.Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    companion object {
        const val METHOD_CHANNEL = "com.dsh.castkit/methods"
        const val STATE_CHANNEL = "com.dsh.castkit/state"
        const val RECEIVERS_CHANNEL = "com.dsh.castkit/receivers"

        /** 遥控动作，与 [LanCast] 保持一致，Dart 侧直接用这些常量。 */
        const val ACTION_PLAY = LanCast.ACTION_PLAY
        const val ACTION_PAUSE = LanCast.ACTION_PAUSE
        const val ACTION_TOGGLE = LanCast.ACTION_TOGGLE
        const val ACTION_SEEK = LanCast.ACTION_SEEK
    }
}
