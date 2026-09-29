package com.augustana.tfold.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import com.augustana.tfold.machine.FoldMachine
import com.augustana.tfold.machine.Transport
import com.augustana.tfold.ui.parts.CASSETTE_H_RATIO
import com.augustana.tfold.ui.parts.KeyKind
import com.augustana.tfold.ui.parts.WellLayout
import com.augustana.tfold.ui.parts.angleOf
import com.augustana.tfold.ui.parts.deckWeaveBrush
import com.augustana.tfold.ui.parts.drawGrille
import com.augustana.tfold.ui.parts.drawKey
import com.augustana.tfold.ui.parts.drawKnob
import com.augustana.tfold.ui.parts.drawWellInterior
import com.augustana.tfold.ui.parts.hair
import com.augustana.tfold.ui.parts.hairPx
import com.augustana.tfold.ui.parts.knobAngleFromValue
import com.augustana.tfold.ui.parts.led
import com.augustana.tfold.ui.parts.text
import kotlin.math.cos
import kotlin.math.sin

/**
 * 合上态（外屏）：整块屏幕就是这台机器的正面。
 *
 * 布局沿用 viditb 原概念的逻辑：
 *  磁带窗是主角，走带键是**右缘一列竖排**（最上是唯一的安全橙 PLAY），
 *  顶带左边扬声器栅、右边圆形音量旋钮。
 * 但整套皮肤换成 Braun / 拉姆斯：瓷白机身、通风栅、浅色凸起键、黑旋钮。
 *
 * 尺寸由「屏宽」定标、「屏高」兜底，从外屏到任何竖屏都不会打架。
 * 交互：竖排键是真按键（按下下沉）；右上角旋钮直接拧 = 音量。
 */
@Composable
fun FoldedFace(
    machine: FoldMachine,
    tm: TextMeasurer,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val m = FoldMetric(w, h)

        val knob = animateFloatAsState(
            targetValue = m.keyCenterY(stripIndexOf(machine.indicatorAt)),
            animationSpec = tween(200),
            label = "knob",
        )

        // 窗底织纹：和内屏卡座井底同一块瓦片，建一次复用（每帧建瓦片 = 每帧一张 ImageBitmap）
        val weave = remember { deckWeaveBrush() }

        Canvas(Modifier.fillMaxSize()) {
            drawFoldedFace(machine, tm, w, h, m, pressed, knob.value, weave)
        }

        // 一层的指针处理同时管两件事 —— 分成两层的话，上面那层会把按下事件消费掉，
        // 底下的旋钮永远收不到（曾经拧不动就是这个原因）。
        //   · 从旋钮圆里起手 → 拧音量（按角度增量累计，起手不跳）
        //   · 从竖排键里起手 → 按下沉、松手触发
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()

                        // ---- 旋钮
                        val kdx = down.position.x - m.knobCx
                        val kdy = down.position.y - m.knobCy
                        val hitR = m.knobR * 1.6f
                        if (kdx * kdx + kdy * kdy <= hitR * hitR) {
                            down.consume()
                            val base = machine.volume
                            var prev = angleOf(m.knobCx, m.knobCy, down.position.x, down.position.y)
                            var acc = 0f
                            drag(down.id) { change ->
                                change.consume()
                                val a = angleOf(m.knobCx, m.knobCy, change.position.x, change.position.y)
                                var d = a - prev
                                if (d > 180f) d -= 360f      // 跨过 ±180° 的接缝
                                if (d < -180f) d += 360f
                                acc += d
                                prev = a
                                machine.adjustVolume(base + acc / 270f)
                            }
                            return@awaitEachGesture
                        }

                        // ---- 竖排走带键
                        val i = m.keyIndexAt(down.position)
                        if (i < 0) return@awaitEachGesture
                        down.consume()
                        if (machine.busy) return@awaitEachGesture
                        pressed = i
                        val up = waitForUpOrCancellation()
                        pressed = -1
                        if (up != null) {
                            // 竖排自上而下：PLAY FF REW REC EJECT
                            when (i) {
                                0 -> machine.pressPlay()
                                1 -> machine.pressFF()
                                2 -> machine.pressREW()
                                3 -> machine.pressRec()
                                4 -> machine.pressEject()
                            }
                        }
                    }
                }
        )
    }
}

/** 状态机的 indicatorAt（0REW 1PLAY 2FF 3EJECT 4REC）→ 竖排键位（0PLAY 1FF 2REW 3REC 4EJECT）。 */
private fun stripIndexOf(indicatorAt: Int): Int = when (indicatorAt) {
    1 -> 0
    2 -> 1
    0 -> 2
    4 -> 3
    3 -> 4
    else -> 0
}

/** 合上态正面的全部尺寸：宽度定标、高度兜底。 */
class FoldMetric(val w: Float, val h: Float) {
    val inset = w * 0.056f

