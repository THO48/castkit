package com.dsh.castkit.sender.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 主线程卡顿看门狗（**只在 debug 包里启**）。
 *
 * 为什么需要它：**帧统计看不见主线程阻塞** —— `dumpsys gfxinfo` 的直方图只统计"渲染出来的帧"，
 * 主线程被 `release()`、`stop()` 这类 native 调用钉住 2 秒时，那 2 秒里一帧都没渲染，
 * 直方图上一个长帧都不会有，看起来一切正常。用户感受到的却是"画面在但点不动"。
 *
 * 做法：后台线程每 [INTERVAL_MS] 往主线程 post 一个空任务，量它多久才被执行。
 * 超过 [WARN_MS] 就记一条；超过 [DUMP_MS] 直接把主线程当时的 Java 调用栈打出来 ——
 * 阻塞点在哪一行一目了然。
 */
object MainThreadWatchdog {

    private const val TAG = "MainBlock"
    private const val INTERVAL_MS = 150L
    private const val WARN_MS = 400L
    private const val DUMP_MS = 1500L

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({ loop() }, "main-thread-watchdog").apply { isDaemon = true }.start()
    }

    private fun loop() {
        val main = Handler(Looper.getMainLooper())
        while (true) {
            val t0 = SystemClock.uptimeMillis()
            val latch = CountDownLatch(1)
            main.post { latch.countDown() }
            val done = runCatching { latch.await(DUMP_MS + 3000, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            val cost = SystemClock.uptimeMillis() - t0
            when {
                !done -> {
                    // 超过 4.5 秒还没轮到我们：直接抓栈
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .joinToString("\n") { "    at $it" }
                    Log.w(TAG, "主线程卡死 >${DUMP_MS + 3000}ms，当时的栈：\n$stack")
                    Thread.sleep(1000)
                }
                cost >= DUMP_MS -> {
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .joinToString("\n") { "    at $it" }
                    Log.w(TAG, "主线程卡了 ${cost}ms，当时的栈：\n$stack")
                }
                cost >= WARN_MS -> Log.w(TAG, "主线程卡了 ${cost}ms")
            }
            Thread.sleep(INTERVAL_MS)
        }
    }
}
