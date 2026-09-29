package com.augustana.tfold.ui

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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import com.augustana.tfold.model.BLANK_TAPE_ID
import com.augustana.tfold.model.CoverStyle
import com.augustana.tfold.model.Tape
import com.augustana.tfold.ui.parts.Align
import com.augustana.tfold.ui.parts.CASSETTE_IN_BOX
import com.augustana.tfold.ui.parts.CASSETTE_IN_BOX_X
import com.augustana.tfold.ui.parts.HUB_R
import com.augustana.tfold.ui.parts.HUB_V_BOT
import com.augustana.tfold.ui.parts.HUB_V_TOP
import com.augustana.tfold.ui.parts.hair
import com.augustana.tfold.ui.parts.hairPx
import com.augustana.tfold.ui.parts.label
import com.augustana.tfold.ui.parts.text
import com.augustana.tfold.ui.parts.textClipped
import kotlin.math.PI
import kotlin.math.cos
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
 * J 卡（盒面）—— 照独立厂牌磁带的版式语言做（参考实拍：NMR / UMBOR 那一类）。
 *
 *  ① **图**：有内嵌封面（或用户给空白带挑的那张）就**铺满**图片区（cover），
 *     没有就把构成法的图案印在白纸上 —— 出厂那盘空白带走的就是后一条：一张干净的空白 J 卡。
 *  ② **文字**：全部排在封面**之外**那条纸带上，一个字都不压到图上 ——
 *     艺术家（大写宽字距）+ 专辑名（中号，超长裁省略号）
 *     + 右侧规格（`TYPE I · BLANK`、轨数）+ 右下角一枚墨色块。
 *
 * 两条都是"踩过才知道"的：
 *  · 图从 contain 改回 cover：contain 在 1:1.32 的竖幅 J 卡上等于上下两条大留白，图缩成一小块；
 *  · 左上角那枚带子 id 的目录号小签已删除 —— 它跟封面抢位置，用户要的是干净封面。
 */
