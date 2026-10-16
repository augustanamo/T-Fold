package com.augustana.tfold.ui.parts

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.augustana.tfold.model.BLANK_TAPE_ID
import com.augustana.tfold.model.Tape
import com.augustana.tfold.ui.C
import com.augustana.tfold.ui.mix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

const val CASSETTE_H_RATIO = 1.56f
private const val R_MAX = 0.325f
private const val R_MIN = 0.150f
const val HUB_R = 0.115f

/** 两个轮毂中心的高度（相对带高）—— 画带子、画盒腔模位都用它，只有一处真源。 */
const val HUB_V_TOP = 0.288f
const val HUB_V_BOT = 0.712f

const val CASSETTE_IN_BOX = 0.88f
const val CASSETTE_IN_BOX_X = 0.525f

private fun chromaticOf(c: Color): Float =
    maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

val NeutralShell = Color(0xFFEDEFEE)

fun shellTintOf(tape: Tape, coverTint: Color? = null): Color {
    if (tape.id == BLANK_TAPE_ID) return NeutralShell
    val base = coverTint?.takeIf { chromaticOf(it) > 0.10f }
        ?: tape.art.maxByOrNull { chromaticOf(it) }?.takeIf { chromaticOf(it) >= 0.22f }
    var hue: Float
    var sat: Float
    if (base != null) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base.toArgb(), hsv)
        hue = hsv[0]
        sat = hsv[1]
        if (sat < 0.20f) {
            hue = (abs(tape.id.hashCode()) % 360).toFloat()
            sat = 0.46f
        }
    } else {
        hue = (abs(tape.id.hashCode()) % 360).toFloat()
        sat = 0.46f
    }
    return Color.hsv(hue.coerceIn(0f, 359.9f), sat.coerceIn(0.22f, 0.55f), 0.96f)
}

fun glowOf(tint: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(tint.toArgb(), hsv)
    if (hsv[1] < 0.06f) return Color(0xFFF4F6F6)
    return Color.hsv(hsv[0], (hsv[1] * 1.55f).coerceIn(0.30f, 0.92f), 1f)
}

fun rMaxOf(sw: Float) = R_MAX * sw

fun rMinOf(sw: Float) = R_MIN * sw

fun packRadius(tapePos: Float, sw: Float, takeUp: Boolean): Float {
    val p = tapePos.coerceIn(0f, 1f)
    return if (takeUp) mix(R_MIN, R_MAX, p) * sw else mix(R_MAX, R_MIN, p) * sw
}

data class CassetteStyle(
    val shellFill: Color,
    val shellEdge: Color,
    val ink: Color,
    val inkSoft: Color,
    val hub: Color,
    val hubEdge: Color,
    val hubHole: Color,
    val oxide: Color,
    val oxideRing: Color,
    val oxideEdge: Color,
    val path: Color,
    val screw: Color,
) {
    companion object {
        val InRecess = CassetteStyle(
            shellFill = Color(0x14FFFFFF),
            shellEdge = Color(0xB0A8A9A5),
            ink = C.Ivory,
            inkSoft = Color(0x99EDEBE4),
            hub = C.Hub,
            hubEdge = C.SteelDeep,
            hubHole = C.Ivory,
            oxide = C.Oxide,
            oxideRing = C.OxideRing,
            oxideEdge = C.OxideEdge,
            path = C.TapePath,
            screw = Color(0x99C9C7C0),
        )
    }
}

private fun rrPath(x: Float, y: Float, w: Float, h: Float, r: Float): Path =
    Path().apply { addRoundRect(RoundRect(x, y, x + w, y + h, r, r)) }

private fun convexHull(pts: List<Offset>): List<Offset> {
    if (pts.size < 3) return pts
    val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (q in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0f) lower.removeAt(lower.size - 1)
        lower.add(q)
    }
    val upper = ArrayList<Offset>()
    for (q in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0f) upper.removeAt(upper.size - 1)
        upper.add(q)
    }
    lower.removeAt(lower.size - 1)
    upper.removeAt(upper.size - 1)
    return lower + upper
}

private fun extrudeHull(sw: Float, sh: Float, r: Float, dx: Float, dy: Float): Path {
    val x0 = r; val x1 = sw - r; val y0 = r; val y1 = sh - r
    val h = convexHull(
        listOf(
            Offset(x0, y0), Offset(x1, y0), Offset(x1, y1), Offset(x0, y1),
            Offset(x0 + dx, y0 + dy), Offset(x1 + dx, y0 + dy),
            Offset(x1 + dx, y1 + dy), Offset(x0 + dx, y1 + dy),
        ),
    )
    val path = Path()
    if (h.size < 3) {
        path.addRoundRect(RoundRect(0f, 0f, sw, sh, r, r))
        return path
    }
    val n = h.size
    for (i in 0 until n) {
        val prev = h[(i - 1 + n) % n]
        val cur = h[i]
        val next = h[(i + 1) % n]
        val d1 = cur - prev
        val d2 = next - cur
        val l1 = d1.getDistance()
        val l2 = d2.getDistance()
        if (l1 < 1e-3f || l2 < 1e-3f) continue
        val t = minOf(r, 0.42f * l1, 0.42f * l2)
        val a = cur - d1 / l1 * t
        val b = cur + d2 / l2 * t
        if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
        path.quadraticBezierTo(cur.x, cur.y, b.x, b.y)
    }
    path.close()
    return path
}