    // 顶带：左扬声器栅（左对齐）、右音量旋钮 —— 同一水平中线
    val topY = inset * 0.9f
    val knobR = w * 0.082f
    val knobCx = w - inset - knobR
    val knobCy = topY + knobR
    // 扬声器栅：细密横槽，宽约 1/3 屏，跟旋钮同轴居中
    val grilleX = inset
    val grilleW = w * 0.34f
    val grilleSlots = 6
    val grilleSlotH = w * 0.0090f
    val grilleGap = w * 0.0195f
    val grilleH = (grilleSlots - 1) * grilleGap + grilleSlotH
    val grilleY = knobCy - grilleH / 2f
    val topBandBottom = maxOf(knobCy + knobR, grilleY + grilleH) + w * 0.048f

    // 右缘竖排键：PLAY FF REW REC EJECT（自上而下）
    val keyGap = w * 0.020f
    val keySize = w * 0.128f
    val keyX = w - inset - keySize
    val stripBottom = h - h * 0.090f
    val stripTop: Float

    // 磁带窗：键排左侧的大块区域
    val winRight = keyX - w * 0.032f
    val bandH: Float
    val winW: Float
    val winH: Float
    val winX: Float
    val winY: Float

    val botY = h - h * 0.050f

    init {
        val totalKeys = keySize * 5f + keyGap * 4f
        stripTop = stripBottom - totalKeys
        bandH = stripBottom - topBandBottom
        val winAreaW = winRight - inset
        winW = minOf(winAreaW * 0.96f, bandH / CASSETTE_H_RATIO)
        winH = winW * CASSETTE_H_RATIO
        winX = inset + (winAreaW - winW) / 2f
        winY = topBandBottom + (bandH - winH) / 2f
    }

    /** 竖排键左侧的指示轨 x。 */
    val railX = keyX - w * 0.020f

    /**
     * **面板上那个洞**（= 井口）。
     *
     * 卡座不是画在"窗"里的：它整块垫在面板底下（[drawFoldedFace] 的第 0 层），
     * 面板在这块地方开一个洞，洞里的卡座自然露出来 —— 合上盖子只是给同一个卡座
     * 盖了块开了洞的面板，机器本身没换、比例也没动。
     */
    val winL = winX - w * 0.020f
    val winT = winY - w * 0.020f
    val winRW = winW + w * 0.040f
    val winRH = winH + w * 0.040f

    /** 洞口的圆角：这是**面板**铣出来的口，不是井的圆角（井比洞大得多）。 */
    val winRadius = w * 0.0126f

    fun keyRect(i: Int): Rect {
        val top = stripTop + i * (keySize + keyGap)
        return Rect(keyX, top, keyX + keySize, top + keySize)
    }

    fun keyCenterY(i: Int): Float = keyRect(i.coerceIn(0, 4)).center.y

    fun keyIndexAt(pos: Offset): Int {
        for (i in 0..4) if (keyRect(i).contains(pos)) return i
        return -1
    }
}

// ---------------------------------------------------------------- 绘制

