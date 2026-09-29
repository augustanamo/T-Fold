package com.augustana.tfold.ui.parts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class Align { Left, Center, Right }

/**
 * 在 Canvas 里排一行字。
 * 字号用 px 换算成 sp（除以 density），所以设计比例在任何屏幕上都不变形；
 * 用户的字体缩放会额外生效，用于无障碍。
 */
fun DrawScope.text(
    tm: TextMeasurer,
    s: String,
    x: Float,
    cy: Float,
    sizePx: Float,
    color: Color,
    letterSpacingPx: Float = 0f,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif,
    align: Align = Align.Left,
    alpha: Float = 1f,
): TextLayoutResult {
    val st = TextStyle(
        color = color,
        fontSize = (sizePx / density).sp,
        fontWeight = weight,
        fontFamily = family,
        letterSpacing = (letterSpacingPx / density).sp,
    )
    val r = tm.measure(s, st)
    val w = r.size.width.toFloat()
    val dx = when (align) {
        Align.Left -> x
        Align.Center -> x - w / 2f
        Align.Right -> x - w
    }
    drawText(r, topLeft = Offset(dx, cy - r.size.height / 2f), alpha = alpha)
    return r
}

/** 拉姆斯式丝印：小号、大写、宽字距。 */
fun DrawScope.label(
    tm: TextMeasurer,
    s: String,
    x: Float,
    cy: Float,
    sizePx: Float,
    color: Color,
    align: Align = Align.Left,
    alpha: Float = 1f,
) = text(tm, s, x, cy, sizePx, color, letterSpacingPx = sizePx * 0.16f, align = align, alpha = alpha)

/**
 * 单行排不下就裁字接省略号。
 *
 * 盒面上的专辑名必须**一行**放下（磁带的 J 卡就是这么排的），但曲名长度是用户数据 ——
 * 与其让它压到右边那行小字上，不如裁掉。逐字测量（名字都很短，代价可忽略），
 * 不依赖 `TextMeasurer` 的 constraints 重载，版本无关。
 */
fun DrawScope.textClipped(
    tm: TextMeasurer,
    s: String,
    x: Float,
    cy: Float,
    sizePx: Float,
    color: Color,
    maxWidth: Float,
    letterSpacingPx: Float = 0f,
    weight: FontWeight = FontWeight.Normal,
    alpha: Float = 1f,
): TextLayoutResult {
    val st = TextStyle(
        color = color,
        fontSize = (sizePx / density).sp,
        fontWeight = weight,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = (letterSpacingPx / density).sp,
    )
    fun widthOf(t: String) = tm.measure(t, st).size.width.toFloat()
    var shown = s
    if (widthOf(shown) > maxWidth) {
        while (shown.isNotEmpty() && widthOf("$shown…") > maxWidth) shown = shown.dropLast(1)
        shown += "…"
    }
    val r = tm.measure(shown, st)
    drawText(r, topLeft = Offset(x, cy - r.size.height / 2f), alpha = alpha)
    return r
}

/** 凹槽：外深内浅的一道双线，用来表示"沉下去"的开口。 */
fun DrawScope.engrave(x: Float, y: Float, w: Float, h: Float, r: Float, dark: Color, light: Color) {
    drawRoundRect(
        color = dark, topLeft = Offset(x, y), size = Size(w, h),
        cornerRadius = CornerRadius(r), style = Stroke(hairPx())
    )
    drawRoundRect(
        color = light, topLeft = Offset(x + hairPx(), y + hairPx()),
        size = Size(w - hairPx(), h - hairPx()),
        cornerRadius = CornerRadius(r), style = Stroke(hairPx())
    )
}

/** 凸起：内浅外深，用来表示"按下去之外"的实体按键边缘。 */
fun DrawScope.bevel(x: Float, y: Float, w: Float, h: Float, r: Float, light: Color, dark: Color) {
    drawRoundRect(
        color = light, topLeft = Offset(x, y), size = Size(w, h),
        cornerRadius = CornerRadius(r), style = Stroke(hairPx())
    )
    drawRoundRect(
        color = dark, topLeft = Offset(x, y + h - hairPx()), size = Size(w - hairPx(), h),
        cornerRadius = CornerRadius(r), style = Stroke(hairPx())
    )
}

fun DrawScope.hair(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, alpha: Float = 1f) {
    drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = hairPx(), alpha = alpha)
}

fun DrawScope.hairPx(): Float = 0.5f * density

fun DrawScope.linePx(): Float = 1f * density

fun DrawScope.roundRect(cx: Float, cy: Float, w: Float, h: Float, r: Float, color: Color, alpha: Float = 1f) {
    drawRoundRect(
        color = color, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h),
        cornerRadius = CornerRadius(r), alpha = alpha
    )
}

fun DrawScope.strokeRoundRect(
    cx: Float, cy: Float, w: Float, h: Float, r: Float, color: Color, width: Float, alpha: Float = 1f
) {
    drawRoundRect(
        color = color, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h),
        cornerRadius = CornerRadius(r), alpha = alpha, style = Stroke(width)
    )
}

/** 正多边形路径。 */
fun polygon(cx: Float, cy: Float, r: Float, sides: Int, rotateDeg: Float = 0f): Path {
    val p = Path()
    for (i in 0 until sides) {
        val a = Math.toRadians((i * 360f / sides + rotateDeg).toDouble())
        val x = cx + (r * Math.cos(a)).toFloat()
        val y = cy + (r * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/** 向右或向左的实心三角。 */
fun tri(cx: Float, cy: Float, w: Float, h: Float, right: Boolean): Path {
    val p = Path()
    if (right) {
        p.moveTo(cx - w / 2f, cy - h / 2f)
        p.lineTo(cx + w / 2f, cy)
        p.lineTo(cx - w / 2f, cy + h / 2f)
    } else {
        p.moveTo(cx + w / 2f, cy - h / 2f)
        p.lineTo(cx - w / 2f, cy)
        p.lineTo(cx + w / 2f, cy + h / 2f)
    }
    p.close()
    return p
}