private fun DrawScope.drawJCard(
    tape: Tape,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    alpha: Float,
    art: ImageBitmap?,
    tm: TextMeasurer,
    trackCount: Int,
) {
    val cr = CornerRadius(w * 0.018f)
    val ink = tape.art.getOrElse(1) { Color(0xFF2F4A3C) }
    val paperTop = Color(0xFFFAF9F5)
    val paperBot = Color(0xFFECEAE3)
    val pad = w * 0.075f

    // 纸
    drawRoundRect(
        Brush.verticalGradient(0f to paperTop, 1f to paperBot),
        Offset(x, y), Size(w, h), cr, alpha = alpha,
    )

    val stripH = h * 0.150f          // 文字带
    val imgH = h - stripH

    // ---- ① 图：**铺满图片区**（cover：短边贴边，多出来的裁掉）
    //
    // 中间试过一版"整张放进去、不裁"（contain）：理由是很多封面自己印着专辑名，
    // 铺满会切掉半行。但盒面是 1:1.32 的竖幅、专辑封面大多是方的 —— 一 contain
    // 就是上下两条大留白，封面缩成中间一小块，"满"的观感全没了。
    // 用户看下来要的还是铺满：图就是这一张，边界交给纸带（文字在封面之外，见 ②）。
    if (art != null) {
        clipRect(x, y, x + w, y + imgH) {
            val s = maxOf(w / art.width, imgH / art.height)   // cover：短边铺满，多出来的裁掉
            val dw = art.width * s
            val dh = art.height * s
            drawImage(
                art,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(art.width, art.height),
                dstOffset = IntOffset((x + (w - dw) / 2f).roundToInt(), (y + (imgH - dh) / 2f).roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
        }
    } else {
        val m = w * 0.10f
        paperArt(tape, x + m, y + m * 0.75f, w - m * 2f, imgH - m * 1.5f, alpha)
    }

    // （原来这里贴着一枚"目录号小签"—— 带子 id 压在封面左上角。已去掉：
    //   它跟封面争夺同一块位置，用户要的是一张干净的封面。）

    // ---- ② 文字带：封面之外的一条纸 —— 我们的排版只在这条带上做，绝不压到图上
    val bandY = y + imgH
    drawRect(Brush.verticalGradient(0f to paperTop, 1f to paperBot), Offset(x, bandY), Size(w, stripH), alpha = alpha)
    drawLine(Color(0x33000000), Offset(x, bandY), Offset(x + w, bandY), strokeWidth = hairPx(), alpha = alpha)

    val rightW = w * 0.26f
    val leftW = w - pad * 2f - rightW
    val artistSize = w * 0.040f
    val titleSize = w * 0.052f

    if (tape.artist.isNotBlank()) {
        label(tm, tape.artist.uppercase(), x + pad, bandY + stripH * 0.30f, artistSize, C.SteelDeep, alpha = alpha)
    }
    textClipped(
        tm, tape.title, x + pad, bandY + stripH * 0.66f, titleSize, ink, leftW,
        weight = FontWeight.Medium, alpha = alpha,
    )
    // 右侧规格：`TYPE I · 9 MIN` + 轨数
    text(
        tm, tape.typeLabel, x + w - pad, bandY + stripH * 0.34f, artistSize * 0.82f, C.SteelDeep,
        align = Align.Right, alpha = alpha * 0.9f,
    )
    val tk = when {
        trackCount <= 0 -> "BLANK"
        trackCount == 1 -> "1 TRACK"
        else -> "$trackCount TRACKS"
    }
    text(
        tm, tk, x + w - pad, bandY + stripH * 0.70f, artistSize * 0.82f, C.SteelDeep,
        align = Align.Right, alpha = alpha * 0.9f,
    )
    // 右下角一枚墨色块 —— 这一盘的签名色
    drawRect(
        ink,
        Offset(x + w - pad - w * 0.085f, bandY + stripH * 0.88f),
        Size(w * 0.085f, h * 0.009f),
        alpha = alpha * 0.9f,
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

    // 出厂那盘空白带：**不印任何图案** —— 它就是一张还没写过的 J 卡。
    // 几道格线（真空白带上留给你手写标题的那几行）+ 左上角两枚规格色标。
    // 别的带子即使抽不到封面也有构成法兜底，只有这一盘该是"空白"的。
    if (tape.id == BLANK_TAPE_ID) {
        val rule = Color(0x2E9A9890)
        for (i in 1..4) {
            val ly = y + h * (0.14f + i * 0.175f)
            hair(x + w * 0.015f, ly, x + w * 0.985f, ly, rule, alpha = alpha)
        }
        drawRect(ink, Offset(x + w * 0.015f, y + h * 0.048f), Size(w * 0.26f, h * 0.018f), alpha = alpha)
        drawRect(
            ink, Offset(x + w * 0.015f, y + h * 0.078f), Size(w * 0.13f, h * 0.018f),
            alpha = alpha * 0.45f,
        )
        return
    }

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
 *  - `open = 1`：**正面（印着图案的那块壳）绕左缘向观众转开 ≈48°** ——
 *    越开越被透视压扁（自由边离眼睛更近，所以略高一点），
 *    它让出来的右边就是**里面**：空腔 + 压出来的模位（带子已经取走了）
 *  - 中间值：盖从合到开地掀起来，压扁与内腔露出来同时发生
 *
 * 两块料，边界清清楚楚：
 *  · **盖板（正面）**：动的是它。纸上的图案没变形，只是视角把它压扁了 ——
 *    所以旁边必须有"里面"作证（曾经只把盒体收窄到 62%、旁边什么都没有 → "封面被挤压"）
 *  · **盒体（里面）**：壳的边框尺寸恒定，里面是深腔：两个轮毂凹槽 + 中间那道窗，
 *    带子取走后只剩模子 —— 一眼就知道"盒子开着，带子已经拿出去了"
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
    /** 盒面 J 卡上那些"这盘带自己的"文字要用它排版；不传就退回无字版式。 */
    tm: TextMeasurer? = null,
    /** 这盘带里有几首歌（`BLANK` / `1 TRACK` / `N TRACKS`）。 */
    trackCount: Int = 0,
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

    // 盖板绕左缘转开的角度（≈48°）→ 表观宽度被透视压扁；自由边离眼睛更近，所以略高
    val ang = (PI * 0.306f * e).toFloat()
    val cw = w * cos(ang)
    val freeX = x + cw
    val grow = 1f + 0.055f * e
    val inset = w * 0.050f

    // ---- 盒体（壳）：尺寸恒定。开盒动的只是前面那块盖板
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color(0x99FFFFFF),
            0.35f to Color(0x26FFFFFF),
            1f to Color(0x1F000000),
        ),
        Offset(x, y), Size(w, h), cr, alpha = alpha,
    )

    // ---- 里面：空腔。四周留出壳壁，腔底是压出来的模位 —— 两个轮毂凹槽 + 中间那道窗
    val ci = w * 0.032f
    val cav = Size(w - ci * 2f, h - ci * 2f)
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color(0xFF767165),
            0.40f to Color(0xFF4A473F),
            1f to Color(0xFF2A2926),
        ),
        Offset(x + ci, y + ci), cav, CornerRadius(crf * 0.6f), alpha = alpha,
    )
    // 模位偏在「掀开后真正露得出来的那一侧」—— 居中会被盖板整个挡回去，白做。
    // 位置与孔距**按躺在盒里的那盘带推**（CASSETTE_IN_BOX / CASSETTE_IN_BOX_X）：
    // 带子在盒里只有 0.76 盒宽，所以它的两个轮毂落在 0.5 ∓ 0.212×0.76 处、中心在 0.60 盒宽 ——
    // 不能再用带子本体（1.0 盒宽）的 0.288 / 0.712 去放，那样孔距会宽出 0.102 盒高，孔对不上。
    val bayX = x + w * CASSETTE_IN_BOX_X
    val bayR = w * CASSETTE_IN_BOX * HUB_R * 1.25f
    listOf(HUB_V_TOP, HUB_V_BOT).forEach { v ->
        val top = y + h * (0.5f + (v - 0.5f) * CASSETTE_IN_BOX) - bayR
        // 凹槽：外圈一道亮沿，槽底沉下去，正中一只毂芯
        drawOval(Color(0x59000000), Offset(bayX - bayR, top), Size(bayR * 2f, bayR * 2f), alpha = alpha)
        drawOval(
            Color(0x47FFFFFF), Offset(bayX - bayR, top), Size(bayR * 2f, bayR * 2f),
            alpha = alpha, style = Stroke(hairPx()),
        )
        drawOval(
            Color(0x40000000), Offset(bayX - bayR * 0.78f, top + bayR * 0.22f),
            Size(bayR * 1.56f, bayR * 1.56f), alpha = alpha,
        )
        drawCircle(
            Color(0x66FFFFFF), bayR * 0.28f, Offset(bayX, top + bayR),
            alpha = alpha, style = Stroke(hairPx()),
        )
    }
    // 中央那道窗（真实带盒正面的透明窗，印在腔底）：宽高也按盒内带子收一圈
    val winTL = Offset(bayX - w * CASSETTE_IN_BOX * 0.21f, y + h * (0.5f - CASSETTE_IN_BOX * 0.105f))
    val winSize = Size(w * CASSETTE_IN_BOX * 0.42f, h * CASSETTE_IN_BOX * 0.21f)
    val winCr = CornerRadius(w * 0.012f)
    drawRoundRect(Color(0x73000000), winTL, winSize, winCr, alpha = alpha)
    drawRoundRect(Color(0x4DFFFFFF), winTL, winSize, winCr, alpha = alpha, style = Stroke(hairPx()))
    // 腔壁：里端（远）更暗，靠铰链这一侧是一道亮壁
    drawRect(
        Brush.horizontalGradient(
            0f to Color(0x14000000), 0.35f to Color(0x47000000), 1f to Color(0x8C000000),
        ),
        Offset(x + ci, y + ci), cav, alpha = alpha,
    )
    drawRect(
        Brush.horizontalGradient(0f to Color(0x66FFFFFF), 1f to Color.Transparent),
        Offset(x + ci, y + ci), Size(w * 0.05f, cav.height), alpha = alpha * 0.8f,
    )

    // ---- 壳的边框：整圈壳厚（顶光渐变）+ 斜向 gloss
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

    // ---- 盖板（正面 = 白纸 J 卡）：绕左缘转开，越开越窄、自由边略高
    //      变换内画的就是原来那张闭盒，只是被压扁 —— 纸和图案一个点都不改
    withTransform({
        translate(x, y + h * 0.5f)
        scale(cw / w, grow, pivot = Offset.Zero)
        translate(0f, -h * 0.5f)
    }) {
        drawRoundRect(
            Brush.verticalGradient(
                0f to Color(0x99FFFFFF),
                0.35f to Color(0x26FFFFFF),
                1f to Color(0x1F000000),
            ),
            Offset(0f, 0f), Size(w, h), cr, alpha = alpha,
        )
        // 盖板上印的是这一盘带自己的 J 卡：真有封面图就铺满，文字带写它自己的
        // 艺术家/专辑/规格/轨数 —— 没有 TextMeasurer 时退回无字版（老调用点不会崩）
        val cardW = w - inset * 2f
        val cardH = h - inset * 1.6f
        if (tm != null) {
            drawJCard(tape, inset, inset * 0.8f, cardW, cardH, alpha, art, tm, trackCount)
        } else {
            drawRoundRect(
                Brush.verticalGradient(0f to Color(0xFFFAF9F5), 1f to Color(0xFFECEAE3)),
                Offset(inset, inset * 0.8f), Size(cardW, cardH),
                CornerRadius(cardW * 0.018f), alpha = alpha,
            )
            paperArt(tape, inset + cardW * 0.10f, inset * 0.8f + cardH * 0.075f, cardW * 0.80f, cardH * 0.85f, alpha)
        }
        // 纸坐在壳里：上、左两道暗边，下、右两道亮边
        drawRect(
            Brush.verticalGradient(0f to Color(0x33000000), 1f to Color.Transparent),
            Offset(inset, inset * 0.8f), Size(w - inset * 2f, h * 0.045f), alpha = alpha,
        )
        drawRect(
            Brush.horizontalGradient(0f to Color(0x29000000), 1f to Color.Transparent),
            Offset(inset, inset * 0.8f), Size(w * 0.055f, h - inset * 1.6f), alpha = alpha,
        )
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x38FFFFFF)),
            Offset(inset, h - inset * 1.6f - h * 0.045f), Size(w - inset * 2f, h * 0.045f),
            alpha = alpha,
        )
    }

    // ---- 转开带来的立体：盖板靠铰链一侧压暗、自由边一侧亮；它还在内腔上投一道影
    if (e > 0.004f) {
        // 盖板是斜着的：靠铰链一侧背光
        drawRect(
            Brush.horizontalGradient(
                0f to Color(0x4D000000), 0.55f to Color(0x14000000), 1f to Color(0x24FFFFFF),
            ),
            Offset(x, y - h * 0.028f), Size(cw, h * 1.056f), alpha = alpha * e,
        )
        // 盖板投在内腔上的影
        drawRect(
            Brush.horizontalGradient(0f to Color(0x73000000), 1f to Color.Transparent),
            Offset(freeX, y + ci), Size(w * 0.11f, cav.height), alpha = alpha * e,
        )
        // 自由边：亚克力切开的那道厚边（外暗内亮）
        drawLine(
            Color(0x73FFFFFF), Offset(freeX, y - h * 0.028f), Offset(freeX, y + h * 1.028f),
            strokeWidth = w * 0.019f, alpha = alpha * e,
        )
        drawLine(
            Color(0x59000000), Offset(freeX + w * 0.011f, y - h * 0.028f),
            Offset(freeX + w * 0.011f, y + h * 1.028f),
            strokeWidth = hairPx(), alpha = alpha * e,
        )
        drawPath(
            rrPath(x, y - h * 0.028f, cw, h * 1.056f, crf * 0.6f),
            Color(0x59FFFFFF), alpha = alpha * e, style = Stroke(hairPx()),
        )
        // 铰链折痕：盖与盒体夹出的那道亮线
        drawLine(
            Color(0x8CFFFFFF), Offset(x + 1.5f * density, y), Offset(x + 1.5f * density, y + h),
            strokeWidth = 1.5f * density, alpha = alpha * e,
        )
        // 盖板上那两颗镀铬螺丝（开到头才全亮）
        fun coverPt(ux: Float, vy: Float) = Offset(
            x + cw * ux,
            y + h * 0.5f + (vy - 0.5f) * h * grow,
        )
        val screwA = ((e - 0.35f) / 0.65f).coerceIn(0f, 1f)
        if (screwA > 0.01f) {
            val s1 = coverPt(0.87f, 0.085f)
            val s2 = coverPt(0.87f, 0.915f)
            chromeScrew(s1.x, s1.y, w * 0.019f, alpha * screwA)
            chromeScrew(s2.x, s2.y, w * 0.019f, alpha * screwA)
        }
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
