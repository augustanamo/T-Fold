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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import com.augustana.tfold.machine.DuoMachine
import com.augustana.tfold.ui.parts.CASSETTE_H_RATIO
import com.augustana.tfold.ui.parts.drawCassette
import com.augustana.tfold.ui.parts.hairPx
import com.augustana.tfold.ui.parts.led
import kotlin.math.cos
import kotlin.math.sin

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
    machine: DuoMachine,
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

        // 磁带落座位：井内偏左，开口边朝右对着机构列。
        // 尺寸拉到井宽的 0.72（原来 0.56 显得"小一圈"）：带子右缘离上方磁头组的压带轮
        // 只剩几十像素 —— 真机上带子就是贴着压带轮/主导轴那一列走的。
        // 高度上限 0.86 井高只是兜底（1.56 的宽高比下先卡住的总是宽度）。
        val cassW = minOf(wellW * 0.72f, wellH * 0.86f / CASSETTE_H_RATIO)
        val cassH = cassW * CASSETTE_H_RATIO
        val cassX = wellL + wellW * 0.40f - cassW / 2f
        val cassY = wellT + (wellH - cassH) / 2f

        // 机构列：井内右侧
        val mechX = wellL + wellW * 0.80f
        // 左缘刻度槽
        val scaleX = wellL + wellW * 0.075f

        // 门板织纹：一块小瓦片平铺，只建一次
        val weave = remember { doorWeaveBrush() }

        LaunchedEffect(realW, realH) {
            machine.slotRect = Rect(
                realW + cassX * u,
                cassY * u,
                realW + (cassX + cassW) * u,
                (cassY + cassH) * u,
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            drawRect(C.DoorEdge, Offset.Zero, size)
            drawDeck(
                machine, u, weave,
                doorL, doorT, doorR, doorB,
                wellL, wellT, wellW, wellH, mechX, scaleX,
                cassX, cassY, cassW, cassH,
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                // 唯一的物理动作：点磁带本体 = 取出，飞回磁带库
                .pointerInput(machine.loadedId, machine.busy) {
                    detectTapGestures { pos ->
                        if (machine.busy) return@detectTapGestures
                        val cassette = Rect(
                            cassX * u, cassY * u, (cassX + cassW) * u, (cassY + cassH) * u
                        )
                        if (machine.loadedId != null && cassette.contains(pos)) machine.eject()
                    }
                }
        )
    }
}

// ---------------------------------------------------------------- 门板与织纹

/** 门板织纹：18px 瓦片，正交细线平铺 —— 概念片门板那种织物/碳纹质感。 */
private fun doorWeaveBrush(): Brush {
    val tile = ImageBitmap(18, 18)
    val canvas = androidx.compose.ui.graphics.Canvas(tile)
    val p = Paint().apply {
        color = C.DoorWeave
        strokeWidth = 1f
    }
    canvas.drawLine(Offset(0f, 0.5f), Offset(18f, 0.5f), p)
    canvas.drawLine(Offset(0.5f, 0f), Offset(0.5f, 18f), p)
    val p2 = Paint().apply {
        color = Color(0x08FFFFFF)
        strokeWidth = 1f
    }
    canvas.drawLine(Offset(9f, 0f), Offset(9f, 18f), p2)
    canvas.drawLine(Offset(0f, 9f), Offset(18f, 9f), p2)
    val shader = ImageShader(tile, TileMode.Repeated, TileMode.Repeated)
    return object : ShaderBrush() {
        override fun createShader(size: Size) = shader
    }
}

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