fun DrawScope.drawCassette(
    w: Float,
    h: Float,
    tape: Tape,
    tapePos: Float,
    angleTop: Float,
    angleBot: Float,
    style: CassetteStyle = CassetteStyle.InRecess,
    tint: Color? = null,
    depthX: Float = 0f,
    depthY: Float = 0f,
    /** 在 Well.kt 中被 scale(-1f, 1f) 水平镜像时传 true，用于反向补偿光源方向，保持全机左上受光一致 */
    mirrored: Boolean = false,
) {
    val sw = w
    val sh = h
    val hubX = sw * 0.5f
    val topY = sh * HUB_V_TOP
    val botY = sh * HUB_V_BOT
    val hubR = sw * HUB_R
    val rTop = packRadius(tapePos, sw, takeUp = false)
    val rBot = packRadius(tapePos, sw, takeUp = true)
    val crf = sw * 0.045f
    val cr = CornerRadius(crf)
    val cutR = hubR * 1.35f
    val lightSign = if (mirrored) -1f else 1f

    // 飞行偏航比率（0 = 平放，>0 = 空中翻转），用于驱动表面玻璃高光扫动。
    // 取绝对值：飞行那盘为了配合镜像会把 depthX 传成负数，厚度依然存在，高光该照扫。
    val tiltRatio = ((abs(depthX) / (sw * 0.08f)).coerceIn(0f, 1.2f))

    val rawAccent = tape.art.getOrElse(1) { Color(0xFFE8433E) }
    val accent = run {
        val mx = maxOf(rawAccent.red, rawAccent.green, rawAccent.blue)
        val mn = minOf(rawAccent.red, rawAccent.green, rawAccent.blue)
        if (mx - mn > 0.25f && mx < 0.98f) rawAccent else Color(0xFFE8433E)
    }
    val shellPath = rrPath(0f, 0f, sw, sh, crf)
    val shellTint = tint ?: shellTintOf(tape)
    val glow = glowOf(shellTint)

    // 中央窗板（贯穿两毂的透明凸台）与带窗几何
    val stripX = sw * 0.315f
    val stripW = sw * 0.37f
    val stripTop = topY - cutR * 1.30f
    val stripBot = botY + cutR * 1.30f
    val winX = hubX - sw * 0.11f
    val winW = sw * 0.22f
    val winY = topY + cutR + sh * 0.006f
    val winH = (botY - cutR) - winY - sh * 0.006f

    val wallR = depthX > 0f      // +x 侧有厚度（侧壁挤出朝右）
    val wallL = depthX < 0f      // -x 侧有厚度（飞行那盘镜像时 depthX 取反，厚度落在左边）
    val wallD = depthY > 0f      // +y 侧有厚度（底缘侧壁）

    // ============ 0. 壳体立体挤出侧壁：亚克力多层折光 + 合模线（Parting Line） ============
    if (depthX != 0f || depthY != 0f) {
        val hull = extrudeHull(sw, sh, crf, depthX, depthY)
        val midHull = extrudeHull(sw, sh, crf, depthX * 0.52f, depthY * 0.52f)
        val wall = Path.combine(
            PathOperation.Intersect,
            Path.combine(
                PathOperation.Difference,
                Path().apply { addRect(Rect(-sw * 6f, -sh * 6f, sw * 7f, sh * 7f)) },
                shellPath,
            ),
            hull,
        )
        clipPath(wall) {
            val steps = 12
            for (i in steps downTo 1) {
                val ratio = i.toFloat() / steps
                drawPath(
                    rrPath(depthX * ratio, depthY * ratio, sw, sh, crf),
                    lerp(shellTint, Color.White, 0.55f).copy(alpha = 0.048f),
                )
            }
            // 侧壁内部染色折光感
            drawPath(hull, glow.copy(alpha = 0.20f), style = Stroke(5.5f * density))
            drawPath(hull, Color(0x22FFFFFF), style = Stroke(3.0f * density))
            // 真实磁带前后两半壳体的侧壁合模缝（Parting Seam）
            drawPath(midHull, Color(0x38000000), style = Stroke(hairPx() * 1.2f))
            drawPath(midHull, Color(0x40FFFFFF), style = Stroke(hairPx() * 0.7f))
        }
        // 远端亚克力外棱高光
        drawPath(hull, Color(0xD8FFFFFF), style = Stroke(1.4f * density))
    }

    // ============ 0.5 底壳内腔基准面（强化内部景深） ============
    clipPath(shellPath) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0x28000000),
                0.45f to Color(0x0E000000),
                1f to Color(0x32000000),
            ),
            Offset.Zero, Size(sw, sh),
        )
    }

    // ============ 1. 壳内零件（视差层） ============
    translate(depthX * 0.45f, depthY * 0.45f) {
        clipPath(shellPath) {
            // 1.1 内部注塑加强筋（Molding Ribs）与六角防擦垫片（Slip Sheet）轮廓
            val ribColor = Color(0x1CFFFFFF)
            val ribShadow = Color(0x26000000)
            listOf(sh * 0.088f, sh * 0.912f).forEach { ry ->
                drawLine(ribShadow, Offset(sw * 0.08f, ry + hairPx()), Offset(sw * 0.92f, ry + hairPx()), strokeWidth = hairPx() * 1.5f)
                drawLine(ribColor, Offset(sw * 0.08f, ry), Offset(sw * 0.92f, ry), strokeWidth = hairPx() * 1.5f)
            }
            // 超声波焊接内框线（Ultrasonic Weld Step）
            drawRoundRect(
                Color(0x16FFFFFF),
                Offset(sw * 0.042f, sh * 0.032f),
                Size(sw * 0.916f, sh * 0.936f),
                CornerRadius(crf * 0.75f),
                style = Stroke(hairPx()),
            )
            // 防擦垫片（半透明聚酯薄膜折角线）
            val sheetMarginX = sw * 0.095f
            val sheetMarginY = sh * 0.105f
            drawRoundRect(
                Color(0x14FFFFFF),
                Offset(sheetMarginX, sheetMarginY),
                Size(sw - sheetMarginX * 2f, sh - sheetMarginY * 2f),
                CornerRadius(sw * 0.08f),
                style = Stroke(hairPx()),
            )

            // 1.2 磁带走带路径（从上下带盘切线引出 -> 绕过上下导带轮 -> 贴左缘磁头开口走带）
            val rollerTopY = sh * 0.115f
            val rollerBotY = sh * 0.885f
            val rollerX = sw * 0.115f
            val rollerR = sw * 0.032f
            val headGuideX = sw * 0.022f

            val topTangentX = (hubX - rTop * 0.92f).coerceAtLeast(rollerX)
            val botTangentX = (hubX - rBot * 0.92f).coerceAtLeast(rollerX)

            val tapeRibbon = Path().apply {
                moveTo(topTangentX, topY - rTop * 0.35f)
                lineTo(rollerX, rollerTopY - rollerR * 0.85f)
                lineTo(headGuideX, sh * 0.19f)
                lineTo(headGuideX, sh * 0.81f)
                lineTo(rollerX, rollerBotY + rollerR * 0.85f)
                lineTo(botTangentX, botY + rBot * 0.35f)
            }
            drawPath(tapeRibbon, style.oxideEdge.copy(alpha = 0.88f), style = Stroke(linePx() * 1.3f, cap = StrokeCap.Round))
            // 磁带走带侧面金属微反光
            drawLine(
                Color(0x52E0D2BA),
                Offset(headGuideX + hairPx(), sh * 0.21f),
                Offset(headGuideX + hairPx(), sh * 0.79f),
                strokeWidth = hairPx(),
            )

            // 1.3 上下导带滚轮（Guide Rollers）与左侧磁头护罩铜压垫（Pressure Pad）
            listOf(rollerTopY, rollerBotY).forEach { ry ->
                drawCircle(Color(0x55000000), rollerR + hairPx() * 1.5f, Offset(rollerX, ry + hairPx()))
                drawCircle(Color(0xFFD8D6CE), rollerR, Offset(rollerX, ry))
                // 滚轮内环沟槽
                drawCircle(Color(0x38000000), rollerR * 0.70f, Offset(rollerX, ry), style = Stroke(hairPx()))
                drawCircle(Color(0xFF4A4843), rollerR * 0.40f, Offset(rollerX, ry))
                drawCircle(Color(0x88FFFFFF), rollerR, Offset(rollerX, ry), style = Stroke(hairPx()))
            }
            // 左缘开口处：磁头舱金属屏蔽罩（Mu-metal Shield）+ 磷青铜弹片 + 毛毡压垫
            val shieldY = sh * 0.42f
            val shieldH = sh * 0.16f
            drawRoundRect(
                Brush.horizontalGradient(
                    0f to Color(0xFF3E3B38),
                    0.5f to Color(0xFF242220),
                    1f to Color(0xFF161514),
                    startX = 0f,
                    endX = sw * 0.072f,
                ),
                Offset(0f, shieldY),
                Size(sw * 0.070f, shieldH),
                CornerRadius(sw * 0.01f),
            )
            drawRoundRect(
                Color(0x40FFFFFF),
                Offset(0f, shieldY),
                Size(sw * 0.070f, shieldH),
                CornerRadius(sw * 0.01f),
                style = Stroke(hairPx()),
            )
            drawLine(
                Color(0xB3D4A36A), // 磷青铜弹簧片
                Offset(sw * 0.030f, sh * 0.435f),
                Offset(sw * 0.030f, sh * 0.565f),
                strokeWidth = linePx() * 1.25f,
            )
            drawRect(
                Color(0xFF876D50), // 毛毡压块
                Offset(sw * 0.014f, sh * 0.474f),
                Size(sw * 0.020f, sh * 0.052f),
            )
        }

        // 1.4 缠绕带盘与轮毂（传入 lightSign 保证镜像时高光仍在左上）
        tapePack(hubX, topY, rTop, style, angleTop, lightSign)
        hub(hubX, topY, hubR, angleTop, style, lightSign)
        tapePack(hubX, botY, rBot, style, angleBot, lightSign)
        hub(hubX, botY, hubR, angleBot, style, lightSign)

        // 1.5 中央黑带窗（两毂之间的走带路径视窗）
        drawRoundRect(Color(0xF212100E), Offset(winX, winY), Size(winW, winH), CornerRadius(sw * 0.012f))
        drawRoundRect(
            Brush.horizontalGradient(
                0f to Color(0x90000000),
                0.25f to Color.Transparent,
                0.75f to Color.Transparent,
                1f to Color(0x90000000),
                startX = winX,
                endX = winX + winW,
            ),
            Offset(winX, winY), Size(winW, winH), CornerRadius(sw * 0.012f),
        )
        drawRect(style.path, Offset(hubX - sw * 0.009f, winY), Size(sw * 0.018f, winH))
        drawLine(
            Color(0x42FFFFFF),
            Offset(hubX - sw * 0.003f * lightSign, winY),
            Offset(hubX - sw * 0.003f * lightSign, winY + winH),
            strokeWidth = hairPx(),
        )

        // 1.6 开口边（左缘）梯形厚壁区（Head Azimuth 梯形区）与主导轴孔 + 定位孔
        val trapPath = Path().apply {
            moveTo(0f, sh * 0.22f)
            lineTo(sw * 0.090f, sh * 0.275f)
            lineTo(sw * 0.090f, sh * 0.725f)
            lineTo(0f, sh * 0.78f)
            close()
        }
        drawPath(trapPath, Color(0x18FFFFFF))
        drawPath(trapPath, Color(0x36FFFFFF), style = Stroke(hairPx()))

        listOf(sh * 0.36f, sh * 0.64f).forEach { cy ->
            val holeR = sw * 0.021f
            val hx = sw * 0.044f
            drawCircle(Color(0x2CFFFFFF), holeR * 1.48f, Offset(hx, cy))
            drawCircle(Color(0xEE0E0C0A), holeR, Offset(hx, cy))
            drawArc(
                color = Color(0x78FFFFFF),
                startAngle = if (mirrored) -70f else 110f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(hx - holeR, cy - holeR),
                size = Size(holeR * 2f, holeR * 2f),
                style = Stroke(hairPx() * 1.2f),
            )
        }
        listOf(sh * 0.295f, sh * 0.705f).forEach { cy ->
            val holeR = sw * 0.015f
            val hx = sw * 0.044f
            drawCircle(Color(0x24FFFFFF), holeR * 1.42f, Offset(hx, cy))
            drawCircle(Color(0xE00E0C0A), holeR, Offset(hx, cy))
        }

        // ============ 2. 壳内氛围透光：收带盘一圈最亮，底缘整片透光 ============
        clipPath(shellPath) {
            drawCircle(
                Brush.radialGradient(
                    0f to glow.copy(alpha = 0.48f),
                    0.55f to glow.copy(alpha = 0.19f),
                    1f to Color.Transparent,
                ),
                radius = sw * 0.58f, center = Offset(hubX, botY),
            )
            drawCircle(
                Brush.radialGradient(
                    0f to glow.copy(alpha = 0.25f),
                    1f to Color.Transparent,
                ),
                radius = sw * 0.42f, center = Offset(hubX, topY),
            )
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.58f to Color.Transparent,
                    1f to glow.copy(alpha = 0.32f),
                ),
                Offset.Zero, Size(sw, sh),
            )
        }

        // ============ 3. 毂旁红色记号弧（结构图的印刷红，画在窗板下面） ============
        listOf(topY, botY).forEach { cy ->
            drawArc(
                color = accent, startAngle = 130f, sweepAngle = 80f, useCenter = false,
                topLeft = Offset(hubX - cutR * 1.18f, cy - cutR * 1.18f),
                size = Size(cutR * 2.36f, cutR * 2.36f),
                alpha = 0.92f,
                style = Stroke(cutR * 0.38f),
            )
        }

        // ============ 3.5 上半盘半幅贴纸（白标签只占上半，收带盘整盘裸露） ============
        val labelLeft = sw * 0.135f
        val labelTop = sh * 0.055f
        val labelRight = sw * 0.885f
        val labelBot = topY + cutR * 0.72f
        val labelHoleR = cutR * 1.02f
        val labelPath = Path().apply {
            addRoundRect(RoundRect(labelLeft, labelTop, labelRight, labelBot, CornerRadius(sw * 0.022f)))
            addOval(Rect(hubX - labelHoleR, topY - labelHoleR, hubX + labelHoleR, topY + labelHoleR))
            fillType = PathFillType.EvenOdd
        }
        drawPath(labelPath, Color(0x2E000000))
        val paperBase = lerp(Color(0xFFF4F0E8), shellTint, 0.07f)
        drawPath(labelPath, paperBase.copy(alpha = 0.92f))
        clipPath(labelPath) {
            val lW = labelRight - labelLeft
            val lH = labelBot - labelTop
            // 顶部色带装饰条
            drawRect(
                accent.copy(alpha = 0.86f),
                Offset(labelLeft, labelTop),
                Size(lW, lH * 0.135f),
            )
            // 右上角复古磁带 A 面标识小方框（Side A Badge）
            val badgeS = lW * 0.085f
            val badgeX = if (mirrored) labelLeft + sw * 0.05f else labelRight - sw * 0.05f - badgeS
            val badgeY = labelTop + lH * 0.20f
            drawRoundRect(
                Color(0xCC22201E),
                Offset(badgeX, badgeY),
                Size(badgeS, badgeS),
                CornerRadius(sw * 0.006f),
            )
            // 贴纸上的横纹印刷细线
            for (i in 1..3) {
                val ly = labelTop + lH * (0.28f + i * 0.15f)
                drawLine(
                    Color(0x281C1B18),
                    Offset(labelLeft + sw * 0.05f, ly),
                    Offset(labelRight - sw * 0.05f, ly),
                    strokeWidth = hairPx(),
                )
            }
        }
        drawPath(labelPath, Color(0x60FFFFFF), style = Stroke(hairPx()))

        // ============ 4. 中央窗板：挖两毂圆孔 + 中央带窗（EvenOdd） ============
        val stripPath = Path().apply {
            addRoundRect(RoundRect(stripX, stripTop, stripX + stripW, stripBot, CornerRadius(sw * 0.020f)))
            addOval(Rect(hubX - cutR, topY - cutR, hubX + cutR, topY + cutR))
            addOval(Rect(hubX - cutR, botY - cutR, hubX + cutR, botY + cutR))
            addRoundRect(RoundRect(winX, winY, winX + winW, winY + winH, CornerRadius(sw * 0.012f)))
            fillType = PathFillType.EvenOdd
        }
        drawPath(stripPath, shellTint.copy(alpha = 0.20f))
        clipPath(stripPath) {
            drawRect(
                Brush.horizontalGradient(
                    0f to Color(if (mirrored) 0x14000000 else 0x1AFFFFFF),
                    0.5f to Color.Transparent,
                    1f to Color(if (mirrored) 0x1AFFFFFF else 0x14000000),
                    startX = stripX,
                    endX = stripX + stripW,
                ),
                Offset(stripX, stripTop),
                Size(stripW, stripBot - stripTop),
            )
        }

        // 孔缘双层倒角环（上暗下亮 = 亚克力开孔厚度）
        listOf(topY, botY).forEach { cy ->
            drawCircle(Color(0x48000000), cutR, Offset(hubX, cy), style = Stroke(hairPx() * 1.2f))
            drawArc(
                color = Color(0x6EFFFFFF), startAngle = 15f, sweepAngle = 150f, useCenter = false,
                topLeft = Offset(hubX - cutR - hairPx(), cy - cutR - hairPx()),
                size = Size((cutR + hairPx()) * 2f, (cutR + hairPx()) * 2f),
                style = Stroke(hairPx() * 1.2f),
            )
        }
        // 带窗内凹边 + 走带丝印刻度线（Gauge Ticks）
        drawRoundRect(
            Color(0x66000000), Offset(winX, winY), Size(winW, winH),
            CornerRadius(sw * 0.012f), style = Stroke(hairPx() * 1.2f),
        )
        drawLine(
            Color(0x58FFFFFF),
            Offset(winX + hairPx(), winY + winH), Offset(winX + winW - hairPx(), winY + winH),
            strokeWidth = hairPx() * 1.2f,
        )
        val tickCount = 6
        for (i in 0..tickCount) {
            val ty = winY + winH * (0.12f + 0.76f * i / tickCount)
            val isMajor = i == 0 || i == tickCount / 2 || i == tickCount
            val tickLen = if (isMajor) sw * 0.026f else sw * 0.014f
            drawLine(
                Color(0x80EDEBE4),
                Offset(winX - sw * 0.008f - tickLen, ty),
                Offset(winX - sw * 0.008f, ty),
                strokeWidth = if (isMajor) hairPx() * 1.3f else hairPx(),
            )
        }

        drawRoundRect(
            Color(0x3CFFFFFF), Offset(stripX, stripTop), Size(stripW, stripBot - stripTop),
            CornerRadius(sw * 0.020f), style = Stroke(hairPx() * 1.2f),
        )
    } // ← 壳内零件到此（视差平移收尾）

    // ============ 5. 磨砂清壳覆层：让零件“陷进”塑料里 ============
    drawRoundRect(shellTint.copy(alpha = 0.14f), Offset.Zero, Size(sw, sh), cr)
    drawRoundRect(tape.art.getOrElse(0) { Color(0xFFE7DFD2) }, Offset.Zero, Size(sw, sh), cr, alpha = 0.045f)
    clipPath(shellPath) {
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.78f to Color.Transparent,
                1f to Color(0x22000000),
                center = Offset(sw * 0.5f, sh * 0.5f),
                radius = sh * 0.62f,
            ),
            Offset.Zero, Size(sw, sh),
        )
    }

    // ============ 6. 清壳玻璃感：随空中翻转角动态扫动的双层折射高光带 ============
    clipPath(shellPath) {
        // 镜像时翻转高光倾斜角，保证与全局左上光源一致；飞行时随 tiltRatio 横向扫过表面
        val glintAngle = if (mirrored) 22f else -22f
        val sweepShift = sw * 0.24f * tiltRatio * lightSign
        rotate(glintAngle, Offset(sw * 0.5f, sh * 0.5f)) {
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.40f to Color(0x18FFFFFF),
                    0.50f to Color(0x48FFFFFF),
                    0.60f to Color(0x18FFFFFF),
                    1f to Color.Transparent,
                ),
                Offset(sw * 0.02f + sweepShift, -sh * 0.3f), Size(sw * 0.19f, sh * 1.6f),
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to Color(0x34FFFFFF),
                    1f to Color.Transparent,
                ),
                Offset(sw * 0.23f + sweepShift * 1.15f, -sh * 0.3f), Size(sw * 0.045f, sh * 1.6f),
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to Color(0x24FFFFFF),
                    1f to Color.Transparent,
                ),
                Offset(sw * 0.52f - sweepShift * 0.5f, -sh * 0.3f), Size(sw * 0.30f, sh * 1.6f),
            )
        }
    }

    // ============ 8. 壳缘细条纹（结构图的壳侧壁防滑/加强纹） ============
    clipPath(shellPath) {
        for (i in 0..5) {
            val off = sw * (0.014f + i * 0.012f)
            drawLine(Color(0x16FFFFFF), Offset(off, sh * 0.06f), Offset(off, sh * 0.94f), strokeWidth = hairPx())
            drawLine(Color(0x16FFFFFF), Offset(sw - off, sh * 0.06f), Offset(sw - off, sh * 0.94f), strokeWidth = hairPx())
        }
    }

    // ============ 9. 壳体厚度：底缘侧壁刻面 + 青色透光 ============
    val seam = sw * 0.014f
    val faceClipR = sw - if (wallR) seam else 0f
    val faceClipL = if (wallL) seam else 0f
    val faceClipB = sh - if (wallD) seam else 0f
    clipPath(shellPath) {
        clipRect(faceClipL, 0f, faceClipR, faceClipB) {
            drawRoundRect(
                Color(0x22FFFFFF), Offset.Zero, Size(sw, sh), cr,
                style = Stroke(sw * 0.028f),
            )
        }
        if (!wallR && !wallL && !wallD) {
            drawPath(
                rrPath(sw * 0.014f, sh * 0.011f, sw * 0.972f, sh * 0.978f, crf * 0.82f),
                Color(0x2BFFFFFF), style = Stroke(hairPx()),
            )
        }
        if (!wallD) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color(0x2EFFFFFF),
                    1f to glow.copy(alpha = 0.58f),
                ),
                Offset(0f, sh - sw * 0.05f), Size(sw, sw * 0.05f),
            )
            val facets = 12
            for (i in 1 until facets) {
                val fx = sw * i / facets
                drawLine(Color(0x26FFFFFF), Offset(fx, sh - sw * 0.040f), Offset(fx, sh - hairPx()), strokeWidth = hairPx())
            }
        }
    }
    if (!wallD) {
        drawLine(glow.copy(alpha = 0.88f), Offset(crf, sh - hairPx() * 1.5f), Offset(sw - crf, sh - hairPx() * 1.5f), strokeWidth = 1.5f * density)
        // 左缘那道透光只在左缘是"自由边"时才画：厚度落在左边时那里是壳壁接缝，画了会留一道假棱
        if (!wallL) {
            drawLine(glow.copy(alpha = 0.42f), Offset(hairPx() * 1.5f, sh * 0.70f), Offset(hairPx() * 1.5f, sh - crf), strokeWidth = 1.2f * density)
        }
    }
    if (!wallR) {
        drawLine(glow.copy(alpha = 0.42f), Offset(sw - hairPx() * 1.5f, sh * 0.70f), Offset(sw - hairPx() * 1.5f, sh - crf), strokeWidth = 1.2f * density)
    }

    // 四缘玻璃倒角亮边（考虑镜像后的左/右受光强弱交换）
    val leftEdgeAlpha = if (mirrored) 0x4DFFFFFF else 0x6EFFFFFF
    val rightEdgeAlpha = if (mirrored) 0x6EFFFFFF else 0x4DFFFFFF
    drawLine(Color(0x90FFFFFF), Offset(crf, hairPx()), Offset(sw - crf, hairPx()), strokeWidth = 1.5f * density)
    if (!wallL) {
        drawLine(Color(leftEdgeAlpha), Offset(hairPx(), crf), Offset(hairPx(), sh - crf), strokeWidth = 1.5f * density)
    }
    if (!wallR) {
        drawLine(Color(rightEdgeAlpha), Offset(sw - hairPx() * 1.5f, crf), Offset(sw - hairPx() * 1.5f, sh - crf), strokeWidth = 1.5f * density)
    }
    if (!wallD) {
        drawLine(Color(0x3DFFFFFF), Offset(crf, sh - hairPx() * 1.5f), Offset(sw - crf, sh - hairPx() * 1.5f), strokeWidth = 1.2f * density)
    }
    clipRect(faceClipL, 0f, faceClipR, faceClipB) {
        drawRoundRect(
            lerp(shellTint, Color.White, 0.48f).copy(alpha = 0.66f),
            Offset.Zero, Size(sw, sh), cr, style = Stroke(hairPx() * 1.4f),
        )
    }

    // ============ 10. 螺丝与注塑套筒（四角 + 开口边中点；右侧两颗是红色塑料） ============
    val sr = sw * 0.020f
    screw(Offset(sw * 0.052f, sh * 0.028f), sr, red = false, lightSign = lightSign)
    screw(Offset(sw * 0.948f, sh * 0.028f), sr, red = true, lightSign = lightSign)
    screw(Offset(sw * 0.052f, sh * 0.972f), sr, red = false, lightSign = lightSign)
    screw(Offset(sw * 0.948f, sh * 0.972f), sr, red = true, lightSign = lightSign)
    screw(Offset(sw * 0.040f, sh * 0.50f), sr, red = false, lightSign = lightSign)
}

