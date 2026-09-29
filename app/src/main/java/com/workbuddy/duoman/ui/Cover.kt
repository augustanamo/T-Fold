package com.workbuddy.duoman.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.workbuddy.duoman.model.CoverStyle
import com.workbuddy.duoman.model.Tape
import com.workbuddy.duoman.ui.parts.hair
import com.workbuddy.duoman.ui.parts.hairPx
import kotlin.math.roundToInt

/**
 * 盒面 —— **不印一个字**。
 *
 * 绑了曲目的磁带：盒面就是歌曲内嵌的封面图（居中裁切铺满）。
 * 没绑曲目的：四盘带各用一种构成法（地平线 / 网格 / 竖条 / 轨道），
 * 凭配色和构成认盘，不凭标题。
 */
fun DrawScope.drawCover(
    tape: Tape,
    w: Float,
    h: Float,
    alpha: Float = 1f,
    art: ImageBitmap? = null,
) {
    val a0 = tape.art.getOrElse(0) { Color(0xFFE7DFD2) }
    val a1 = tape.art.getOrElse(1) { Color(0xFF2B2B28) }
    val a2 = tape.art.getOrElse(2) { Color(0xFFB9B7B0) }

    drawRect(a0, Offset(0f, 0f), Size(w, h), alpha = alpha)

    if (art != null) {
        // 歌曲自带封面：居中裁切铺满整个盒面
        clipRect(0f, 0f, w, h) {
            val scale = maxOf(w / art.width, h / art.height)
            val dw = (art.width * scale).roundToInt()
            val dh = (art.height * scale).roundToInt()
            drawImage(
                art,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(art.width, art.height),
                dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
                dstSize = IntSize(dw, dh),
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
        }
    } else {
        when (tape.style) {
            CoverStyle.HORIZON -> {
                drawCircle(a1, w * 0.26f, Offset(w * 0.50f, h * 0.46f), alpha = alpha)
                drawRect(a2, Offset(0f, h * 0.72f), Size(w, h * 0.28f), alpha = alpha)
                hair(w * 0.06f, h * 0.60f, w * 0.94f, h * 0.60f, a1, alpha = alpha * 0.35f)
                for (i in 0..3) {
                    val y = h * (0.80f + i * 0.05f)
                    hair(w * 0.06f, y, w * 0.94f, y, a1, alpha = alpha * 0.14f)
                }
            }

            CoverStyle.GRID -> {
                for (i in 1..3) hair(w * i / 4f, 0f, w * i / 4f, h, a2, alpha = alpha * 0.28f)
                for (i in 1..4) hair(0f, h * i / 5f, w, h * i / 5f, a2, alpha = alpha * 0.22f)
                drawRect(a1, Offset(w * 0.17f, h * 0.26f), Size(w * 0.50f, h * 0.48f), alpha = alpha)
            }

            CoverStyle.STRIPES -> {
                for (i in 0..9) {
                    hair(
                        w * (0.06f + i * 0.10f), 0f, w * (0.06f + i * 0.10f), h, a2,
                        alpha = alpha * 0.32f,
                    )
                }
                drawRect(a1, Offset(w * 0.56f, 0f), Size(w * 0.13f, h), alpha = alpha)
            }

            CoverStyle.ORBIT -> {
                drawCircle(
                    a1, w * 0.29f, Offset(w * 0.47f, h * 0.53f),
                    alpha = alpha, style = Stroke(1.5f * density),
                )
                drawCircle(a2, w * 0.075f, Offset(w * 0.74f, h * 0.28f), alpha = alpha)
                hair(w * 0.04f, h * 0.92f, w * 0.96f, h * 0.14f, a1, alpha = alpha * 0.45f)
            }
        }
        // 一道压在封面上方的细色带：四种构成法的公共元素，也是"这一盘"的签名
        drawRect(a1, Offset(0f, h * 0.955f), Size(w, h * 0.045f), alpha = alpha * 0.9f)
    }

    drawRoundRect(
        Color(0x33000000), Offset(0f, 0f), Size(w, h), CornerRadius(1.5f * density),
        alpha = alpha, style = Stroke(hairPx()),
    )
}

/**
 * 白纸 J 卡（例图右侧那张）：白纸上印一枚单色图案，下方几行曲目细线，
 * 右下一枚签名色块。绑了曲目就把内嵌封面图印上去（留白 contain），
 * 没绑就用构成法以单色墨印 —— 和真 J 卡一样，图案是"印在纸上"的。
 */
private fun DrawScope.drawJCard(
    tape: Tape,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    alpha: Float,
    art: ImageBitmap?,
) {
    val cr = CornerRadius(w * 0.018f)
    val ink = tape.art.getOrElse(1) { Color(0xFF2F4A3C) }

    // 纸：微暖的白，底部略沉
    drawRoundRect(
        Brush.verticalGradient(0f to Color(0xFFFAF9F5), 1f to Color(0xFFECEAE3)),
        Offset(x, y), Size(w, h), cr, alpha = alpha,
    )

    // 图案区：居中留白，像贴上去的印刷块
    val ax = x + w * 0.115f
    val ay = y + h * 0.085f
    val aw = w * 0.77f
    val ah = h * 0.545f
    if (art != null) {
        clipRect(ax, ay, ax + aw, ay + ah) {
            val s = minOf(aw / art.width, ah / art.height)
            val dw = art.width * s
            val dh = art.height * s
            drawImage(
                art,
                dstOffset = IntOffset((ax + (aw - dw) / 2f).roundToInt(), (ay + (ah - dh) / 2f).roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
        }
    } else {
        paperArt(tape, ax, ay, aw, ah, alpha)
    }

    // 曲目细线：图案下方两列短灰线（无字，只是"印了字"的密度感）
    val lineInk = Color(0x4D9A9890)
    val colX1 = x + w * 0.115f
    val colX2 = x + w * 0.60f
    for (i in 0 until 5) {
        val ly = ay + ah + h * 0.045f + i * h * 0.048f
        if (ly > y + h * 0.94f) break
        drawLine(lineInk, Offset(colX1, ly), Offset(colX1 + w * (if (i % 2 == 0) 0.30f else 0.22f), ly), strokeWidth = hairPx(), alpha = alpha)
        if (i < 3) drawLine(lineInk, Offset(colX2, ly), Offset(colX2 + w * 0.16f, ly), strokeWidth = hairPx(), alpha = alpha)
    }

    // 右下一枚签名色块 —— 这一盘的墨色
    drawRect(
        ink,
        Offset(x + w * 0.74f, y + h * 0.905f),
        Size(w * 0.115f, h * 0.020f),
        alpha = alpha,
    )

    // 纸边
    drawRoundRect(
        Color(0x26000000), Offset(x, y), Size(w, h), cr,
        alpha = alpha, style = Stroke(hairPx()),
    )
}

/** J 卡上的单色印刷图案：构成法不变，但只上一层墨，白纸露底。 */
private fun DrawScope.paperArt(tape: Tape, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
    val ink = tape.art.getOrElse(1) { Color(0xFF2F4A3C) }
    val soft = Color(0x339A9890)
    when (tape.style) {
        CoverStyle.HORIZON -> {
            drawCircle(ink, w * 0.30f, Offset(x + w * 0.50f, y + h * 0.40f), alpha = alpha)
            hair(x + w * 0.06f, y + h * 0.78f, x + w * 0.94f, y + h * 0.78f, ink, alpha = alpha * 0.8f)
        }

        CoverStyle.GRID -> {
            for (i in 1..3) hair(x + w * i / 4f, y, x + w * i / 4f, y + h, soft, alpha = alpha)
            for (i in 1..4) hair(x, y + h * i / 5f, x + w, y + h * i / 5f, soft, alpha = alpha)
            drawRect(ink, Offset(x + w * 0.20f, y + h * 0.22f), Size(w * 0.44f, h * 0.46f), alpha = alpha)
        }

        CoverStyle.STRIPES -> {
            for (i in 0..6) {
                hair(
                    x + w * (0.08f + i * 0.14f), y, x + w * (0.08f + i * 0.14f), y + h,
                    soft, alpha = alpha,
                )
            }
            drawRect(ink, Offset(x + w * 0.52f, y), Size(w * 0.14f, h), alpha = alpha)
        }

        CoverStyle.ORBIT -> {
            drawCircle(
                ink, w * 0.32f, Offset(x + w * 0.48f, y + h * 0.52f),
                alpha = alpha, style = Stroke(1.5f * density),
            )
            drawCircle(ink, w * 0.075f, Offset(x + w * 0.78f, y + h * 0.22f), alpha = alpha)
            hair(x + w * 0.05f, y + h * 0.95f, x + w * 0.95f, y + h * 0.10f, ink, alpha = alpha * 0.7f)
        }
    }
}

/**
 * 磁带包装盒 —— **开合是连续的**（open 0→1）：
 *
 *  - `open = 0`：清壳合着，盒面就是白纸 J 卡（= 之前那张闭盒）
 *  - `open = 1`：盖绕盒体右缘向右开到底，盒腔空着（带子已在卡座里）
 *  - 中间值：盖正从右缘向右转出、边转边收窄、远端下沉（透视），
 *    **盒体与 J 卡的尺寸从头到尾不变** —— 真实包装就是这样：
 *    开盒动的是盖，坐壳里的纸不会跟着缩
 *
 * 装带时由状态机把它从 0 拉到 1（开盒 → 磁带飞出），退带时倒放（磁带落回 → 合盖）。
 */
fun DrawScope.drawCoverCard(
    tape: Tape,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    open: Float = 0f,
    alpha: Float = 1f,
    art: ImageBitmap? = null,
) {
    val e = open.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) } // smoothstep：起收都软
    val crf = w * 0.035f
    val cr = CornerRadius(crf)

    // ---- 分层软落影（没有模糊，用三层递进的透明圆角矩形骗出柔和感）
    for (i in 1..3) {
        val grow = i * w * 0.010f
        drawRoundRect(
            Color(0xFF000000),
            Offset(x + w * 0.010f - grow / 2f, y + h * 0.014f + grow * 0.4f),
            Size(w + grow, h + grow * 0.6f),
            CornerRadius(crf + grow / 2f),
            alpha = alpha * (0.16f / (i * i)),
        )
    }

    // 盖向右转开时，卡槽右侧会露出来 —— 铺一层"盒子摊开的影子"垫底
    val lidLen = w * 0.52f * e
    if (e > 0.01f) {
        drawRoundRect(
            Brush.verticalGradient(0f to Color(0x29000000), 1f to Color(0x14000000)),
            Offset(x, y), Size(w + lidLen, h), cr, alpha = alpha * e,
        )
    }

    // ---- 盒体：尺寸恒定，开盒动的只是盖
    val hingeX = x + w
    // 清壳：一层塑料光，底下透出库的钢蓝
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color(0x99FFFFFF),
            0.35f to Color(0x26FFFFFF),
            1f to Color(0x1F000000),
        ),
        Offset(x, y), Size(w, h), cr, alpha = alpha,
    )

    // ---- J 卡（白纸印图案）坐在壳里
    val inset = w * 0.050f
    val jx = x + inset
    val jy = y + inset * 0.8f
    val jw = w - inset * 2f
    val jh = h - inset * 1.6f
    drawJCard(tape, jx, jy, jw, jh, alpha, art)
    // 内阴影：上、左两道暗边，下、右两道亮边 —— 卡纸坐在壳里
    drawRect(
        Brush.verticalGradient(0f to Color(0x33000000), 1f to Color.Transparent),
        Offset(jx, jy), Size(jw, jh * 0.08f), alpha = alpha,
    )
    drawRect(
        Brush.horizontalGradient(0f to Color(0x29000000), 1f to Color.Transparent),
        Offset(jx, jy), Size(jw * 0.06f, jh), alpha = alpha,
    )
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x38FFFFFF)),
        Offset(jx, jy + jh * 0.94f), Size(jw, jh * 0.06f), alpha = alpha,
    )

    // ---- 空腔：带子被拿走了，原来那块留一片极淡的影（随开合淡入）
    if (e > 0.01f) {
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.35f to Color(0x1A000000),
                0.75f to Color(0x14000000),
                1f to Color.Transparent,
            ),
            Offset(jx, jy + jh * 0.16f), Size(jw, jh * 0.62f),
            alpha = alpha * e,
        )
        // 开口边（右侧腔壁）一道由内而外的暗
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color(0x3D000000)),
            Offset(hingeX - w * 0.16f, y), Size(w * 0.16f, h),
            alpha = alpha * e,
        )
    }

    // ---- 塑料壳：边缘一圈壳厚（顶光渐变）+ 斜向 gloss
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color(0xB3FFFFFF),
            0.5f to Color(0x42FFFFFF),
            1f to Color(0x2E000000),
        ),
        Offset(x, y), Size(w, h), cr,
        alpha = alpha, style = Stroke(w * 0.016f),
    )
    clipPath(rrPath(x, y, w, h, crf)) {
        rotate(-24f, Offset(x + w * 0.5f, y + h * 0.5f)) {
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to Color(0x33FFFFFF),
                    1f to Color.Transparent,
                ),
                Offset(x + w * 0.10f, y - h * 0.3f), Size(w * 0.20f, h * 1.6f),
                alpha = alpha,
            )
        }
    }
    // 盒脊合页：合着时是左侧那道脊（一凸一凹），开盒后淡出
    if (e < 0.98f) {
        val k = 1f - e
        val spineX = x + w * 0.052f
        drawLine(
            Color(0x73FFFFFF), Offset(spineX, y + h * 0.025f), Offset(spineX, y + h * 0.975f),
            strokeWidth = 1.5f * density, alpha = alpha * k,
        )
        drawLine(
            Color(0x3D000000), Offset(spineX + 1.8f * density, y + h * 0.025f),
            Offset(spineX + 1.8f * density, y + h * 0.975f),
            strokeWidth = 1.5f * density, alpha = alpha * k,
        )
    }
    // 外缘收光
    drawRoundRect(
        Color(0x59FFFFFF), Offset(x, y), Size(w, h), cr,
        alpha = alpha, style = Stroke(hairPx()),
    )

    // ---- 盖：绕盒体右缘向右转出（远端收窄 + 下沉 = 倾斜），长度随开度长出
    if (e > 0.004f) {
        val nearTop = y + h * 0.010f
        val nearBot = y + h * 0.990f
        val farTop = y + h * (0.010f + 0.105f * e)
        val farBot = y + h * (0.990f - 0.065f * e)
        val farX = hingeX + lidLen
        val fade = (e * 4f).coerceAtMost(1f) // 刚离开盒体时快速显形，不然像凭空冒出
        val lid = Path().apply {
            moveTo(hingeX, nearTop)
            lineTo(hingeX, nearBot)
            lineTo(farX, farBot)
            lineTo(farX, farTop)
            close()
        }
        drawPath(
            lid,
            Brush.horizontalGradient(
                0f to Color(0xFFF2F1EC),
                1f to Color(0xFFD6D4CC),
                startX = hingeX, endX = farX,
            ),
            alpha = alpha * fade,
        )
        clipPath(lid) {
            rotate(-28f, Offset(hingeX + lidLen * 0.35f, y + h * 0.5f)) {
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to Color(0x2EFFFFFF),
                        1f to Color.Transparent,
                    ),
                    Offset(hingeX + lidLen * 0.08f, y - h * 0.3f), Size(lidLen * 0.34f, h * 1.6f),
                    alpha = alpha * fade,
                )
            }
            // 盖的两个外角镀铬螺丝：开到头才全亮
            val screwA = ((e - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (screwA > 0.01f) {
                val sx = farX - lidLen * 0.07f
                chromeScrew(sx, farTop + h * 0.030f, w * 0.020f, alpha * screwA)
                chromeScrew(sx, farBot - h * 0.030f, w * 0.020f, alpha * screwA)
            }
        }
        drawPath(lid, Color(0x40000000), alpha = alpha * fade, style = Stroke(hairPx()))
        // 盖的远端厚沿：外暗内亮
        drawLine(
            Color(0x59000000), Offset(farX, farTop), Offset(farX, farBot),
            strokeWidth = w * 0.012f, alpha = alpha * fade,
        )
        drawLine(
            Color(0x73FFFFFF), Offset(farX - w * 0.012f, farTop + h * 0.012f),
            Offset(farX - w * 0.012f, farBot - h * 0.012f),
            strokeWidth = hairPx(), alpha = alpha * fade,
        )
        // 铰链：左暗右亮，夹住轴
        drawLine(
            Color(0x4D000000), Offset(hingeX, nearTop), Offset(hingeX, nearBot),
            strokeWidth = hairPx(), alpha = alpha * fade,
        )
        drawLine(
            Color(0x8CFFFFFF), Offset(hingeX + 1.5f * density, nearTop), Offset(hingeX + 1.5f * density, nearBot),
            strokeWidth = 1.5f * density, alpha = alpha * fade,
        )
    }
}

/** 镀铬螺丝：径向渐变 + 一字槽 + 高光点。 */
private fun DrawScope.chromeScrew(cx: Float, cy: Float, r: Float, alpha: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFFF2F1ED),
            1f to Color(0xFF6E6C66),
            center = Offset(cx - r * 0.3f, cy - r * 0.3f),
            radius = r * 1.4f,
        ),
        r, Offset(cx, cy), alpha = alpha,
    )
    drawCircle(Color(0x66000000), r, Offset(cx, cy), alpha = alpha * 0.7f, style = Stroke(hairPx()))
    drawLine(Color(0x99000000), Offset(cx - r * 0.58f, cy), Offset(cx + r * 0.58f, cy), strokeWidth = hairPx(), alpha = alpha)
    drawCircle(Color(0xB3FFFFFF), r * 0.16f, Offset(cx - r * 0.32f, cy - r * 0.36f), alpha = alpha)
}

/** 圆角矩形 Path（gloss 裁剪用）。 */
private fun rrPath(x: Float, y: Float, w: Float, h: Float, r: Float): Path =
    Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y, x + w, y + h, r, r)) }
