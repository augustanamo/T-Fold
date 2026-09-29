package com.augustana.tfold.ui.parts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.augustana.tfold.ui.C
import kotlin.math.cos
import kotlin.math.sin

/**
 * Braun 通风栅：一排等距的黑色横槽。
 *
 * Lectron / SK 机身上那组标志性的散热缝 —— 不做渐变、不做圆孔，
 * 就是干净的平行槽，靠「槽深一致、间距一致」出气质。
 *
 * @param slots 槽数；[slotH] 槽厚；[gap] 槽间距（中心到中心）
 */
fun DrawScope.drawGrille(
    x: Float,
    y: Float,
    w: Float,
    slots: Int,
    slotH: Float,
    gap: Float,
) {
    val r = CornerRadius(slotH / 2f)
    for (i in 0 until slots) {
        val yy = y + i * gap
        // 槽底
        drawRoundRect(C.Vent, Offset(x, yy), Size(w, slotH), r)
        // 槽内上缘一道细高光，让槽看起来是"铣"进去的
        drawRoundRect(
            androidx.compose.ui.graphics.Color(0x33FFFFFF),
            Offset(x, yy + slotH - hairPx()), Size(w, hairPx()), CornerRadius(0f),
        )
    }
}

/**
 * Braun 旋钮：黑色圆柱 + 受光边 + 一道定位刻线。
 *
 * Clairtone 机身上 AM/SW 那种旋钮：不滚花、不加字，
 * 靠圆柱的体积感和一道白色定位线说明"它是能拧的"。
 *
 * @param angleDeg 定位刻线角度（0 = 正上方，顺时针）
 */
fun DrawScope.drawKnob(cx: Float, cy: Float, r: Float, angleDeg: Float) {
    // 落影：让旋钮"立"在机身上
    drawCircle(androidx.compose.ui.graphics.Color(0x33000000), r * 1.04f, Offset(cx + r * 0.10f, cy + r * 0.16f))
    // 柱体
    drawCircle(C.Knob, r, Offset(cx, cy))
    // 顶面受光（左上一圈浅弧 = 圆柱的体积）
    drawArc(
        color = C.KnobEdge, startAngle = 150f, sweepAngle = 120f, useCenter = false,
        topLeft = Offset(cx - r * 0.86f, cy - r * 0.86f), size = Size(r * 1.72f, r * 1.72f),
        style = Stroke(r * 0.16f),
    )
    drawCircle(C.Knob, r * 0.62f, Offset(cx, cy))
    // 定位刻线：跟着 angle 转
    rotate(angleDeg, Offset(cx, cy)) {
        drawRoundRect(
            C.KnobMark,
            Offset(cx - r * 0.055f, cy - r * 0.92f),
            Size(r * 0.11f, r * 0.46f),
            CornerRadius(r * 0.05f),
        )
    }
}

/** 旋钮角度 → 0..1（270° 行程：-135° .. +135°，音频电位器的标准行程）。 */
fun knobValueFromAngle(angleDeg: Float): Float = ((angleDeg + 135f) / 270f).coerceIn(0f, 1f)

/** 0..1 → 旋钮角度。 */
fun knobAngleFromValue(v: Float): Float = v.coerceIn(0f, 1f) * 270f - 135f

/** 点到旋钮中心的夹角（度，0 = 正上方，顺时针），用于拖动旋钮。 */
fun angleOf(cx: Float, cy: Float, x: Float, y: Float): Float {
    val dx = x - cx
    val dy = y - cy
    var a = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()
    if (a > 180f) a -= 360f
    if (a < -180f) a += 360f
    return a
}