private fun DrawScope.screw(c: Offset, r: Float, red: Boolean, lightSign: Float = 1f) {
    val bossR = r * 1.65f
    drawCircle(Color(0x24000000), bossR, Offset(c.x + hairPx() * 0.5f * lightSign, c.y + hairPx() * 0.6f))
    drawCircle(Color(0x1AFFFFFF), bossR, c)
    drawCircle(Color(0x40FFFFFF), bossR, c, style = Stroke(hairPx()))
    drawCircle(Color(0x55000000), r * 1.12f, c)

    val hlOffset = Offset(c.x - r * 0.3f * lightSign, c.y - r * 0.3f)
    val dotOffset = Offset(c.x - r * 0.32f * lightSign, c.y - r * 0.36f)

    if (red) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFE56358),
                0.55f to Color(0xFFB8322A),
                1f to Color(0xFF751914),
                center = hlOffset,
                radius = r * 1.5f,
            ),
            r, c,
        )
        drawCircle(Color(0x73FFFFFF), r, c, style = Stroke(hairPx()))
        rotate(18f * lightSign, c) {
            drawLine(Color(0x66420A07), Offset(c.x - r * 0.62f, c.y + hairPx() * 0.5f), Offset(c.x + r * 0.62f, c.y + hairPx() * 0.5f), strokeWidth = hairPx() * 1.3f)
            drawLine(Color(0x66420A07), Offset(c.x + hairPx() * 0.5f, c.y - r * 0.62f), Offset(c.x + hairPx() * 0.5f, c.y + r * 0.62f), strokeWidth = hairPx() * 1.3f)
            drawLine(Color(0x88FFA69E), Offset(c.x - r * 0.62f, c.y), Offset(c.x + r * 0.62f, c.y), strokeWidth = hairPx())
            drawLine(Color(0x88FFA69E), Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y + r * 0.62f), strokeWidth = hairPx())
        }
        drawCircle(Color(0x99FFFFFF), r * 0.16f, dotOffset)
    } else {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF6B7073),
                0.55f to Color(0xFF323638),
                1f to Color(0xFF141618),
                center = hlOffset,
                radius = r * 1.5f,
            ),
            r, c,
        )
        drawCircle(Color(0x73FFFFFF), r, c, style = Stroke(hairPx()))
        rotate(-24f * lightSign, c) {
            drawLine(Color(0x8808090A), Offset(c.x - r * 0.62f, c.y + hairPx() * 0.5f), Offset(c.x + r * 0.62f, c.y + hairPx() * 0.5f), strokeWidth = hairPx() * 1.3f)
            drawLine(Color(0x8808090A), Offset(c.x + hairPx() * 0.5f, c.y - r * 0.62f), Offset(c.x + hairPx() * 0.5f, c.y + r * 0.62f), strokeWidth = hairPx() * 1.3f)
            drawLine(Color(0x90FFFFFF), Offset(c.x - r * 0.62f, c.y), Offset(c.x + r * 0.62f, c.y), strokeWidth = hairPx())
            drawLine(Color(0x90FFFFFF), Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y + r * 0.62f), strokeWidth = hairPx())
        }
        drawCircle(Color(0x75FFFFFF), r * 0.16f, dotOffset)
    }
}

