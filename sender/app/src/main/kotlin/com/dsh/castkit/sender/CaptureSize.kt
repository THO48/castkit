package com.dsh.castkit.sender

/**
 * 采集尺寸计算：**保持屏幕比例**，避免把非 16:9 的手机屏幕拉伸成 16:9。
 *
 * 语义（与设置页一致）：
 *  - 预设（720p/1080p/1440p）= **短边像素**，长边按屏幕长宽比推算；
 *  - `跟随本机` = 屏幕原始尺寸（对齐后）；
 *  - **关闭「保持屏幕比例」时**，预设按字面 16:9（兼容"必须是 1280×720"的用法，
 *    画面会被拉伸）——所以这个开关对**所有档位**都生效，不只是曾经的"自定义"。
 *
 * 尺寸都会做 16 对齐（H.264 编码器普遍要求），不放大超过屏幕，能力不支持时逐级回退。
 *
 * 注：`自定义` 档位已在本次重构中移除（设计系统 §10 偏差 7），因此这里不再有
 * `customW` / `customH` 两个参数。
 */
object CaptureSize {

    const val ALIGN = 16
    private const val MIN_DIM = 240

    data class Result(
        val width: Int,
        val height: Int,
        /** 非空表示发生了夹取/回退，用于 UI 提示。 */
        val note: String? = null,
    )

    fun align16(v: Int): Int = (v / ALIGN) * ALIGN

    fun compute(
        screenW: Int,
        screenH: Int,
        preset: ResolutionPreset,
        keepAspect: Boolean,
        isSupported: ((Int, Int) -> Boolean)? = null,
    ): Result {
        val sw = screenW.coerceAtLeast(MIN_DIM)
        val sh = screenH.coerceAtLeast(MIN_DIM)
        val shortSide = minOf(sw, sh)
        val longSide = maxOf(sw, sh)
        val portrait = sh >= sw

        var note: String? = null

        // 1) 先得到"目标短边/长边"
        var targetShort: Int
        var targetLong: Int
        if (!keepAspect) {
            // 字面尺寸：预设按标准 16:9，跟随本机按屏幕原始尺寸
            val w = when (preset) {
                ResolutionPreset.NATIVE -> sw
                else -> preset.shortSide ?: 720
            }
            val h = when (preset) {
                ResolutionPreset.NATIVE -> sh
                else -> preset.landscapeHeight ?: 720
            }
            val (aw, ah) = sanitize(w, h, isSupported).also { if (it.note != null) note = it.note }
            return Result(aw, ah, note)
        }

        val requestedShort = when (preset) {
            ResolutionPreset.NATIVE -> shortSide
            else -> preset.shortSide ?: 720
        }

        // 不放大：短边不超过屏幕短边
        targetShort = requestedShort.coerceAtMost(shortSide)
        if (targetShort != requestedShort) {
            note = "已限制为屏幕尺寸"
        }
        // 长边按屏幕长宽比推算，并对齐/不超过屏幕长边
        targetLong = align16((targetShort.toLong() * longSide / shortSide).toInt())
        if (targetLong > longSide) targetLong = align16(longSide)
        if (targetLong < targetShort) targetLong = targetShort

        var w = if (portrait) targetShort else targetLong
        var h = if (portrait) targetLong else targetShort
        w = align16(w).coerceAtLeast(MIN_DIM)
        h = align16(h).coerceAtLeast(MIN_DIM)

        // 2) 编码器能力回退：按 16 递减短边，保持比例
        if (isSupported != null && !isSupported(w, h)) {
            val ratio = longSide.toDouble() / shortSide.toDouble()
            var s = targetShort
            var ok = false
            while (s > MIN_DIM) {
                s -= ALIGN
                val l = align16((s * ratio).toInt()).coerceAtMost(align16(longSide)).coerceAtLeast(s)
                val cw = if (portrait) s else l
                val ch = if (portrait) l else s
                if (isSupported(cw, ch)) {
                    w = cw; h = ch; ok = true
                    break
                }
            }
            if (!ok) {
                // 最后回退到屏幕原始尺寸（对齐后）
                w = align16(sw).coerceAtLeast(MIN_DIM)
                h = align16(sh).coerceAtLeast(MIN_DIM)
                note = "编码器不支持所选尺寸，已回退到屏幕尺寸"
            } else {
                note = "已按编码器能力下调"
            }
        }

        return Result(w, h, note)
    }

    /** 字面尺寸的清理：16 对齐 + 能力回退（不保持比例）。 */
    private fun sanitize(
        wIn: Int,
        hIn: Int,
        isSupported: ((Int, Int) -> Boolean)?,
    ): Result {
        var w = align16(wIn).coerceAtLeast(MIN_DIM)
        var h = align16(hIn).coerceAtLeast(MIN_DIM)
        var note: String? = null
        if (isSupported != null && !isSupported(w, h)) {
            var ok = false
            var tw = w
            while (tw > MIN_DIM) {
                tw -= ALIGN
                var th = h
                while (th > MIN_DIM) {
                    th -= ALIGN
                    if (isSupported(tw, th)) {
                        w = tw; h = th; ok = true
                        break
                    }
                }
                if (ok) break
            }
            note = if (ok) "已按编码器能力下调" else "编码器不支持所选尺寸"
        }
        return Result(w, h, note)
    }
}
