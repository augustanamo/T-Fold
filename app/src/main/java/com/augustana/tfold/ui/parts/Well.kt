package com.augustana.tfold.ui.parts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.augustana.tfold.model.Tape

/**
 * 卡座「井」里的一套几何 —— **只有这一份**。
 *
 * 内屏卡座（DeckPane）和外屏面板底下垫着的那层卡座，必须是同一台机器的同一个井：
 * 内外屏的比例要一模一样，合上盖子只是"给同一个卡座加了块开了洞的面板"。
 * 曾经外屏那边另起了一套按窗宽排布的算法（机构贴窗左缘、零件按窗宽定尺寸），
 * 结果内外两块屏上同一台机器长得完全不一样 —— 所以几何统一到这里，
 * 带子尺寸、机构列位置、刻度槽位置、零件大小全部由井的尺寸推。
 *
 * 全部是 px。比例复刻内屏卡座的设计值（井宽 165 设计单位、带子 0.72 井宽、
 * 带心 0.40 井宽、机构列 0.80 井宽、刻度槽 0.075 井宽）。
 */
class WellLayout(val l: Float, val t: Float, val w: Float, val h: Float) {
    /** 带子：井宽的 0.72；高度上限 0.86 井高（1.56 的宽高比下总是宽度先卡住）。 */
    val cassW = minOf(w * 0.72f, h * 0.86f / CASSETTE_H_RATIO)
    val cassH = cassW * CASSETTE_H_RATIO

    /** 落在井内偏左 —— 开口边朝右，正对机构列。 */
    val cassX = l + w * 0.40f - cassW / 2f
    val cassY = t + (h - cassH) / 2f

    /** 机构列（井内右侧）与左缘刻度槽。 */
    val mechX = l + w * 0.80f
    val scaleX = l + w * 0.075f

    /**
     * 零件尺寸一律按**带宽**定标。
     *
     * 内屏那套用的是绝对 dp（磁头 7dp / 压带轮 5.5dp / 主导轴 3dp / 导带轮 4.5dp），
     * 而内屏带宽 738px —— 折算过来就是下面这几个比例。按带宽走，井一大一小零件也跟着走，
     * 两边才永远是同样的机器。
     */
    val headS = cassW * 0.0249f
    val rollerR = cassW * 0.0196f
    val capR = cassW * 0.0107f
    val guideR = cassW * 0.0160f
    val hubRingR = cassW * HUB_R * 1.5f
    val tickMajor = cassW * 0.0178f
    val tickMinor = cassW * 0.0093f
    val corner = cassW * 0.0142f

    companion object {
        /**
         * 井的尺寸由**屏**定（不是由某个洞口定），带子中心落在 [casCx],[casCy]。
         *
         * 外屏那块面板上的洞只是"开了个口"，它裁剪井、不该决定井多大 ——
         * 井按屏尺寸摆好以后，从洞里看到的才是**同一台机器上同一个卡座的中间一块**。
         * 反过来拿洞去定井的尺寸（井 = 洞），等于整台机器跟着洞缩了一圈，
         * 内屏那盘带 738px、外屏就成了 660px —— 合盖不该改变机器。
         */
        fun centred(wellW: Float, wellH: Float, casCx: Float, casCy: Float): WellLayout {
            val probe = WellLayout(0f, 0f, wellW, wellH)
            return WellLayout(
                casCx - (probe.cassX + probe.cassW / 2f),
                casCy - (probe.cassY + probe.cassH / 2f),
                wellW,
                wellH,
            )
        }
    }
}

/**
 * 画井的内部：井底织纹 + 凹下去的落影 + 左缘刻度槽 + 右侧机构列 + 磁带（或空井的两个带盘轴）。
 *
 * 只画井；井口那一圈"铣边"由调用方在[画完覆盖在上面的面板之后]补 ——
 * 内屏卡座外面是门板，外屏外面是那块开了洞的面板，两者的边缘处理不是一回事。
 *
 * @param tape null = 空井，两个带盘轴留在轮毂位置
 * @param shadowOffset 带子在井里的落影偏移（内屏用的是一点点右下的偏移，0 就不画影）
 */