private fun DrawScope.tapePack(
    cx: Float,
    cy: Float,
    r: Float,
    st: CassetteStyle,
    angle: Float = 0f,
    lightSign: Float = 1f,
) {
    if (r <= 2f) return
    val center = Offset(cx, cy)

    // 1. 带盘投在底壳上的环境阴影
    val aoCenter = Offset(cx + hairPx() * lightSign, cy + hairPx() * 2f)
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent,
            0.82f to Color(0x46000000),
            1f to Color.Transparent,
            center = aoCenter,
            radius = r * 1.09f,
        ),
        r * 1.09f,
        aoCenter,
    )

    // 2. 氧化铁基底
    drawCircle(
        Brush.radialGradient(
            0f to st.oxideRing,
            0.68f to st.oxide,
            0.95f to st.oxideEdge,
            1f to Color(0xFF0E0B09),
            center = Offset(cx - r * 0.15f * lightSign, cy - r * 0.18f),
            radius = r * 1.2f,
        ),
        r, center,
    )

    // 3. 各向异性丝绸反光（固定在光照方向，不随 angle 旋转）
    val sheenHighlight = lerp(st.oxideRing, Color(0xFFD8B898), 0.40f).copy(alpha = 0.28f)
    val sheenShadow = Color(0x38000000)
    rotate(-35f * lightSign, center) {
        drawCircle(
            Brush.sweepGradient(
                0.00f to sheenShadow,
                0.12f to sheenHighlight,
                0.25f to Color.Transparent,
                0.50f to sheenShadow,
                0.62f to sheenHighlight,
                0.75f to Color.Transparent,
                1.00f to sheenShadow,
                center = center,
            ),
            r * 0.99f, center,
        )
    }

    // 4. 紧密缠绕的磁带圈层纹理
    val step = (1.35f * density).coerceAtLeast(1.4f)
    val count = (r / step).toInt().coerceIn(4, 52)
    for (i in 1 until count) {
        val ratio = i.toFloat() / count
        val ringAlpha = when {
            i % 5 == 0 -> 0.42f
            i % 2 == 0 -> 0.26f
            else -> 0.12f
        }
        drawCircle(
            color = st.oxideRing,
            radius = r * ratio,
            center = center,
            alpha = ringAlpha,
            style = Stroke(hairPx() * 1.3f),
        )
    }

    // 5. 带盘外缘深色倒角与左上受光弧（镜像时自动补偿到视觉左上角）
    drawCircle(st.oxideEdge, r, center, style = Stroke(linePx() * 1.1f))
    drawArc(
        color = Color(0x75D6B28D),
        startAngle = if (lightSign > 0f) -152f else -90f,
        sweepAngle = 62f,
        useCenter = false,
        topLeft = Offset(cx - r * 0.985f, cy - r * 0.985f),
        size = Size(r * 1.97f, r * 1.97f),
        alpha = 0.62f,
        style = Stroke(1.6f * density, cap = StrokeCap.Round),
    )

    // 6. 随盘旋转的导带接缝线（镜像时反转旋转方向，保证顺时针走带物理一致）
    rotate(angle * lightSign, center) {
        drawLine(
            Color(0x80E0D3BC),
            Offset(cx, cy - r * 0.34f), Offset(cx, cy - r * 0.97f),
            strokeWidth = hairPx() * 1.8f,
        )
        drawLine(
            Color(0x501E1712),
            Offset(cx + hairPx() * 1.4f * lightSign, cy - r * 0.34f),
            Offset(cx + hairPx() * 1.4f * lightSign, cy - r * 0.97f),
            strokeWidth = hairPx(),
        )
    }
}

