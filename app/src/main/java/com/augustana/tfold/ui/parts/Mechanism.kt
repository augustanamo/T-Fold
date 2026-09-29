package com.augustana.tfold.ui.parts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
import com.augustana.tfold.ui.C

// 读带机构件 —— 内屏卡座（DeckPane）与外屏那个井（FoldedFace）**共用同一套画法**。
//
// 之所以要把这几个零件从 DeckPane 里搬出来：外屏合盖时露出来的就是这台机器的"正面"，
// 没有带子时它不能是一块纯黑的洞 —— 该露出读带结构（磁头组 / 压带轮 / 主导轴 /
// 导带轮 / 两个带盘轴）。既然内屏卡座里已经有一套画得挺准的，就别在外屏再画一遍
// 近似的 —— 同一个零件两处不一致，翻开一看就能看出来。

/**
 * 门板 / 井底织纹：18px 瓦片，正交细线平铺 —— 概念片门板那种织物 / 碳纹质感。
 *
 * 建瓦片有成本（一张 ImageBitmap + 一个 shader），**必须 remember 起来复用**，
 * 别在每帧的 draw 里调。
 */
fun deckWeaveBrush(): Brush {
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

/** 磁头块：一小方金属块，工作面一道细缝。 */
fun DrawScope.headBlock(cx: Float, cy: Float, s: Float) {
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
fun DrawScope.pinchRoller(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0xFF050505), r, Offset(cx, cy))
    drawCircle(Color(0x42FFFFFF), r, Offset(cx, cy), style = Stroke(hairPx()))
    drawCircle(Color(0x99A8A9A5), r * 0.28f, Offset(cx, cy))
}

/** 主导轴：金属六角轴。 */
fun DrawScope.capstan(cx: Float, cy: Float, r: Float) {
    drawPath(polygon(cx, cy, r, 6, 0f), Color(0x99CFCDC6))
    drawPath(polygon(cx, cy, r, 6, 0f), Color(0x4DFFFFFF), style = Stroke(hairPx()))
    drawCircle(Color(0xFF0A0A0A), r * 0.34f, Offset(cx, cy))
}

/** 导带轮：一道金属环。 */
fun DrawScope.guideRing(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0x59000000), r, Offset(cx, cy))
    drawCircle(Color(0x8CCFCDC6), r, Offset(cx, cy), style = Stroke(1f * density))
    drawCircle(Color(0xFF0A0A0A), r * 0.36f, Offset(cx, cy))
}

/** 带盘轴（空井时留在轮毂位置）：金属环 + 六角孔。 */
fun DrawScope.reelTable(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0x38FFFFFF), r, Offset(cx, cy), style = Stroke(1.2f * density))
    drawPath(polygon(cx, cy, r * 0.42f, 6, 0f), Color(0x8CCFCDC6))
    drawPath(polygon(cx, cy, r * 0.42f, 6, 0f), Color(0x33FFFFFF), style = Stroke(hairPx()))
}