/** 六角形（主导轴 / 带盘轴孔）。 */
private fun hexPath(cx: Float, cy: Float, r: Float): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    for (i in 0 until 6) {
        val a = Math.toRadians(i * 60.0 - 90.0)
        val x = cx + cos(a).toFloat() * r
        val y = cy + sin(a).toFloat() * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** 磁头块：一小方金属块，工作面一道细缝。 */
private fun DrawScope.headBlock(cx: Float, cy: Float, s: Float) {
    drawRoundRect(
        Color(0xB06E6E6A), Offset(cx - s / 2f, cy - s * 0.7f), Size(s, s * 1.4f),
        CornerRadius(s * 0.12f),
    )
    drawRoundRect(
        Color(0x33FFFFFF), Offset(cx - s / 2f, cy - s * 0.7f), Size(s, s * 1.4f),
        CornerRadius(s * 0.12f), style = Stroke(hairPx()),
    )
    drawLine(
        Color(0xCC000000), Offset(cx - s * 0.28f, cy), Offset(cx + s * 0.28f, cy),
        strokeWidth = hairPx(),
    )
}

/** 压带轮：黑色橡胶轮 + 金属轴心。 */
private fun DrawScope.pinchRoller(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0xFF050505), r, Offset(cx, cy))
    drawCircle(Color(0x42FFFFFF), r, Offset(cx, cy), style = Stroke(hairPx()))
    drawCircle(Color(0x99A8A9A5), r * 0.28f, Offset(cx, cy))
}

/** 主导轴：金属六角轴。 */
private fun DrawScope.capstan(cx: Float, cy: Float, r: Float) {
    drawPath(hexPath(cx, cy, r), Color(0x99CFCDC6))
    drawPath(hexPath(cx, cy, r), Color(0x4DFFFFFF), style = Stroke(hairPx()))
    drawCircle(Color(0xFF0A0A0A), r * 0.34f, Offset(cx, cy))
}

/** 导带轮：一道金属环。 */
private fun DrawScope.guideRing(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0x59000000), r, Offset(cx, cy))
    drawCircle(Color(0x8CCFCDC6), r, Offset(cx, cy), style = Stroke(1f * density))
    drawCircle(Color(0xFF0A0A0A), r * 0.36f, Offset(cx, cy))
}

/** 带盘轴（空井时留在轮毂位置）：金属环 + 六角孔。 */
private fun DrawScope.reelTable(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0x38FFFFFF), r, Offset(cx, cy), style = Stroke(1.2f * density))
    drawPath(hexPath(cx, cy, r * 0.42f), Color(0x8CCFCDC6))
    drawPath(hexPath(cx, cy, r * 0.42f), Color(0x33FFFFFF), style = Stroke(hairPx()))
}

// ---------------------------------------------------------------- 井

/**
 * 卡座。所有几何（doorL/cassX…）都是**设计单位**，乘 u 才是像素；
 * 但 hairPx()/density 的线宽不能乘 —— 所以坐标逐个乘 u，线宽保持绝对值。
 */