private fun DrawScope.hub(
    cx: Float,
    cy: Float,
    r: Float,
    angleDeg: Float,
    st: CassetteStyle,
    lightSign: Float = 1f,
) {
    val center = Offset(cx, cy)
    drawCircle(Color(0x55000000), r * 1.06f, center)

    rotate(angleDeg * lightSign, center) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFF6F5F0),
                0.65f to st.hub,
                1f to st.hubEdge,
                center = Offset(cx - r * 0.18f * lightSign, cy - r * 0.22f),
                radius = r * 1.25f,
            ),
            r, center,
        )
        drawCircle(st.hubEdge, r, center, style = Stroke(hairPx() * 1.2f))

        val grooveR = r * 0.74f
        drawCircle(Color(0x28000000), grooveR, center, style = Stroke(r * 0.16f))
        drawCircle(Color(0x45FFFFFF), grooveR + r * 0.08f, center, style = Stroke(hairPx()))

        drawArc(
            color = Color(0x55403E3A),
            startAngle = -35f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(cx - grooveR, cy - grooveR),
            size = Size(grooveR * 2f, grooveR * 2f),
            style = Stroke(r * 0.14f, cap = StrokeCap.Butt),
        )
        drawCircle(Color(0x99FFFFFF), r * 0.10f, Offset(cx + r * 0.72f, cy))

        val holeR = r * 0.46f
        drawCircle(Color(0xFF141210), holeR, center)

        for (i in 0 until 6) {
            val a = Math.toRadians(i * 60.0 - 90.0)
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()

            drawLine(
                st.hubEdge,
                Offset(cx + c * holeR, cy + s * holeR),
                Offset(cx + c * r * 0.94f, cy + s * r * 0.94f),
                strokeWidth = linePx(),
                alpha = if (i % 2 == 0) 0.92f else 0.48f,
            )
            drawLine(
                Color(0x66FFFFFF),
                Offset(cx + c * holeR - hairPx() * lightSign, cy + s * holeR),
                Offset(cx + c * r * 0.94f - hairPx() * lightSign, cy + s * r * 0.94f),
                strokeWidth = hairPx(),
                alpha = 0.55f,
            )

            val toothOuter = holeR * 1.02f
            val toothInner = holeR * 0.68f
            drawLine(
                st.hub,
                Offset(cx + c * toothOuter, cy + s * toothOuter),
                Offset(cx + c * toothInner, cy + s * toothInner),
                strokeWidth = linePx() * 1.65f,
                cap = StrokeCap.Butt,
            )
            drawCircle(
                Color(0x99FFFFFF),
                hairPx() * 0.9f,
                Offset(cx + c * toothInner, cy + s * toothInner),
            )
        }

        drawCircle(st.hubEdge, holeR, center, style = Stroke(hairPx() * 1.2f))
    }
    drawArc(
        color = Color(0x55FFFFFF), startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(cx - r * 0.46f, cy - r * 0.46f),
        size = Size(r * 0.92f, r * 0.92f),
        style = Stroke(hairPx()),
    )
}

