package com.dsh.castkit.sender.cast

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dsh.castkit.sender.CastKitApp
import com.dsh.castkit.sender.CaptureSize
import com.dsh.castkit.sender.CastConfig
import com.dsh.castkit.sender.MainActivity
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.net.LanCast
import com.dsh.castkit.sender.net.LanCastClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 投屏前台服务：MediaProjection → MediaCodec(H.264) → LANCast(TCP) → 接收端。
 *
 * Android 14+ 要求：先以 `mediaProjection` 类型进入前台，再取 MediaProjection；每次会话都要重新授权
 * （授权在 MainActivity 里通过 `createScreenCaptureIntent()` 获取）。
 */
class CastService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var client: LanCastClient? = null
    private var config: CastConfig? = null
    private var csd: ByteArray? = null
    private var stoppedByUser = false
    private val bytesSent = AtomicLong(0)
    private var displayListener: DisplayManager.DisplayListener? = null
    private var displayManager: DisplayManager? = null
    @Volatile private var currentW = 0
    @Volatile private var currentH = 0
    private val framesSent = AtomicLong(0)
    private var connectAttempt = 0

    /**
     * 编码器代次：每次重建编码器（旋转 / 出错恢复）都会 +1。
     * 编码器回调携带自己的代次，只有"当前代"的回调才允许写进 socket，
     * 避免旧编码器的迟到帧混进新分辨率的数据流里让接收端解码器错乱。
     */
    @Volatile private var encoderGeneration = 0
    private val autoRebuilds = AtomicLong(0)

    /** 屏幕尺寸变化是"抖动型"事件（旋转动画会连发多次），单线程串行 + 合并处理。 */
    private val sizeChangeWorker =
        Executors.newSingleThreadExecutor { r -> Thread(r, "cast-size-change").apply { isDaemon = true } }
    private val sizeChangePending = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 被系统停止")
            teardown("系统已停止录屏，请重新开始投屏")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedByUser = true
                teardown("已停止")
                stopSelf()
            }
            ACTION_START -> {
                val cfg = Prefs.config(this)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultCode == 0 || data == null) {
                    CastBus.update { it.copy(phase = CastPhase.ERROR, message = getString(R.string.err_no_projection)) }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (cfg.host.isBlank()) {
                    CastBus.update { it.copy(phase = CastPhase.ERROR, message = getString(R.string.err_bad_address)) }
                    stopSelf()
                    return START_NOT_STICKY
                }
                stoppedByUser = false
                config = cfg
                startAsForeground()
                startCast(resultCode, data, cfg)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown("服务结束")
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification("准备中…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCast(resultCode: Int, data: Intent, cfg: CastConfig) {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Throwable) {
            Log.e(TAG, "getMediaProjection 失败", e)
            null
        }
        if (proj == null) {
            fail(getString(R.string.err_no_projection))
            return
        }
        projection = proj
        proj.registerCallback(projectionCallback, null)

        config = cfg
        csd = null
        bytesSent.set(0)
        framesSent.set(0)
        connectAttempt = 0
        autoRebuilds.set(0)
        CastBus.update {
            it.copy(
                phase = CastPhase.CONNECTING,
                mode = CastMode.MIRROR,
                message = "",
                host = cfg.host,
                port = cfg.port,
                width = cfg.width,
                height = cfg.height,
                fps = cfg.fps,
                bitrateBps = cfg.bitrateBps,
            )
        }

        val enc = newEncoder(cfg)

        var activeEncoder = enc
        try {
            activeEncoder.start()
        } catch (e: Throwable) {
            // 编码器不接受该尺寸时，退回屏幕原始尺寸再试一次（比例仍正确）
            Log.w(TAG, "编码器启动失败，回退屏幕尺寸重试: ${e.message}")
            val (sw, sh) = Prefs.nativeSize(this)
            val fallback = CastConfig(
                width = CaptureSize.align16(sw).coerceAtLeast(240),
                height = CaptureSize.align16(sh).coerceAtLeast(240),
                fps = cfg.fps,
                bitrateBps = cfg.bitrateBps,
                host = cfg.host,
                port = cfg.port,
                audio = cfg.audio,
                sizeNote = "已回退到屏幕尺寸",
            )
            val retry = newEncoder(fallback)
            try {
                retry.start()
                activeEncoder = retry
                config = fallback
            } catch (e2: Throwable) {
                Log.e(TAG, "编码器启动失败（回退后仍失败）", e2)
                fail(e2.message ?: "编码器启动失败")
                return
            }
        }
        encoder = activeEncoder
        val effective = config ?: cfg
        currentW = effective.width
        currentH = effective.height

        virtualDisplay = proj.createVirtualDisplay(
            "CastKitSender",
            effective.width,
            effective.height,
            DEFAULT_DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            activeEncoder.inputSurface(),
            null,
            null,
        )

        startDisplayListener()
        // 用 effective（可能已按屏幕尺寸回退）而不是原始 cfg：否则握手里报的尺寸和真实流不一致，
        // 接收端会按错误尺寸建解码器
        connect(effective)
        startStatsLoop()
    }

    /**
     * 连接入口：**永远**派发到后台线程。
     *
     * `Socket.connect` 是阻塞调用，而本方法的上游 [startCast] 是从 `onStartCommand`
     * 进来的，也就是**主线程**。这里曾经直接同步连接，于是必然抛
     * `NetworkOnMainThreadException`，再被下面的 catch 当成"连接丢失"交给
     * [onConnectionLost] 的重连线程——1 秒后才真正连上。
     *
     * 功能上属于"歪打正着"，代价有三个：日志里一句假的"连接失败"、
     * UI 白闪一下"第 1 次重连"、以及测速窗口里空转的一秒
     * （状态卡上 `实测 0.0 Mbps` 的来源之一）。更糟的是它每次投屏都会
     * 白白消耗一格重连预算。
     */
    private fun connect(cfg: CastConfig) {
        Thread({ connectBlocking(cfg) }, "lancast-connect").apply { isDaemon = true }.start()
    }

    /** 真正的阻塞连接逻辑。**只能在后台线程调用。** */
    private fun connectBlocking(cfg: CastConfig) {
        val c = LanCastClient(cfg.host, cfg.port)
        c.onControl = { control ->
            when (control) {
                LanCast.CTRL_READY -> {
                    connectAttempt = 0
                    CastBus.update { it.copy(phase = CastPhase.RUNNING, message = "") }
                    csd?.let { csdBytes ->
                        runCatching { c.sendFrame(LanCast.TYPE_VIDEO_CSD, 0, 0L, csdBytes, 0, csdBytes.size) }
                    }
                }
                LanCast.CTRL_REQUEST_KEYFRAME -> encoder?.requestKeyFrame()
            }
        }
        c.onClosed = { reason -> onConnectionLost(reason) }
        client = c
        try {
            c.connect(LanCast.handshake(cfg.width, cfg.height, cfg.fps, cfg.bitrateBps, cfg.audio, null))
        } catch (e: Throwable) {
            Log.w(TAG, "连接失败", e)
            onConnectionLost(e.message ?: "连接失败")
        }
    }

    private fun onConnectionLost(reason: String) {
        if (stoppedByUser || !isCasting()) return
        client?.close()
        client = null
        if (connectAttempt >= MAX_RECONNECT) {
            CastBus.update {
                it.copy(phase = CastPhase.ERROR, message = getString(R.string.err_connect_failed, reason))
            }
            teardown("重连失败")
            stopSelf()
            return
        }
        connectAttempt++
        CastBus.update {
            it.copy(phase = CastPhase.RECONNECTING, message = "第 $connectAttempt 次重连（$reason）")
        }
        val delayMs = 1000L shl (connectAttempt - 1)
        Thread {
            Thread.sleep(delayMs)
            val cfg = config
            if (!stoppedByUser && cfg != null && isCasting()) {
                // 已经在后台线程上，直接走阻塞版本，不必再派发一次
                connectBlocking(cfg)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startStatsLoop() {
        Thread {
            var lastBytes = 0L
            var lastFrames = 0L
            var lastTime = System.currentTimeMillis()
            var idleMs = 0L
            while (!stoppedByUser && isCasting()) {
                Thread.sleep(1000)
                val now = System.currentTimeMillis()
                val dt = (now - lastTime).coerceAtLeast(1)
                val bytes = bytesSent.get()
                val frames = framesSent.get()
                val kbps = ((bytes - lastBytes) * 8 / dt).toInt()
                val fps = (frames - lastFrames) * 1000f / dt
                // 重建编码器/屏幕静止时可能长时间没有帧，补心跳，避免接收端按空闲超时断开
                if (bytes == lastBytes) {
                    idleMs += dt
                    if (idleMs >= LanCast.PING_INTERVAL_MS) {
                        idleMs = 0
                        runCatching { client?.takeIf { it.isConnected() }?.sendPing() }
                    }
                } else {
                    idleMs = 0
                }
                lastBytes = bytes
                lastFrames = frames
                lastTime = now
                CastBus.update { it.copy(measuredKbps = kbps, measuredFps = fps) }
                updateNotification(kbps, fps)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun isCasting(): Boolean = encoder != null || virtualDisplay != null || projection != null

    /** 监听屏幕尺寸/方向变化：交给串行合并的 [scheduleScreenSizeChange] 处理。 */
    private fun startDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        displayManager = dm
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                scheduleScreenSizeChange(force = false)
            }
        }
        dm.registerDisplayListener(listener, null)
        displayListener = listener
    }

    private fun stopDisplayListener() {
        displayListener?.let { runCatching { displayManager?.unregisterDisplayListener(it) } }
        displayListener = null
        displayManager = null
    }

    /**
     * 合并屏幕变化事件后处理一次：旋转动画期间 [DisplayManager.DisplayListener] 会连发多次，
     * 每次都重建编码器既浪费又容易互相踩踏，这里用一个短延时把它们并成一次。
     */
    private fun scheduleScreenSizeChange(force: Boolean) {
        if (stoppedByUser) return
        if (!force && !sizeChangePending.compareAndSet(false, true)) return
        sizeChangeWorker.execute {
            try {
                if (!force) Thread.sleep(SCREEN_CHANGE_DEBOUNCE_MS)
                applyScreenSizeChange(force)
            } catch (t: Throwable) {
                Log.w(TAG, "处理屏幕变化失败", t)
            } finally {
                sizeChangePending.set(false)
            }
        }
    }

    /** 构造一个带代次的编码器：只有当前代的回调会真正写进 socket。 */
    private fun newEncoder(cfg: CastConfig): ScreenEncoder {
        val gen = ++encoderGeneration
        Log.i(TAG, "创建编码器 gen=$gen ${cfg.width}x${cfg.height}@${cfg.fps}")
        return ScreenEncoder(
            config = cfg,
            onCsd = { csdBytes, w, h -> onEncoderCsd(gen, csdBytes, w, h) },
            onFrame = { data, offset, length, ptsUs, keyframe ->
                onEncoderFrame(gen, data, offset, length, ptsUs, keyframe)
            },
            onError = { msg -> onEncoderError(gen, msg) },
        )
    }

    /**
     * 屏幕比例/方向变化：**绝不因此结束投屏**。
     *
     * 顺序很关键——先把新编码器的输入面挂到 VirtualDisplay 上，确认成功后才释放旧编码器；
     * 任何一步失败都回滚到旧链路继续投屏，只给用户一条提示。旧实现是"先杀旧编码器 → resize
     * → 挂新面"，中间那段时间 VirtualDisplay 指向一个已释放的 Surface，失败还会 teardown 整场会话
     * （实测表现就是横竖屏一切换投屏直接断掉）。
     */
    @Synchronized
    private fun applyScreenSizeChange(force: Boolean) {
        if (stoppedByUser) return
        // 本地播放器全屏时用户可能在用「旋转本机」按钮看片：那是本机行为，不该带动接收端画面
        if (CastBus.state.value.localPlayerActive) return
        val vd = virtualDisplay ?: return
        val old = config ?: return
        val fresh = try {
            Prefs.config(this)
        } catch (t: Throwable) {
            Log.w(TAG, "读取新尺寸失败", t)
            return
        }
        if (fresh.width <= 0 || fresh.height <= 0) return

        val orientationChanged = old.portrait != fresh.portrait
        val aspectChanged = kotlin.math.abs(old.aspect - fresh.aspect) > 0.01f
        val sizeChanged = old.width != fresh.width || old.height != fresh.height
        if (!force && !orientationChanged && !aspectChanged && !sizeChanged) return

        val oldEncoder = encoder
        val oldGen = encoderGeneration
        Log.i(TAG, "屏幕变化：${old.width}x${old.height} -> ${fresh.width}x${fresh.height}（强制=$force）")

        // 1) 先备好新编码器（此时旧链路仍在正常工作）
        val newEncoder = newEncoder(fresh)
        val newGen = encoderGeneration
        try {
            newEncoder.start()
        } catch (t: Throwable) {
            Log.w(TAG, "重建编码器失败，继续用原分辨率", t)
            encoderGeneration = oldGen
            runCatching { newEncoder.stop() }
            warn("方向变化后重建编码器失败，继续以原分辨率投屏")
            return
        }

        // 2) 切换 VirtualDisplay：先 resize，再挂新面
        try {
            vd.resize(fresh.width, fresh.height, DEFAULT_DPI)
            vd.surface = newEncoder.inputSurface()
        } catch (t: Throwable) {
            Log.w(TAG, "切换 VirtualDisplay 失败，回滚到原链路", t)
            encoderGeneration = oldGen
            runCatching { vd.resize(old.width, old.height, DEFAULT_DPI) }
            runCatching { oldEncoder?.let { vd.surface = it.inputSurface() } }
            runCatching { newEncoder.stop() }
            warn("方向变化适配失败（${t.javaClass.simpleName}），继续以原分辨率投屏")
            return
        }

        // 3) 切换成功，才收掉旧编码器
        encoder = newEncoder
        config = fresh
        autoRebuilds.set(0)
        runCatching { oldEncoder?.stop() }
        CastBus.update {
            it.copy(width = fresh.width, height = fresh.height, fps = fresh.fps, message = "")
        }
        updateNotification(CastBus.state.value.measuredKbps, CastBus.state.value.measuredFps)
        Log.i(TAG, "方向变化完成：${fresh.width}x${fresh.height}（新代次 gen=$newGen）")
    }

    /** 非致命告警：只提示，不结束会话。 */
    private fun warn(msg: String) {
        Log.w(TAG, msg)
        CastBus.update { it.copy(message = msg) }
        runCatching {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun onEncoderError(gen: Int, msg: String) {
        if (stoppedByUser) return
        if (gen != encoderGeneration) return
        Log.e(TAG, "编码器错误 gen=$gen: $msg")
        if (autoRebuilds.incrementAndGet() > MAX_AUTO_REBUILD) {
            CastBus.update { it.copy(message = "编码器反复出错：$msg") }
            return
        }
        CastBus.update { it.copy(message = "编码器出错，正在恢复…") }
        // 复用同一条重建路径，尺寸没变也要重建
        scheduleScreenSizeChange(force = true)
    }

    /** CSD 回调：尺寸变化先发 TYPE_RESIZE，再发 CSD（接收端据此重建解码器）。 */
    private fun onEncoderCsd(gen: Int, csdBytes: ByteArray, w: Int, h: Int) {
        if (gen != encoderGeneration) return
        csd = csdBytes
        val c = client ?: return
        if (!c.isConnected()) return
        runCatching {
            if (w > 0 && h > 0 && (w != currentW || h != currentH)) {
                val payload = LanCast.resizePayload(w, h)
                c.sendFrame(LanCast.TYPE_RESIZE, 0, 0L, payload, 0, payload.size)
                currentW = w
                currentH = h
            }
            c.sendFrame(LanCast.TYPE_VIDEO_CSD, 0, 0L, csdBytes, 0, csdBytes.size)
        }.onFailure { Log.w(TAG, "发送 CSD 失败", it) }
    }

    /** 视频帧回调。 */
    private fun onEncoderFrame(
        gen: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
        ptsUs: Long,
        keyframe: Boolean,
    ) {
        if (gen != encoderGeneration) return
        val c = client ?: return
        if (!c.isConnected()) return
        val flags = if (keyframe) LanCast.FLAG_KEYFRAME else 0
        try {
            c.sendFrame(LanCast.TYPE_VIDEO_AU, flags, ptsUs, data, offset, length)
            bytesSent.addAndGet(length.toLong())
            framesSent.incrementAndGet()
        } catch (e: Throwable) {
            Log.w(TAG, "发送视频帧失败", e)
        }
    }

    private fun fail(msg: String) {
        CastBus.update { it.copy(phase = CastPhase.ERROR, message = msg) }
        teardown(msg)
        stopSelf()
    }

    private fun teardown(reason: String) {
        stoppedByUser = stoppedByUser || reason == "已停止"
        stopDisplayListener()
        runCatching { encoder?.stop() }
        encoder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { client?.close() }
        client = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        csd = null
        val cur = CastBus.state.value
        if (cur.phase != CastPhase.ERROR) {
            CastBus.update { it.copy(phase = CastPhase.IDLE, message = reason, measuredKbps = 0, measuredFps = 0f) }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CastKitApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(kbps: Int, fps: Float) {
        val cfg = config ?: return
        val text = getString(
            R.string.notification_text,
            cfg.host,
            cfg.width,
            cfg.height,
            "${kbps / 1000}.${(kbps % 1000) / 100}Mbps ${"%.0f".format(fps)}fps",
        )
        val mgr = getSystemService(android.app.NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "CastService"
        const val ACTION_START = "com.dsh.castkit.sender.START"
        const val ACTION_STOP = "com.dsh.castkit.sender.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val NOTIFICATION_ID = 4711
        private const val DEFAULT_DPI = 320
        private const val MAX_RECONNECT = 3
        /** 旋转事件合并窗口（旋转动画会连发多次 onDisplayChanged）。 */
        private const val SCREEN_CHANGE_DEBOUNCE_MS = 350L
        /** 单次投屏里编码器自动重建的次数上限，防止出错后无限重建。 */
        private const val MAX_AUTO_REBUILD = 5

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CastService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
