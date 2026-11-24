package com.dsh.castkit.sender.cast

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dsh.castkit.sender.CastKitApp
import com.dsh.castkit.sender.MainActivity
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.net.LanCast
import com.dsh.castkit.sender.net.LanCastClient
import com.dsh.castkit.sender.net.LanCastFileServer

/**
 * 「投视频文件 & 共享视频库」前台服务：
 *  1. 用 [LanCastFileServer] 通过局域网 HTTP 暴露本机视频库（`/api/library`、`/item/<id>/...`、`/thumb/<id>.jpg`）；
 *  2. 通过 LANCast 控制连接告知接收端视频库基址（`TYPE_LIBRARY_BASE`）及初始播放地址（`TYPE_PLAY_URL`）；
 *  3. 接收端可随时退出当前视频并在平板上浏览/切换任意视频，通过 `EXT_SELECT_VIDEO` 实时回传给发送端同步遥控器；
 *  4. 接收端处于选片状态时发送 `STATUS_FLAG_BROWSING`，发送端保持 HTTP 服务存活不中断。
 */
class VideoCastService : Service() {

    private var server: LanCastFileServer? = null
    private var client: LanCastClient? = null
    private var source: LanCastFileServer.Source? = null
    private var url: String? = null
    private var libraryBaseUrl: String? = null
    private var host: String = ""
    private var port: Int = LanCast.DEFAULT_PORT
    private var connectedHost: String = ""
    private var connectedPort: Int = 0
    private var targetName: String = ""
    private var stoppedByUser = false
    private var connectAttempt = 0
    private var pingThread: Thread? = null
    private var startPositionMs = 0L