/** 卡座凹槽：全机唯一的深色块。 */
fun DrawScope.drawDeckRecess(x: Float, y: Float, w: Float, h: Float) {
    val r = CornerRadius(4f * density)
    drawRoundRect(C.DeckFloor, Offset(x, y), Size(w, h), r)
    drawRoundRect(Color(0x2EFFFFFF), Offset(x, y), Size(w, h), r, style = Stroke(hairPx()))
    drawRoundRect(
        Color(0x14000000),
        Offset(x + density, y + density),
        Size(w - 2f * density, h - 2f * density), r, style = Stroke(hairPx()),
    )
    for (i in 1..3) {
        val yy = y + h * i / 4f
        hair(x + w * 0.06f, yy, x + w * 0.94f, yy, Color(0x10FFFFFF))
    }
}

/** 走带方向标记（实心三角，和按键同一套符号）。 */
fun DrawScope.triMark(x: Float, y: Float, s: Float, right: Boolean, color: Color) {
    drawPath(tri(x, y, s, s, right), color)
}

/** 「需要动作」指示灯：亮赤色 = 这盘带还没装曲目。 */
fun DrawScope.led(cx: Float, cy: Float, r: Float, on: Boolean) {
    if (on) {
        drawCircle(C.Signal, r * 2.2f, Offset(cx, cy), alpha = 0.16f)
        drawCircle(C.Signal, r, Offset(cx, cy))
    } else {
        drawCircle(Color(0x55787870), r, Offset(cx, cy))
    }
}
