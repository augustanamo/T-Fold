package com.workbuddy.duoman.ui.parts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.workbuddy.duoman.ui.C

/** 五个功能键 —— 一套符号，不加图标底色，不写一个字。 */
enum class KeyKind { REW, PLAY, PAUSE, FF, EJECT, REC }

/**
 * 按键几何符号：只用实心三角、直线和圆点构成，靠形状自解释。
 * @param s 符号的基准高度（px）
 */
fun DrawScope.drawGlyph(kind: KeyKind, cx: Float, cy: Float, s: Float, color: Color) {
    val gw = s * 1.16f
    when (kind) {
        KeyKind.REW -> {
            val w = gw * 0.45f
            drawPath(tri(cx - gw * 0.275f, cy, w, s, right = false), color)
            drawPath(tri(cx + gw * 0.275f, cy, w, s, right = false), color)
        }

        KeyKind.FF -> {
            val w = gw * 0.45f
            drawPath(tri(cx - gw * 0.275f, cy, w, s, right = true), color)
            drawPath(tri(cx + gw * 0.275f, cy, w, s, right = true), color)
        }

        KeyKind.PLAY -> drawPath(tri(cx + s * 0.06f, cy, s * 0.88f, s * 1.02f, right = true), color)

        KeyKind.PAUSE -> {
            val bw = s * 0.26f
            val gap = s * 0.22f
            drawRoundRect(
                color, Offset(cx - gap / 2f - bw, cy - s / 2f), Size(bw, s),
                CornerRadius(bw * 0.25f),
            )
            drawRoundRect(
                color, Offset(cx + gap / 2f, cy - s / 2f), Size(bw, s),
                CornerRadius(bw * 0.25f),
            )
        }

        KeyKind.EJECT -> {
            drawPath(tri(cx, cy - s * 0.16f, gw, s * 0.62f, right = true), color)
            drawRoundRect(
                color, Offset(cx - gw / 2f, cy + s * 0.28f), Size(gw, s * 0.14f),
                CornerRadius(s * 0.03f),
            )
        }

        // 录音键：一个实心圆点 —— 真机上唯一一个红点，这里也一样
        KeyKind.REC -> drawCircle(color, s * 0.40f, Offset(cx, cy))
    }
}

/**
 * Braun 按键（SK 按钮排）：浅色凸起的方块，落在一条凹槽里。
 *
 * 体是近白浅灰，上缘受光、下缘背光，按下去整体下沉 1px；
 * 唯一一颗 REC 是安全橙 —— 和 Lectron 机身上那唯一一颗橙色件同理。
 */
fun DrawScope.drawKey(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    kind: KeyKind,
    pressed: Boolean,
    armed: Boolean,
) {
    val r = CornerRadius(3f * density)
    val sink = if (pressed) 1f * density else 0f
    // 主走带键（PLAY/PAUSE）= 全机唯一的安全橙 —— 和原概念里那颗橙色 PLAY 同理
    val isAccent = kind == KeyKind.PLAY || kind == KeyKind.PAUSE

    // 键槽（凹）：按键坐进一条比它略大的浅槽
    drawRoundRect(
        C.KeyLightEdge.copy(alpha = 0.55f), Offset(x - sink, y - sink), Size(w, h), r,
    )
    // 键体
    val body = when {
        isAccent -> if (pressed) C.Signal.copy(alpha = 0.86f) else C.Signal
        pressed -> C.KeyLightEdge
        else -> C.KeyLight
    }
    drawRoundRect(body, Offset(x, y + sink), Size(w, h), r)
    // 上缘受光 / 下缘背光（橙色键也保留这层体积）
    drawRoundRect(
        if (isAccent) Color(0x40FFFFFF) else Color(0x66FFFFFF),
        Offset(x + 1f, y + sink + 1f), Size(w - 2f, h - 2f), r,
        style = Stroke(hairPx()),
    )
    drawRoundRect(
        Color(0x1F000000), Offset(x, y + sink + h - hairPx() * 2f), Size(w, h), r,
        style = Stroke(hairPx() * 2f),
    )
    // 丝印：橙色键用 Ivory，其余用石墨
    val ink = when {
        isAccent -> C.Ivory
        pressed -> C.Ink
        else -> C.Graphite
    }
    drawGlyph(kind, x + w / 2f, y + sink + h / 2f, h * 0.30f, ink)
}

/**
 * 机械式位置指示器：一根细滑轨 + 一个赤色滑块。
 * 赤色只出现在这里和录音键上 —— 它表示"当前功能"。
 */
fun DrawScope.drawIndicator(
    trackX0: Float,
    trackX1: Float,
    y: Float,
    knobCx: Float,
    engaged: Boolean,
    trackColor: Color,
) {
    hair(trackX0, y, trackX1, y, trackColor, alpha = 0.55f)
    val c = if (engaged) C.Signal else C.Steel
    val w = 26f * density
    val h = 3f * density
    drawRoundRect(
        c, Offset(knobCx - w / 2f, y - h / 2f), Size(w, h), CornerRadius(h / 2f),
    )
}

/** 状态小圆点（信号赤 = 需要动作）。 */
fun DrawScope.drawDot(cx: Float, cy: Float, r: Float, color: Color) {
    drawCircle(color, r, Offset(cx, cy))
}
