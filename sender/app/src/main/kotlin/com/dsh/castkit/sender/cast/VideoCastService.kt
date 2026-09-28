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
import java.util.concurrent.Executors

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

    /**
     * 换集串行队列。
     *
     * 一是**顺序**：连点两次「下一个」必须按点击顺序下发，否则接收端可能停在上一集；
     * 二是**别在主线程写 socket**：`onStartCommand` 在主线程，而 `sendFrame` 是直接 `Socket.write`
     * （接收端那边就因此踩过 `NetworkOnMainThreadException`，这里不给它机会）。
     */
    private val switchWorker =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lancast-switch").apply { isDaemon = true } }

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
            ACTION_SWITCH -> {
                // 投送中换集：**不结束会话**，只在同一个会话里让接收端换片。
                // 走单线程队列串行处理：连点两次「下一个」时，发送顺序必须和点击顺序一致。
                val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
                if (uri == null) return START_NOT_STICKY
                switchWorker.execute { switchServing(uri) }
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
                // 新一次投送：把上一次留下的接收端进度清掉，否则连上之前的这一两秒里
                // 进度条会先显示上一部片子的位置（收尾时故意保留它，见 teardown）
                CastBus.update {
                    it.copy(
                        remotePositionMs = 0,
                        remoteDurationMs = 0,
                        remotePlaying = false,
                        remoteBuffering = false,
                    )
                }
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
        // 这里**刻意不做**播放前预检。
        //
        // 曾经用 PlayabilityChecker 按「本机有没有解码器」拦截投送，接收端集成 FFmpeg 之后
        // 这个判断就是错的了：发送端硬件解不了的格式（MPEG-2/PS、隔行片源等），接收端靠软解
        // 照样能播 —— 用发送端的能力去否决接收端，会把本来能投的片子挡在门外。
        // 投视频这条链路发送端只做字节转发、不解码，所以能力判断应该完全交给接收端；
        // 真投不了时接收端会给出具体原因（容器不认识 / 编码解不了）。
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

    /**
     * 投送中换集：在**同一个会话**里让接收端改播另一个文件。
     *
     * 与 [startServing] 的区别是「什么都不重来」：不重建 HTTP 服务（只换它服务的那个文件）、
     * 不重连控制通道、不动心跳、不改 `phase`。接收端收到第二帧 `type=6` 就会释放当前播放器、
     * 播新地址（`LanVideoPlayer.play()` 内部先 `releaseInternal()`），会话本身一直活着。
     *
     * **`phase` 必须保持 RUNNING/CONNECTING**：播放页把「投视频 + RUNNING」当作"我在投送、
     * 本机是遥控器"，一旦这里改成 CONNECTING，播放页的接管逻辑会判定"投送结束"，把本机
     * seek 到当前位置并开始播放 —— 手机就会跟着出声（不是我们要的）。
     */
    private fun switchServing(uri: Uri) {
        // 排队期间用户可能已经停了投送：这时**什么都不能做**，否则会把会话又开起来
        if (stoppedByUser) return
        val srv = server
        if (srv == null || !srv.isRunning()) {
            // 不在投送中（或文件服务已经收了）：退化成一次正常的开投
            Log.i(TAG, "换集时没有在跑的投送，按新开投处理")
            startPositionMs = 0L
            startAsForeground(getString(R.string.video_preparing))
            startServing(uri)
            return
        }
        // 与开投一致：**不做播放前预检**，能力判断交给接收端（它解不了会给出具体原因）
        val src = LanCastFileServer.describe(this, uri)
        val playUrl = srv.switchSource(src)
        if (playUrl == null) {
            Log.w(TAG, "换集失败：拿不到新的播放地址，继续投送原文件")
            return
        }
        source = src
        url = playUrl
        startPositionMs = 0L
        // 换集那一瞬间接收端还没回报，刷新时间戳，免得 8 秒"接收端已停止"判定误伤
        if (statusSeen) lastStatusAt = System.currentTimeMillis()
        CastBus.update {
            it.copy(
                message = src.name,
                remotePositionMs = 0,
                remoteDurationMs = 0,
                remotePlaying = false,
                remoteBuffering = true,
            )
        }
        updateNotification(getString(R.string.video_playing, src.name))
        val c = client
        if (c == null || !c.isConnected()) {
            // 控制通道断着：不在这里重连。重连成功后 CTRL_READY 会按**当前 url** 重新下发地址
            Log.i(TAG, "换集时控制通道未连接，等重连后下发新地址：$playUrl")
            return
        }
        runCatching {
            val payload = playUrl.toByteArray(Charsets.UTF_8)
            c.sendFrame(LanCast.TYPE_PLAY_URL, 0, 0L, payload, 0, payload.size)
        }.onFailure { Log.w(TAG, "换集下发播放地址失败", it) }
        Log.i(TAG, "已换集：${src.name}（$playUrl）")
    }

    private fun connect() {
        if (url == null) return
        val c = LanCastClient(host, port)
        c.onControl = { control ->
            when (control) {
                LanCast.CTRL_READY -> {
                    connectAttempt = 0
                    // **读当前的 url，不用 connect 时捕获的值**：投送中换过集的话，
                    // 这里必须是新地址（否则重连会把接收端拉回上一集）。
                    val target = url
                    if (target != null) {
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
                }
                LanCast.CTRL_REQUEST_KEYFRAME -> Unit
            }
        }
        c.onExtended = { type, payload ->
            when {
                type == LanCast.EXT_STATUS && payload.size >= 9 -> {
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
                // 接收端用户自己按了「断开投屏」：立刻收尾，别等 8 秒的状态超时
                type == LanCast.EXT_STOP -> {
                    Log.i(TAG, "接收端主动断开投送")
                    teardown("接收端已断开投屏")
                    stopSelf()
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
        // **不要清 remotePositionMs / remoteDurationMs**：播放页要靠"最后一次收到的接收端进度"
        // 把本机进度对齐过去（`LaunchedEffect(remote)` 里 `vm.seekTo(remotePositionMs)`）。
        // 这里清零过一次，结果所有收尾路径（点停止投送、接收端主动断开、8 秒超时）都变成
        // "投送结束回到片头重放" —— 接收端明明停在 12 分钟，手机上却从 0 开始。
        CastBus.update {
            it.copy(
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
        const val ACTION_SWITCH = "com.dsh.castkit.sender.SWITCH_VIDEO"
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

        /**
         * 投送中换集：把正在投的那个文件换成另一个，**会话不重来**（接收端在同一个会话里改播新地址）。
         * 没有在投送时会退化成一次正常的开投。
         */
        fun switchTo(context: Context, uri: Uri) {
            val intent = Intent(context, VideoCastService::class.java)
                .setAction(ACTION_SWITCH)
                .putExtra(EXTRA_URI, uri.toString())
            runCatching { context.startService(intent) }
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