fun DrawScope.drawWellInterior(
    L: WellLayout,
    weave: Brush,
    tape: Tape?,
    tapePos: Float,
    angleTop: Float,
    angleBot: Float,
    tint: Color?,
    shadowOffset: Float = 0f,
) {
    val cr = CornerRadius(L.corner)
    val topLeft = Offset(L.l, L.t)
    val size = Size(L.w, L.h)

    // ---- 井底：全机唯一的深色块 + 织纹
    drawRoundRect(Color(0xFF0B0B0B), topLeft, size, cr)
    drawRoundRect(weave, topLeft, size, cr)
    // ---- 井是"铣下去"的：顶边一道落影、两侧各一道、底边一线受光
    drawRect(
        Brush.verticalGradient(0f to Color(0x59000000), 1f to Color.Transparent),
        topLeft, Size(L.w, L.h * 0.10f),
    )
    drawRect(
        Brush.horizontalGradient(0f to Color(0x33000000), 1f to Color.Transparent),
        topLeft, Size(L.w * 0.06f, L.h),
    )
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, 1f to Color(0x33000000)),
        Offset(L.l + L.w * 0.94f, L.t), Size(L.w * 0.06f, L.h),
    )
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x1FFFFFFF)),
        Offset(L.l, L.t + L.h * 0.96f), Size(L.w, L.h * 0.04f),
    )

    // ---- 左缘刻度槽（走带标尺，无字）
    val tickTop = L.t + L.h * 0.12f
    val tickBot = L.t + L.h * 0.88f
    drawLine(Color(0x2EFFFFFF), Offset(L.scaleX, tickTop), Offset(L.scaleX, tickBot), strokeWidth = hairPx())
    for (i in 0..14) {
        val ty = tickTop + (tickBot - tickTop) * i / 14
        val major = i % 4 == 0
        val len = if (major) L.tickMajor else L.tickMinor
        drawLine(
            Color(if (major) 0x59FFFFFF else 0x2EFFFFFF),
            Offset(L.scaleX, ty), Offset(L.scaleX + len, ty),
            strokeWidth = hairPx(),
        )
    }

    // ---- 右侧读带机构列（空井也在 —— 它就是这台机器的"读带结构"）
    val mx = L.mechX
    headBlock(mx + L.headS * 0.9f, L.t + L.h * 0.185f, L.headS)
    pinchRoller(mx - L.rollerR * 0.6f, L.t + L.h * 0.235f, L.rollerR)
    capstan(mx + L.capR * 1.9f, L.t + L.h * 0.245f, L.capR)
    guideRing(mx, L.t + L.h * 0.36f, L.guideR)
    guideRing(mx, L.t + L.h * 0.64f, L.guideR)
    capstan(mx + L.capR * 1.9f, L.t + L.h * 0.755f, L.capR)
    headBlock(mx + L.headS * 0.9f, L.t + L.h * 0.815f, L.headS)

    // ---- 磁带 / 空井
    if (tape != null) {
        if (shadowOffset > 0f) {
            drawRoundRect(
                Color(0x66000000),
                Offset(L.cassX + shadowOffset * 0.5f, L.cassY + shadowOffset * 0.8f),
                Size(L.cassW, L.cassH), CornerRadius(L.cassW * 0.04f),
            )
        }
        // 水平镜像：让磁带的开口边朝右，对着读带机构（pivot 必须显式给磁带中心，
        // 默认 pivot 是整块画布的 center，会把磁带翻到画面外）
        translate(L.cassX, L.cassY) {
            scale(-1f, 1f, pivot = Offset(L.cassW / 2f, L.cassH / 2f)) {
                drawCassette(
                    w = L.cassW,
                    h = L.cassH,
                    tape = tape,
                    tapePos = tapePos,
                    angleTop = angleTop,
                    angleBot = angleBot,
                    tint = tint,
                )
            }
        }
    } else {
        reelTable(L.cassX + L.cassW / 2f, L.cassY + L.cassH * HUB_V_TOP, L.hubRingR)
        reelTable(L.cassX + L.cassW / 2f, L.cassY + L.cassH * HUB_V_BOT, L.hubRingR)
    }
}
