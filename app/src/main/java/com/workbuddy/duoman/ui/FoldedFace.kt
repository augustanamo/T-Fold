package com.workbuddy.duoman.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import com.workbuddy.duoman.machine.DuoMachine
import com.workbuddy.duoman.machine.Transport
import com.workbuddy.duoman.ui.parts.CASSETTE_H_RATIO
import com.workbuddy.duoman.ui.parts.KeyKind
import com.workbuddy.duoman.ui.parts.angleOf
import com.workbuddy.duoman.ui.parts.drawCassette
import com.workbuddy.duoman.ui.parts.drawGrille
import com.workbuddy.duoman.ui.parts.drawKey
import com.workbuddy.duoman.ui.parts.drawKnob
import com.workbuddy.duoman.ui.parts.hair
import com.workbuddy.duoman.ui.parts.hairPx
import com.workbuddy.duoman.ui.parts.knobAngleFromValue
import com.workbuddy.duoman.ui.parts.knobValueFromAngle
import com.workbuddy.duoman.ui.parts.led
import com.workbuddy.duoman.ui.parts.text
import kotlin.math.cos
import kotlin.math.sin

/**
 * 合上态（外屏）：整块屏幕就是这台机器的正面。
 *
 * 布局沿用 viditb 原概念的逻辑：
 *  磁带窗是主角，走带键是**右缘一列竖排**（最上是唯一的安全橙 PLAY），
 *  左上品牌字标、右上圆形旋钮。
 * 但整套皮肤换成 Braun / 拉姆斯：瓷白机身、通风栅、浅色凸起键、黑旋钮。
 *
 * 尺寸由「屏宽」定标、「屏高」兜底，从外屏到任何竖屏都不会打架。
 * 交互：竖排键是真按键（按下下沉）；右上角旋钮直接拧 = 音量。
 */
@Composable
fun FoldedFace(
    machine: DuoMachine,
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

        Canvas(Modifier.fillMaxSize()) {
            drawFoldedFace(machine, tm, w, h, m, pressed, knob.value)
        }

        // 音量旋钮：只有从旋钮上起手才算拧它。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var active = false
                    detectDragGestures(
                        onDragStart = { start ->
                            val dx = start.x - m.knobCx
                            val dy = start.y - m.knobCy
                            active = (dx * dx + dy * dy) <= (m.knobR * 1.5f) * (m.knobR * 1.5f)
                        },
                        onDragEnd = { active = false },
                        onDragCancel = { active = false },
                    ) { change, _ ->
                        if (!active) return@detectDragGestures
                        change.consume()
                        val a = angleOf(m.knobCx, m.knobCy, change.position.x, change.position.y)
                        machine.adjustVolume(knobValueFromAngle(a.coerceIn(-135f, 135f)))
                    }
                }
        )

        // 竖排走带键。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { pos ->
                            val i = m.keyIndexAt(pos)
                            if (i >= 0 && !machine.busy) {
                                pressed = i
                                tryAwaitRelease()
                                pressed = -1
                            }
                        },
                        onTap = { pos ->
                            if (machine.busy) return@detectTapGestures
                            // 竖排自上而下：PLAY FF REW REC EJECT
                            when (m.keyIndexAt(pos)) {
                                0 -> machine.pressPlay()
                                1 -> machine.pressFF()
                                2 -> machine.pressREW()
                                3 -> machine.pressRec()
                                4 -> machine.pressEject()
                            }
                        },
                    )
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

    // 顶带：左字标、中通风栅、右音量旋钮
    val topY = inset * 0.9f
    val knobR = w * 0.082f
    val knobCx = w - inset - knobR
    val knobCy = topY + knobR
    val wordX = inset
    val wordY = topY + knobR * 0.9f
    val grilleX = inset + w * 0.30f
    val grilleW = w * 0.30f
    val grilleY = topY + w * 0.010f
    val grilleSlots = 4
    val grilleSlotH = w * 0.012f
    val grilleGap = w * 0.030f
    val topBandBottom = maxOf(
        knobCy + knobR,
        grilleY + (grilleSlots - 1) * grilleGap + grilleSlotH,
    ) + w * 0.045f

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
    machine: DuoMachine,
    tm: TextMeasurer,
    w: Float,
    h: Float,
    m: FoldMetric,
    pressed: Int,
    knobY: Float,
) {
    val tape = machine.loadedTape

    // 机身 + 一道内凹边框
    drawRoundRect(C.Porcelain, Offset(0f, 0f), Size(w, h), CornerRadius(12f * density))
    drawRoundRect(
        C.Paper, Offset(m.inset * 0.55f, m.inset * 0.55f),
        Size(w - m.inset * 1.1f, h - m.inset * 1.1f), CornerRadius(9f * density),
    )
    drawRoundRect(
        C.Hair, Offset(m.inset * 0.55f, m.inset * 0.55f),
        Size(w - m.inset * 1.1f, h - m.inset * 1.1f),
        CornerRadius(9f * density), style = Stroke(hairPx()),
    )

    // 通风栅（顶带左段，原来字标的位置）
    drawGrille(m.grilleX, m.grilleY, m.grilleW, m.grilleSlots, m.grilleSlotH, m.grilleGap)

    // 音量旋钮（右上）+ 一圈刻度
    drawKnobScale(m.knobCx, m.knobCy, m.knobR)
    drawKnob(m.knobCx, m.knobCy, m.knobR, knobAngleFromValue(machine.volume))

    // 磁带窗：全屏唯一深色块
    val padX = w * 0.020f
    drawRoundRect(
        C.Shade, Offset(m.winX + w * 0.005f, m.winY + w * 0.008f),
        Size(m.winW + padX * 2f, m.winH + padX * 2f), CornerRadius(6f * density),
    )
    drawRoundRect(
        C.Recess, Offset(m.winX - padX, m.winY - padX),
        Size(m.winW + padX * 2f, m.winH + padX * 2f), CornerRadius(6f * density),
    )
    drawRoundRect(
        Color(0x33FFFFFF), Offset(m.winX - padX, m.winY - padX),
        Size(m.winW + padX * 2f, m.winH + padX * 2f),
        CornerRadius(6f * density), style = Stroke(hairPx()),
    )
    if (tape != null) {
        translate(m.winX, m.winY) {
            drawCassette(
                w = m.winW,
                h = m.winH,
                tape = tape,
                tapePos = machine.tapePos,
                angleTop = machine.angleTop,
                angleBot = machine.angleBot,
            )
        }
    }
    // 没装曲目：窗口右上角一颗安全橙警示灯
    led(
        m.winX + m.winW + padX * 0.4f, m.winY - padX * 0.35f, w * 0.0080f,
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
    for (i in 0..4) {
        val r = m.keyRect(i)
        drawKey(
            r.left, r.top, r.width, r.height, kinds[i], pressed == i,
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
