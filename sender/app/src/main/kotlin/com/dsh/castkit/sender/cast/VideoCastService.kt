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
 * 「投视频文件」前台服务。
 *
 * 与镜像投屏完全不同的思路：发送端只做两件事——
 *  1. 用 [LanCastFileServer] 把选中的视频通过局域网 HTTP 暴露出去；
 *  2. 通过 LANCast 控制连接把这个地址告诉接收端，**不发送任何视频帧**。
 * 接收端用系统原生播放器直接播这个地址，因此拿到的是原始码流：原始画质、有声音、能拖进度、
 * 硬解，并且**不需要 MediaProjection 录屏授权**。
 *
 * 控制连接只用来下发地址和通知停止（以及心跳，防止接收端按空闲超时断开），
 * 它断了也不影响正在进行的播放（播放走的是 HTTP），所以这里只重连不终止。
 */
class VideoCastService : Service() {

    private var server: LanCastFileServer? = null
    private var client: LanCastClient? = null
    private var source: LanCastFileServer.Source? = null
    private var url: String? = null
    private var host: String = ""
    private var port: Int = LanCast.DEFAULT_PORT
    private var stoppedByUser = false
    private var connectAttempt = 0
    private var pingThread: Thread? = null
    /** 开投时本机的播放进度：连上后会让接收端从这里接着播。 */
    private var startPositionMs = 0L

