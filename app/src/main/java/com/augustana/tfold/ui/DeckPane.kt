package com.augustana.tfold.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.augustana.tfold.machine.FoldMachine
import com.augustana.tfold.ui.parts.WellLayout
import com.augustana.tfold.ui.parts.deckWeaveBrush
import com.augustana.tfold.ui.parts.drawWellInterior
import com.augustana.tfold.ui.parts.hairPx
import com.augustana.tfold.ui.parts.led

/**
 * 右栏：卡座 —— 复刻 viditb 概念片的展开态右屏，**一个字都没有**。
 *
 * 整栏是一块黑色机门：细密织纹、四角螺丝。门里铣出一口「读带井」：
 *  · 左缘一列刻度槽（走带标尺）
 *  · 磁带落座在井的偏左处，开口边朝右
 *  · 右侧一列读带机构 —— 磁头块、压带轮、主导轴、导带轮，空井也全部在
 *  · 空井时，两个带盘轴（六角孔）留在磁带轮毂该在的位置
 *
 * 交互只有一个物理动作：点磁带本体 = 取出，飞回磁带库。
 * 门板右上角那颗安全橙小灯 = 这盘带还没装曲目。
 */
@Composable
fun DeckPane(
    machine: FoldMachine,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val realW = constraints.maxWidth.toFloat()
        val realH = constraints.maxHeight.toFloat()
        val u = realW / DESIGN_W
        val pw = DESIGN_W
        val ph = realH / u
        val pad = PANE_PAD

        // 门板几乎铺满整栏；读带井再往里收一圈
        val doorL = pad * 0.45f
        val doorR = pw - pad * 0.45f
        val doorT = ph * 0.045f
        val doorB = ph * 0.955f
        val wellL = doorL + 7f
        val wellR = doorR - 7f
        val wellT = doorT + ph * 0.022f
        val wellB = doorB - ph * 0.022f
        val wellW = wellR - wellL
        val wellH = wellB - wellT

        // 井里那套几何（带子多大 / 落在哪 / 机构列在哪儿 / 零件多大）**只有一份**，
        // 在 `ui.parts.WellLayout` —— 外屏面板底下垫的也是它。别在这儿再摊开算一遍：
        // 曾经外屏另起一套，结果内外两块屏上同一台机器长得不一样。
        val well = WellLayout(wellL * u, wellT * u, wellW * u, wellH * u)

        // 门板织纹：一块小瓦片平铺，只建一次
        val weave = remember { deckWeaveBrush() }

        LaunchedEffect(realW, realH, well.cassX, well.cassY) {
            machine.slotRect = Rect(
                realW + well.cassX,
                well.cassY,
                realW + well.cassX + well.cassW,
                well.cassY + well.cassH,
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            drawRect(C.DoorEdge, Offset.Zero, size)
            drawDeck(machine, u, weave, doorL, doorT, doorR, doorB, well)
        }

        Box(
            Modifier
                .fillMaxSize()
                // 唯一的物理动作：点磁带本体 = 取出，飞回磁带库
                .pointerInput(machine.loadedId, machine.busy) {
                    detectTapGestures { pos ->
                        if (machine.busy) return@detectTapGestures
                        val cassette = Rect(
                            well.cassX, well.cassY,
                            well.cassX + well.cassW, well.cassY + well.cassH,
                        )
                        if (machine.loadedId != null && cassette.contains(pos)) machine.eject()
                    }
                }
        )
    }
}

// ---------------------------------------------------------------- 门板与织纹
//
// 织纹刷子（deckWeaveBrush）也搬到 `ui.parts`（Mechanism.kt）了 —— 外屏那扇磁带窗
// 没有带子时铺的是**同一块织纹**，两处各建一份瓦片纯属浪费。