private fun DrawScope.drawDeck(
    machine: DuoMachine,
    u: Float,
    weave: Brush,
    doorL: Float,
    doorT: Float,
    doorR: Float,
    doorB: Float,
    wellL: Float,
    wellT: Float,
    wellW: Float,
    wellH: Float,
    mechX: Float,
    scaleX: Float,
    cassX: Float,
    cassY: Float,
    cassW: Float,
    cassH: Float,
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

    // ---- 读带井：门板里铣下去的暗腔
    val wl = wellL * u
    val wt = wellT * u
    val ww = wellW * u
    val wh = wellH * u
    val wcr = CornerRadius(4f * density)
    drawRoundRect(Color(0xFF0B0B0B), Offset(wl, wt), Size(ww, wh), wcr)
    drawRoundRect(weave, Offset(wl, wt), Size(ww, wh), wcr)
    // 井口内阴影：井是"凹下去"的 —— 顶边一道落影、底边一线受光
    drawRect(
        Brush.verticalGradient(0f to Color(0x59000000), 1f to Color.Transparent),
        Offset(wl, wt), Size(ww, wh * 0.10f),
    )
    drawRect(
        Brush.horizontalGradient(0f to Color(0x33000000), 1f to Color.Transparent),
        Offset(wl, wt), Size(ww * 0.06f, wh),
    )
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, 1f to Color(0x33000000)),
        Offset(wl + ww * 0.94f, wt), Size(ww * 0.06f, wh),
    )
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x1FFFFFFF)),
        Offset(wl, wt + wh * 0.96f), Size(ww, wh * 0.04f),
    )
    // 井口的"铣边"：上左暗、下右一线受光
    drawRoundRect(Color(0x33000000), Offset(wl, wt), Size(ww, wh), wcr, style = Stroke(1.5f * density))
    drawRoundRect(Color(0x1AFFFFFF), Offset(wl, wt + wh - hairPx() * 2f), Size(ww, hairPx() * 2f), CornerRadius(0f))

    // ---- 左缘刻度槽（走带标尺，无字）
    val sx = scaleX * u
    val tickTop = wt + wh * 0.12f
    val tickBot = wt + wh * 0.88f
    drawLine(Color(0x2EFFFFFF), Offset(sx, tickTop), Offset(sx, tickBot), strokeWidth = hairPx())
    val ticks = 14
    for (i in 0..ticks) {
        val ty = tickTop + (tickBot - tickTop) * i / ticks
        val major = i % 4 == 0
        val len = (if (major) 5f else 2.6f) * density
        drawLine(
            Color(if (major) 0x59FFFFFF else 0x2EFFFFFF),
            Offset(sx, ty), Offset(sx + len, ty),
            strokeWidth = hairPx(),
        )
    }

    // ---- 右侧读带机构列（空井也在 —— 它就是这台机器的"读带结构"）
    val mx = mechX * u
    val headS = 7f * density
    val rollerR = 5.5f * density
    val capR = 3f * density
    val guideR = 4.5f * density
    // 上磁头组：磁头块 + 压带轮 + 主导轴
    headBlock(mx + headS * 0.9f, wt + wh * 0.185f, headS)
    pinchRoller(mx - rollerR * 0.6f, wt + wh * 0.235f, rollerR)
    capstan(mx + capR * 1.9f, wt + wh * 0.245f, capR)
    // 上导带轮
    guideRing(mx, wt + wh * 0.36f, guideR)
    // 下导带轮
    guideRing(mx, wt + wh * 0.64f, guideR)
    // 下磁头组：主导轴 + 磁头块
    capstan(mx + capR * 1.9f, wt + wh * 0.755f, capR)
    headBlock(mx + headS * 0.9f, wt + wh * 0.815f, headS)

    // ---- 磁带 / 空井
    val tape = machine.loadedTape
    val cxp = cassX * u
    val cyp = cassY * u
    val cw = cassW * u
    val ch = cassH * u
    if (tape != null) {
        // 磁带在井里的落影
        drawRoundRect(
            Color(0x66000000), Offset(cxp + u * 0.5f, cyp + u * 0.8f),
            Size(cw, ch), CornerRadius(cw * 0.04f),
        )
        // 水平镜像：让磁带的开口边朝右，对着读带机构（pivot 必须显式给磁带中心，
        // 默认 pivot 是整个画布的 center，会把磁带翻到画面外）
        translate(cxp, cyp) {
            scale(-1f, 1f, pivot = Offset(cw / 2f, ch / 2f)) {
                drawCassette(
                    w = cw,
                    h = ch,
                    tape = tape,
                    tapePos = machine.tapePos,
                    angleTop = machine.angleTop,
                    angleBot = machine.angleBot,
                    tint = machine.tintFor(tape),
                )
            }
        }
    } else {
        // 空井：两个带盘轴留在轮毂位置
        val hubR = cw * 0.115f
        reelTable(cxp + cw / 2f, cyp + ch * 0.288f, hubR * 1.5f)
        reelTable(cxp + cw / 2f, cyp + ch * 0.712f, hubR * 1.5f)
    }

    // 「还没装曲目」警示灯：门板右上角螺丝内侧一颗安全橙小灯
    led(dl + dw - 15f * density, dt + 30f * density, 3.4f * density, on = tape != null && !machine.hasAudio(tape.id))
}