private fun DrawScope.drawFoldedFace(
    machine: FoldMachine,
    tm: TextMeasurer,
    w: Float,
    h: Float,
    m: FoldMetric,
    pressed: Int,
    knobY: Float,
    weave: Brush,
) {
    val tape = machine.loadedTape

    // 井口 = 面板上那个洞。**洞只是"开了个口"**：井的尺寸由屏定（= 内屏卡座栏里那口井
    // 的比例，井占栏宽的 0.837、栏高的 0.866），带子中心对着洞心 —— 井比洞大，
    // 从洞里看到的是这台机器中间被裁出来的一块。拿洞去定井的尺寸（井 = 洞）等于
    // 整台机器跟着洞缩了一圈，那就不叫"合盖"了。
    val hole = Rect(m.winL, m.winT, m.winL + m.winRW, m.winT + m.winRH)
    val well = WellLayout.centred(
        wellW = w * 0.837f,
        wellH = h * 0.866f,
        casCx = hole.center.x,
        casCy = hole.center.y,
    )
    val winR = CornerRadius(m.winRadius)
    val holePath = Path().apply { addRoundRect(RoundRect(hole, winR)) }

    // ============ 0. 垫在最底下的那台机器：门板 + 卡座井 ============
    //
    // 层序是这台机器的真实层序：卡座在最里，外面才是那块开了洞的面板。
    // 所以先整块铺底（门板 + 井 + 机构 + 带子），再把面板盖上去 ——
    // 面板中间那个洞里的内容**不是"画进窗里的"，是本来就躺在那儿、从洞里露出来的**。
    // （反过来做 —— 先画面板、再往窗里塞一套按窗宽重排的机构 —— 内外屏就是两台机器。）
    drawRect(C.DoorBlack, Offset.Zero, Size(w, h))
    drawRect(weave, Offset.Zero, Size(w, h))
    drawWellInterior(
        L = well,
        weave = weave,
        tape = tape,
        tapePos = machine.tapePos,
        angleTop = machine.spinTop,
        angleBot = machine.spinBot,
        tint = tape?.let { machine.tintFor(it) },
        shadowOffset = w * 0.0008f,
    )

    // ============ 1. 面板：瓷白机身，中间给井留一个洞 ============
    //
    // 用 EvenOdd 的 Path 挖洞，而不是"画满再画一块深色矩形" —— 后者等于把卡座又盖回去了，
    // 而且那块深色的圆角/边缘会和井口的圆角对不上，露出一圈缝。
    val body = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(12f * density)))
        addRoundRect(RoundRect(hole, winR))
    }
    drawPath(body, C.Porcelain)
    val paper = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(
            RoundRect(
                m.inset * 0.55f, m.inset * 0.55f,
                w - m.inset * 0.55f, h - m.inset * 0.55f, CornerRadius(9f * density),
            ),
        )
        addRoundRect(RoundRect(hole, winR))
    }
    drawPath(paper, C.Paper)
    drawPath(
        Path().apply {
            addRoundRect(
                RoundRect(
                    m.inset * 0.55f, m.inset * 0.55f,
                    w - m.inset * 0.55f, h - m.inset * 0.55f, CornerRadius(9f * density),
                ),
            )
        },
        C.Hair, style = Stroke(hairPx()),
    )

    // 洞口那一圈"铣边"：面板是有厚度的，口的内壁得有一道影 + 外缘一道受光，
    // 底缘再补一线受光（井自己那道底光在洞外，看不见了 —— 露出来的这段得有自己的）。
    drawPath(holePath, Color(0x59000000), style = Stroke(3f * density))
    drawPath(holePath, Color(0x33FFFFFF), style = Stroke(hairPx()))
    drawLine(
        Color(0x1FFFFFFF),
        Offset(hole.left + winR.x, hole.bottom - hairPx() * 2f),
        Offset(hole.right - winR.x, hole.bottom - hairPx() * 2f),
        strokeWidth = hairPx() * 2f,
    )

    // 扬声器栅（顶带左段，左对齐）
    drawGrille(m.grilleX, m.grilleY, m.grilleW, m.grilleSlots, m.grilleSlotH, m.grilleGap)

    // 音量旋钮（右上）+ 一圈刻度
    drawKnobScale(m.knobCx, m.knobCy, m.knobR)
    drawKnob(m.knobCx, m.knobCy, m.knobR, knobAngleFromValue(machine.volume))

    // 没装曲目：井口右上角一颗安全橙警示灯（位置照内屏卡座门板上那颗 —— 都在井口角上）
    led(
        hole.right - well.corner * 2.6f, hole.top + well.corner * 2.0f, w * 0.0080f,
        on = tape != null && !machine.hasAudio(tape.id),
    )

    // 竖排键：PLAY(橙) FF REW REC EJECT
    val playing = machine.transport == Transport.PLAY
    val engaged = machine.transport != Transport.STOP
    val kinds = listOf(
        if (playing) KeyKind.PAUSE else KeyKind.PLAY,
        KeyKind.FF,
        KeyKind.REW,
        KeyKind.REC,
        KeyKind.EJECT,
    )
    // 录音时那颗红点**一闪一闪** —— 真录音机也是这么告诉你的，不闪烁就没人知道还在录。
    // 时序借 `machine.frame`（每帧 +1）：60fps 下 30 帧 ≈ 半秒亮、半秒暗。
    val recBlink = machine.recording && (machine.frame / 30L) % 2L == 0L
    for (i in 0..4) {
        val r = m.keyRect(i)
        drawKey(
            r.left, r.top, r.width, r.height, kinds[i], pressed == i || (i == 3 && recBlink),
            engaged && stripIndexOf(machine.indicatorAt) == i,
        )
    }

    // 键排左侧的竖直指示轨 + 一个安全橙滑块（当前功能）
    hair(m.railX, m.stripTop, m.railX, m.stripBottom, C.HairDeep, alpha = 0.5f)
    val kw = 3f * density
    drawRoundRect(
        if (engaged) C.Signal else C.Steel,
        Offset(m.railX - kw / 2f, knobY - 11f * density),
        Size(kw, 22f * density),
        CornerRadius(kw / 2f),
    )

    // 底缘一道压线（Braun 的机身边界感），不放任何读数 —— 这台机器面上没有数字
    hair(
        m.inset, m.stripBottom + (m.botY - m.stripBottom) * 0.30f,
        m.winRight, m.stripBottom + (m.botY - m.stripBottom) * 0.30f, C.HairSoft,
    )
}

/** 旋钮周围的一圈音量刻度（-135°..+135°，9 格）。 */
private fun DrawScope.drawKnobScale(cx: Float, cy: Float, r: Float) {
    val rr = r * 1.28f
    for (i in 0..8) {
        val a = Math.toRadians((-135f + i * 270f / 8f).toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val major = i == 0 || i == 4 || i == 8
        val r0 = rr * (if (major) 0.90f else 0.96f)
        drawLine(
            if (major) C.Smoke else C.HairDeep,
            Offset(cx + s * r0, cy - c * r0),
            Offset(cx + s * rr, cy - c * rr),
            strokeWidth = if (major) 1.4f * density else hairPx(),
        )
    }
}
