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

/** 磁头块：拉丝不锈钢壳体 + 坡莫合金磁芯叠片 + 磁隙黑线 + 顶部高光倒角。 */
fun DrawScope.headBlock(cx: Float, cy: Float, s: Float) {
    val topLeft = Offset(cx - s / 2f, cy - s * 0.7f)
    val sz = Size(s, s * 1.4f)
    val cr = CornerRadius(s * 0.14f)
    // 底部落影
    drawRoundRect(Color(0x66000000), Offset(topLeft.x + hairPx(), topLeft.y + hairPx() * 1.5f), sz, cr)
    // 金属渐变主体
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color(0xFF9E9D97),
            0.45f to Color(0xFF6E6D68),
            1f to Color(0xFF3E3D3A),
            startY = topLeft.y,
            endY = topLeft.y + sz.height,
        ),
        topLeft, sz, cr,
    )
    // 左侧弧形工作面（贴磁带一侧的高亮抛光面）
    drawRoundRect(
        Color(0x55FFFFFF),
        Offset(topLeft.x, topLeft.y + s * 0.18f),
        Size(s * 0.24f, sz.height - s * 0.36f),
        CornerRadius(s * 0.08f),
    )
    // 磁隙（Gap）与上下声道铜线圈微色标
    drawLine(
        Color(0xD9080808), Offset(cx - s * 0.38f, cy), Offset(cx + s * 0.32f, cy),
        strokeWidth = hairPx() * 1.3f,
    )
    drawLine(
        Color(0x66C88A55), Offset(cx - s * 0.22f, cy - s * 0.28f), Offset(cx + s * 0.22f, cy - s * 0.28f),
        strokeWidth = hairPx(),
    )
    drawLine(
        Color(0x66C88A55), Offset(cx - s * 0.22f, cy + s * 0.28f), Offset(cx + s * 0.22f, cy + s * 0.28f),
        strokeWidth = hairPx(),
    )
    drawRoundRect(Color(0x48FFFFFF), topLeft, sz, cr, style = Stroke(hairPx()))
}

/** 压带轮：硫化黑橡胶厚胎 + 黄铜轴套 + 不锈钢轴芯。 */
fun DrawScope.pinchRoller(cx: Float, cy: Float, r: Float) {
    val c = Offset(cx, cy)
    drawCircle(Color(0x73000000), r * 1.12f, Offset(cx + hairPx(), cy + hairPx() * 1.5f))
    // 橡胶轮胎
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF2B2B2B),
            0.75f to Color(0xFF121212),
            1f to Color(0xFF050505),
            center = Offset(cx - r * 0.2f, cy - r * 0.2f),
            radius = r * 1.2f,
        ),
        r, c,
    )
    drawCircle(Color(0x38FFFFFF), r, c, style = Stroke(hairPx()))
    // 金属内毂与黄铜轴套
    drawCircle(Color(0xFF5A5852), r * 0.48f, c)
    drawCircle(Color(0xFFB89B62), r * 0.32f, c)
    drawCircle(Color(0xFF141414), r * 0.15f, c)
    drawCircle(Color(0x88FFFFFF), r * 0.10f, Offset(cx - r * 0.14f, cy - r * 0.14f))
}

/** 主导轴：高光不锈钢转轴 + 底座法兰。 */
fun DrawScope.capstan(cx: Float, cy: Float, r: Float) {
    val c = Offset(cx, cy)
    drawCircle(Color(0x55000000), r * 1.25f, Offset(cx + hairPx(), cy + hairPx()))
    drawPath(polygon(cx, cy, r, 6, 0f), Color(0xFF8A8882))
    drawPath(polygon(cx, cy, r, 6, 0f), Color(0x55FFFFFF), style = Stroke(hairPx()))
    drawCircle(Color(0xFFE8E6DF), r * 0.56f, c)
    drawCircle(Color(0xFF1A1A1A), r * 0.24f, c)
}

/** 导带轮：车削铝合金沟槽轮。 */
fun DrawScope.guideRing(cx: Float, cy: Float, r: Float) {
    val c = Offset(cx, cy)
    drawCircle(Color(0x66000000), r * 1.12f, Offset(cx + hairPx(), cy + hairPx()))
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFFDAD8D0),
            0.7f to Color(0xFF8E8C85),
            1f to Color(0xFF4A4945),
            center = Offset(cx - r * 0.2f, cy - r * 0.2f),
            radius = r * 1.3f,
        ),
        r, c,
    )
    drawCircle(Color(0x55FFFFFF), r, c, style = Stroke(hairPx()))
    drawCircle(Color(0xFF121212), r * 0.42f, c)
}

/** 带盘轴（空井时留在轮毂位置）：防滑黑胶垫盘 + 六角驱动齿座 + 中心精钢定位柱。 */
fun DrawScope.reelTable(cx: Float, cy: Float, r: Float) {
    val c = Offset(cx, cy)
    // 底座暗盘
    drawCircle(Color(0x88000000), r * 1.08f, Offset(cx, cy + hairPx() * 2f))
    drawCircle(Color(0xFF141414), r, c)
    drawCircle(Color(0x38FFFFFF), r, c, style = Stroke(1.2f * density))
    // 内部金属浅环
    drawCircle(Color(0x22FFFFFF), r * 0.72f, c, style = Stroke(hairPx()))
    // 六角齿轮座
    val hex = polygon(cx, cy, r * 0.44f, 6, 0f)
    drawPath(hex, Color(0xFFA8A69E))
    drawPath(hex, Color(0x66FFFFFF), style = Stroke(hairPx()))
    // 中心轴芯
    drawCircle(Color(0xFF1E1E1E), r * 0.20f, c)
    drawCircle(Color(0x99FFFFFF), r * 0.08f, Offset(cx - r * 0.06f, cy - r * 0.06f))
}