    private var statusSeen = false
    private var lastStatusAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedByUser = true
                teardown("已停止")
                stopSelf()
            }
            ACTION_CTRL -> {
                val action = intent.getIntExtra(EXTRA_ACTION, 0)
                val value = intent.getLongExtra(EXTRA_VALUE, 0L)
                if (action == LanCast.ACTION_SPEED && value in 25..300) {
                    CastBus.update { it.copy(remoteSpeed = value / 100f) }
                }
                if (action != 0) client?.sendControl(action, value)
            }
            ACTION_START_LIBRARY -> {
                if (!resolveTarget(intent)) return START_NOT_STICKY
                startPositionMs = 0L
                startAsForeground("正在共享本机视频库…")
                startLibrarySession()
            }
            ACTION_START -> {
                startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)
                val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
                if (uri == null) {
                    CastBus.update { it.copy(phase = CastPhase.ERROR, message = getString(R.string.err_no_video)) }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!resolveTarget(intent)) return START_NOT_STICKY
                startAsForeground(getString(R.string.video_preparing))
                startServing(uri)
            }
        }
        return START_NOT_STICKY
    }

    private fun resolveTarget(intent: Intent): Boolean {
        host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: Prefs.host(this)
        port = intent.getIntExtra(EXTRA_PORT, Prefs.port(this))
        targetName = intent.getStringExtra(EXTRA_TARGET_NAME)?.takeIf { it.isNotBlank() } ?: Prefs.targetName(this)
        if (host.isBlank()) {
            CastBus.update { it.copy(phase = CastPhase.ERROR, message = getString(R.string.err_bad_address)) }
            stopSelf()
            return false
        }
        Prefs.rememberReceiver(this, targetName, host, port)
        stoppedByUser = false
        connectAttempt = 0
        statusSeen = false
        lastStatusAt = 0L
        return true
    }

    override fun onDestroy() {
        teardown("服务结束")
        super.onDestroy()
    }

    private fun ensureServer(): LanCastFileServer = server ?: LanCastFileServer(this).also { server = it }

    private fun startLibrarySession() {
        val srv = ensureServer()
        val base = try {
            srv.startLibraryOnly()
        } catch (e: Throwable) {
            Log.e(TAG, "启动视频库服务失败", e)
            CastBus.update {
                it.copy(phase = CastPhase.ERROR, mode = CastMode.VIDEO, message = e.message ?: "视频库服务启动失败")
            }
            stopSelf()
            return
        }
        source = null
        url = null
        libraryBaseUrl = base
        Log.i(TAG, "视频库服务已启动：$base")
        CastBus.update {
            it.copy(
                phase = CastPhase.CONNECTING,
                mode = CastMode.VIDEO,
                message = "接收端选片模式",
                host = host,
                port = port,
                targetName = targetName,
                videoUri = "",
                videoTitle = "",
                width = 0,
                height = 0,
                fps = 0,
                bitrateBps = 0,
                remotePositionMs = 0L,
                remoteDurationMs = 0L,
                remotePlaying = false,
                remoteBuffering = false,
                remoteBrowsing = true,
                remoteSpeed = 1f,
            )
        }
        updateNotification("已共享本机视频库给 ${targetName.ifBlank { host }}")
        val existing = client
        if (existing != null && existing.isConnected() && connectedHost == host && connectedPort == port) {
            runCatching {
                sendLibraryBase(existing, base)
                CastBus.update { it.copy(phase = CastPhase.RUNNING, remoteBrowsing = true) }
                return
            }
        }
        runCatching { existing?.close() }
        client = null
        connectedHost = host
        connectedPort = port
        connect()
        startPingLoop()
    }

    private fun startServing(uri: Uri) {
        val src = LanCastFileServer.describe(this, uri)
        source = src
        val srv = ensureServer()
        val playUrl = try {
            srv.start(src)
        } catch (e: Throwable) {
            Log.e(TAG, "启动文件服务失败", e)
            CastBus.update {
                it.copy(phase = CastPhase.ERROR, mode = CastMode.VIDEO, message = e.message ?: "文件服务启动失败")
            }
            stopSelf()
            return
        }
        url = playUrl
        libraryBaseUrl = srv.baseUrl
        Log.i(TAG, "文件服务已启动：$playUrl（${src.size} 字节）")
        CastBus.update {
            it.copy(
                phase = CastPhase.CONNECTING,
                mode = CastMode.VIDEO,
                message = src.name,
                host = host,
                port = port,
                targetName = targetName,
                videoUri = uri.toString(),
                videoTitle = src.name,
                width = 0,
                height = 0,
                fps = 0,
                bitrateBps = 0,
                remotePositionMs = startPositionMs,
                remoteDurationMs = 0L,
                remoteBrowsing = false,
                remoteSpeed = 1f,
            )
        }
        updateNotification(getString(R.string.video_serving, src.name))
        val existing = client
        if (existing != null && existing.isConnected() && connectedHost == host && connectedPort == port) {
            runCatching {
                libraryBaseUrl?.let { sendLibraryBase(existing, it) }
                val payload = playUrl.toByteArray(Charsets.UTF_8)
                existing.sendFrame(LanCast.TYPE_PLAY_URL, 0, 0L, payload, 0, payload.size)
                if (startPositionMs > 0) existing.sendControl(LanCast.ACTION_SEEK, startPositionMs)
                CastBus.update {
                    it.copy(
                        phase = CastPhase.RUNNING,
                        message = src.name,
                        remoteBrowsing = false,
                        startedAtMs = if (it.startedAtMs > 0L) it.startedAtMs else System.currentTimeMillis(),
                    )
                }
                updateNotification(getString(R.string.video_playing, src.name))
                return
            }
        }
        runCatching { existing?.close() }
        client = null
        connectedHost = host
        connectedPort = port
        connect()
        startPingLoop()
    }

    private fun sendLibraryBase(c: LanCastClient, base: String) {
        val bytes = base.toByteArray(Charsets.UTF_8)
        c.sendFrame(LanCast.TYPE_LIBRARY_BASE, 0, 0L, bytes, 0, bytes.size)
    }

    private fun connect() {
        val base = libraryBaseUrl ?: return
        val target = url
        val c = LanCastClient(host, port)
        c.onControl = { control ->
            when (control) {
                LanCast.CTRL_READY -> {
                    connectAttempt = 0
                    val now = System.currentTimeMillis()
                    CastBus.update {
                        it.copy(
                            phase = CastPhase.RUNNING,
                            message = source?.name ?: "接收端选片中",
                            startedAtMs = if (it.startedAtMs > 0L) it.startedAtMs else now,
                        )
                    }
                    runCatching {
                        // 始终先下发视频库基址，接收端随时可拉取视频列表
                        sendLibraryBase(c, base)
                        if (target != null) {
                            val payload = target.toByteArray(Charsets.UTF_8)
                            c.sendFrame(LanCast.TYPE_PLAY_URL, 0, 0L, payload, 0, payload.size)
                            if (startPositionMs > 0) {
                                c.sendControl(LanCast.ACTION_SEEK, startPositionMs)
                            }
                            updateNotification(getString(R.string.video_playing, source?.name ?: ""))
                        } else {
                            updateNotification("接收端正在浏览本机视频库")
                        }
                    }.onFailure { Log.w(TAG, "下发播放地址失败", it) }
                }
                LanCast.CTRL_REQUEST_KEYFRAME -> Unit
            }
        }
        c.onExtended = { type, payload ->
            when (type) {
                LanCast.EXT_STATUS -> if (payload.size >= 9) {
                    statusSeen = true
                    lastStatusAt = System.currentTimeMillis()
                    val flags = payload[8].toInt() and 0xFF
                    val browsing = (flags and LanCast.STATUS_FLAG_BROWSING) != 0
                    CastBus.update {
                        it.copy(
                            remotePositionMs = LanCast.u32(payload, 0),
                            remoteDurationMs = LanCast.u32(payload, 4),
                            remotePlaying = (flags and LanCast.STATUS_FLAG_PLAYING) != 0,
                            remoteBuffering = (flags and LanCast.STATUS_FLAG_BUFFERING) != 0,
                            remoteBrowsing = browsing,
                            message = if (browsing) "接收端正在浏览视频库选片…" else (source?.name ?: it.message),
                        )
                    }
                }
                LanCast.EXT_SELECT_VIDEO -> {
                    val selectedId = String(payload, Charsets.UTF_8).trim()
                    val entry = server?.setActiveById(selectedId)
                    if (entry != null) {
                        source = LanCastFileServer.Source(entry.uri, entry.sizeBytes, entry.name, entry.mime)
                        url = libraryBaseUrl?.let { LanCastFileServer.itemUrl(it, entry.id, entry.name) }
                        Log.i(TAG, "接收端已切换视频: ${entry.name}")
                        CastBus.update {
                            it.copy(
                                phase = CastPhase.RUNNING,
                                mode = CastMode.VIDEO,
                                videoUri = entry.uri.toString(),
                                videoTitle = entry.name,
                                message = entry.name,
                                remotePositionMs = 0L,
                                remoteDurationMs = entry.durationMs,
                                remotePlaying = true,
                                remoteBuffering = true,
                                remoteBrowsing = false,
                                remoteSpeed = 1f,
                            )
                        }
                        updateNotification(getString(R.string.video_playing, entry.name))
                    }
                }
                LanCast.EXT_DISCONNECT -> {
                    Log.i(TAG, "接收端主动断开视频库会话")
                    stoppedByUser = true
                    teardown("接收端已结束投送")
                    stopSelf()
                }
            }
        }
        c.onClosed = { reason -> onControlLost(reason) }
        client = c
        try {
            c.connect(LanCast.handshake(0, 0, 0, 0, true, null))
        } catch (e: Throwable) {
            onControlLost(e.message ?: "连接失败")
        }
    }

    private fun onControlLost(reason: String) {
        if (stoppedByUser) return
        client?.close()
        client = null
        if (connectAttempt >= MAX_RECONNECT) {
            Log.w(TAG, "控制连接重连失败：$reason")
            CastBus.update { it.copy(message = getString(R.string.video_control_lost)) }
            updateNotification(getString(R.string.video_control_lost))
            return
        }
        connectAttempt++
        Thread {
            Thread.sleep(1500L * connectAttempt)
            if (!stoppedByUser && server?.isRunning() == true) connect()
        }.apply { isDaemon = true }.start()
    }

    private fun startPingLoop() {
        if (pingThread != null) return
        pingThread = Thread {
            while (!stoppedByUser) {
                try {
                    Thread.sleep(LanCast.PING_INTERVAL_MS.toLong())
                } catch (_: InterruptedException) {
                    break
                }
                runCatching { client?.takeIf { it.isConnected() }?.sendPing() }
                if (statusSeen && System.currentTimeMillis() - lastStatusAt > RECEIVER_IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "接收端已停止会话，结束投送")
                    lastStatusAt = System.currentTimeMillis()
                    statusSeen = false
                    CastBus.update { it.copy(phase = CastPhase.IDLE, message = "接收端已停止投屏") }
                    teardown("接收端已停止投屏")
                    stopSelf()
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, FOREGROUND_TYPE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun teardown(reason: String) {
        stoppedByUser = stoppedByUser || reason == "已停止"
        pingThread?.interrupt()
        pingThread = null
        val controlClient = client
        val fileServer = server
        client = null
        server = null
        url = null
        libraryBaseUrl = null
        Thread {
            if (stoppedByUser) {
                runCatching { controlClient?.takeIf { it.isConnected() }?.sendStop() }
                try {
                    Thread.sleep(STOP_GRACE_MS)
                } catch (_: InterruptedException) {
                }
            }
            runCatching { controlClient?.close() }
            runCatching { fileServer?.stop() }
        }.apply { isDaemon = true }.start()
        CastBus.update {
            it.copy(
                videoUri = "",
                videoTitle = "",
                startedAtMs = 0L,
                remotePositionMs = 0,
                remoteDurationMs = 0,
                remotePlaying = false,
                remoteBuffering = false,
                remoteBrowsing = false,
                remoteSpeed = 1f,
            )
        }
        val cur = CastBus.state.value
        if (cur.phase == CastPhase.IDLE) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            return
        }
        if (cur.phase != CastPhase.ERROR) {
            CastBus.update {
                it.copy(phase = CastPhase.IDLE, mode = CastMode.MIRROR, message = reason, measuredKbps = 0, measuredFps = 0f)
            }
        } else {
            CastBus.update { it.copy(mode = CastMode.MIRROR) }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification(text: String) {
        runCatching {
            val mgr = getSystemService(android.app.NotificationManager::class.java)
            mgr.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VideoCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CastKitApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.video_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "VideoCastService"
        const val ACTION_START = "com.dsh.castkit.sender.START_VIDEO"
        const val ACTION_START_LIBRARY = "com.dsh.castkit.sender.START_LIBRARY"
        const val ACTION_STOP = "com.dsh.castkit.sender.STOP_VIDEO"
        const val ACTION_CTRL = "com.dsh.castkit.sender.CTRL_VIDEO"
        const val EXTRA_URI = "videoUri"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TARGET_NAME = "targetName"
        const val EXTRA_START_POSITION = "startPositionMs"
        const val EXTRA_ACTION = "controlAction"
        const val EXTRA_VALUE = "controlValue"
        private const val NOTIFICATION_ID = 4712
        private const val MAX_RECONNECT = 5
        private const val RECEIVER_IDLE_TIMEOUT_MS = 10_000L
        private const val STOP_GRACE_MS = 350L

        private val FOREGROUND_TYPE: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }

        fun start(
            context: Context,
            uri: Uri,
            host: String,
            port: Int,
            startPositionMs: Long = 0L,
            targetName: String = "",
        ) {
            val intent = Intent(context, VideoCastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TARGET_NAME, targetName)
                .putExtra(EXTRA_START_POSITION, startPositionMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 直接共享本机视频库给接收端选片播放。 */
        fun startLibrary(context: Context, host: String, port: Int, targetName: String = "") {
            val intent = Intent(context, VideoCastService::class.java)
                .setAction(ACTION_START_LIBRARY)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TARGET_NAME, targetName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, VideoCastService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        fun control(context: Context, action: Int, value: Long = 0L) {
            val intent = Intent(context, VideoCastService::class.java)
                .setAction(ACTION_CTRL)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_VALUE, value)
            runCatching { context.startService(intent) }
        }
    }
}