/** 门板螺丝：一颗圆 + 一道一字槽。 */
private fun DrawScope.screw(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0x40000000), r * 1.15f, Offset(cx + r * 0.18f, cy + r * 0.22f))
    drawCircle(C.Screw, r, Offset(cx, cy))
    drawCircle(Color(0x66000000), r, Offset(cx, cy), style = Stroke(hairPx()))
    drawLine(
        Color(0x99000000),
        Offset(cx - r * 0.62f, cy), Offset(cx + r * 0.62f, cy),
        strokeWidth = hairPx() * 2f,
    )
    drawLine(
        Color(0x44FFFFFF),
        Offset(cx - r * 0.62f, cy + hairPx() * 1.5f), Offset(cx + r * 0.62f, cy + hairPx() * 1.5f),
        strokeWidth = hairPx(),
    )
}

// ---------------------------------------------------------------- 读带机构件
//
// 磁头块 / 压带轮 / 主导轴 / 导带轮 / 带盘轴这一套已经搬到 `ui.parts`（Mechanism.kt）——
// 外屏关着时那扇磁带窗里没有带子，露的就是同一套结构，两边必须长得一样。

// ---------------------------------------------------------------- 井

/**
 * 卡座。所有几何（doorL/cassX…）都是**设计单位**，乘 u 才是像素；
 * 但 hairPx()/density 的线宽不能乘 —— 所以坐标逐个乘 u，线宽保持绝对值。
 */
private fun DrawScope.drawDeck(
    machine: FoldMachine,
    u: Float,
    weave: Brush,
    doorL: Float,
    doorT: Float,
    doorR: Float,
    doorB: Float,
    /** 井里那套几何（含带子、机构列、刻度槽、零件尺寸）—— 与外屏面板底下垫的那层同一份。 */
    well: WellLayout,
) {
    // 每帧重绘：走带时轮毂转角在变
    @Suppress("UNUSED_EXPRESSION")
    machine.frame

    val dl = doorL * u
    val dt = doorT * u
    val dw = (doorR - doorL) * u
    val dh = (doorB - doorT) * u
    val cr = CornerRadius(6f * density)

    // 门板落影 + 门板 + 织纹
    drawRoundRect(Color(0x59000000), Offset(dl + u * 0.6f, dt + u * 0.9f), Size(dw, dh), cr)
    drawRoundRect(C.DoorBlack, Offset(dl, dt), Size(dw, dh), cr)
    drawRoundRect(weave, Offset(dl, dt), Size(dw, dh), cr)
    drawRoundRect(Color(0x24FFFFFF), Offset(dl, dt), Size(dw, dh), cr, style = Stroke(hairPx()))

    // 门板四角螺丝
    val sr = 3.2f * density
    val so = 10f * density
    screw(dl + so, dt + so, sr)
    screw(dl + dw - so, dt + so, sr)
    screw(dl + so, dt + dh - so, sr)
    screw(dl + dw - so, dt + dh - so, sr)

    // ---- 读带井 + 刻度槽 + 机构列 + 磁带：**共享实现**（内屏卡座 / 外屏面板底下同一份）
    drawWellInterior(
        L = well,
        weave = weave,
        tape = machine.loadedTape,
        tapePos = machine.tapePos,
        angleTop = machine.spinTop,
        angleBot = machine.spinBot,
        tint = machine.loadedTape?.let { machine.tintFor(it) },
        shadowOffset = u * 0.8f,
    )
    // 井口的"铣边"：上左暗、下右一线受光（这圈是**门板**的边，所以在共享实现之外）
    drawRoundRect(
        Color(0x33000000), Offset(well.l, well.t), Size(well.w, well.h),
        CornerRadius(well.corner), style = Stroke(1.5f * density),
    )
    drawRoundRect(
        Color(0x1AFFFFFF), Offset(well.l, well.t + well.h - hairPx() * 2f),
        Size(well.w, hairPx() * 2f), CornerRadius(0f),
    )

    // 「还没装曲目」警示灯：门板右上角螺丝内侧一颗安全橙小灯
    led(
        dl + dw - 15f * density, dt + 30f * density, 3.4f * density,
        on = machine.loadedTape?.let { !machine.hasAudio(it.id) } == true,
    )
}