    /** 是否收到过接收端的状态回报（收到过才启用"接收端已停止"的超时判断，兼容旧接收端）。 */
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
                // 播放页当遥控器用：把控制指令透传给接收端
                val action = intent.getIntExtra(EXTRA_ACTION, 0)
                val value = intent.getLongExtra(EXTRA_VALUE, 0L)
                if (action != 0) client?.sendControl(action, value)
            }
            ACTION_START -> {
                startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)
                val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
                if (uri == null) {
                    CastBus.update { it.copy(phase = CastPhase.ERROR, message = getString(R.string.err_no_video)) }
                    stopSelf()
                    return START_NOT_STICKY
                }
                host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: Prefs.host(this)
                port = intent.getIntExtra(EXTRA_PORT, Prefs.port(this))
                if (host.isBlank()) {
                    CastBus.update { it.copy(phase = CastPhase.ERROR, message = getString(R.string.err_bad_address)) }
                    stopSelf()
                    return START_NOT_STICKY
                }
                stoppedByUser = false
                connectAttempt = 0
                statusSeen = false
                lastStatusAt = 0L
                startAsForeground(getString(R.string.video_preparing))
                startServing(uri)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown("服务结束")
        super.onDestroy()
    }

    private fun startServing(uri: Uri) {
        val src = LanCastFileServer.describe(this, uri)
        source = src
        val srv = LanCastFileServer(this)
        server = srv
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
        Log.i(TAG, "文件服务已启动：$playUrl（${src.size} 字节）")
        CastBus.update {
            it.copy(
                phase = CastPhase.CONNECTING,
                mode = CastMode.VIDEO,
                message = src.name,
                host = host,
                port = port,
                width = 0,
                height = 0,
                fps = 0,
                bitrateBps = 0,
            )
        }
        updateNotification(getString(R.string.video_serving, src.name))
        connect()
        startPingLoop()
    }

    private fun connect() {
        val target = url ?: return
        val c = LanCastClient(host, port)
        c.onControl = { control ->
            when (control) {
                LanCast.CTRL_READY -> {
                    connectAttempt = 0
                    CastBus.update { it.copy(phase = CastPhase.RUNNING, message = source?.name ?: "") }
                    runCatching {
                        val payload = target.toByteArray(Charsets.UTF_8)
                        c.sendFrame(LanCast.TYPE_PLAY_URL, 0, 0L, payload, 0, payload.size)
                        // 带上本机进度：接收端就绪后会直接跳到这个位置接着播
                        if (startPositionMs > 0) {
                            c.sendControl(LanCast.ACTION_SEEK, startPositionMs)
                        }
                    }.onFailure { Log.w(TAG, "下发播放地址失败", it) }
                    updateNotification(getString(R.string.video_playing, source?.name ?: ""))
                }
                LanCast.CTRL_REQUEST_KEYFRAME -> Unit
            }
        }
        c.onExtended = { type, payload ->
            if (type == LanCast.EXT_STATUS && payload.size >= 9) {
                statusSeen = true
                lastStatusAt = System.currentTimeMillis()
                CastBus.update {
                    it.copy(
                        remotePositionMs = LanCast.u32(payload, 0),
                        remoteDurationMs = LanCast.u32(payload, 4),
                        remotePlaying = (payload[8].toInt() and 1) != 0,
                        remoteBuffering = (payload[8].toInt() and 2) != 0,
                    )
                }
            }
        }
        c.onClosed = { reason -> onControlLost(reason) }
        client = c
        try {
            // 握手只用于让接收端建立会话；投视频模式没有视频帧，尺寸报 0
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
            // 控制通道断了不影响正在播放的 HTTP 流，只提示，不终止服务
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

    /** 心跳：接收端会话有 15 秒空闲超时，投视频模式本身没有帧，必须自己发 ping。 */
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
                // 接收端不再回报状态 = 它那边已经停了（自己退出/播完），发送端跟着结束
                if (statusSeen && System.currentTimeMillis() - lastStatusAt > RECEIVER_IDLE_TIMEOUT_MS) {
                    Log.i(TAG, "接收端已停止播放，结束投送")
                    lastStatusAt = System.currentTimeMillis()
                    statusSeen = false
                    CastBus.update { it.copy(phase = CastPhase.IDLE, message = "接收端已停止播放") }
                    teardown("接收端已停止播放")
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
        // 收尾顺序很重要：先把 TYPE_STOP 发出去、**留出送达时间**，再关连接和停 HTTP 服务。
        // 直接 close() 时若对端还没读走数据，TCP 可能发 RST 把数据丢掉 ——
        // 接收端就收不到"停止"，只能等 HTTP 流被掐断后由播放器报错退出。
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
                remotePositionMs = 0,
                remoteDurationMs = 0,
                remotePlaying = false,
                remoteBuffering = false,
            )
        }
        val cur = CastBus.state.value
        if (cur.phase == CastPhase.IDLE) {
            // 已经收尾过（例如 ACTION_STOP 之后 onDestroy 又调一次）：别覆盖掉提示语
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
        const val ACTION_STOP = "com.dsh.castkit.sender.STOP_VIDEO"
        const val ACTION_CTRL = "com.dsh.castkit.sender.CTRL_VIDEO"
        const val EXTRA_URI = "videoUri"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_START_POSITION = "startPositionMs"
        const val EXTRA_ACTION = "controlAction"
        const val EXTRA_VALUE = "controlValue"
        private const val NOTIFICATION_ID = 4712
        private const val MAX_RECONNECT = 5
        /** 多久没收到状态回报就认为接收端已经停止播放。 */
        private const val RECEIVER_IDLE_TIMEOUT_MS = 8000L
        /** 发完 TYPE_STOP 后留给接收端处理的宽限时间（毫秒）。 */
        private const val STOP_GRACE_MS = 350L

        private val FOREGROUND_TYPE: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }

        fun start(context: Context, uri: Uri, host: String, port: Int, startPositionMs: Long = 0L) {
            val intent = Intent(context, VideoCastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_START_POSITION, startPositionMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VideoCastService::class.java).setAction(ACTION_STOP),
            )
        }

        /** 投屏遥控（播放页调用）：action 见 LanCast.ACTION_*。 */
        fun control(context: Context, action: Int, value: Long = 0L) {
            val intent = Intent(context, VideoCastService::class.java)
                .setAction(ACTION_CTRL)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_VALUE, value)
            runCatching { context.startService(intent) }
        }
    }
}
