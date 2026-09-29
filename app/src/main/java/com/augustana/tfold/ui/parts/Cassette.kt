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

/**
 * 磁带几何（全部相对带盒宽度 sw）：
 *  - 带盒高 = 1.56 × 宽（真实带盒 100.4 × 63.8 mm 旋转 90°）
 *  - 两个轮毂中心在 0.288 / 0.712 高度处，间距 0.424 × 高
 *  - 满盘半径 0.325 × 宽，空盘 0.150 × 宽
 *
 * 样式复刻参考例图（КИНО 清壳带 + 透明结构图）：
 *  - 全透明清壳：零件全部画在壳里，再罩一层磨砂
 *  - 两毂之间一道贯穿的透明窗板（真实带芯结构）：两个轮毂圆孔 + 中央窄带窗
 *  - 白标签只占上半，盖住顶盘区域；收带盘整盘裸露在清壳区
 *  - 毂旁红色记号弧、右侧红色塑料螺丝、壳缘细条纹
 *  - **清壳的色随封面走**（[shellTintOf]）：壳内透光 + 那层磨砂、窗板、壳缘都是同一个色相 ——
 *    所以四盘带的壳各不相同，但都还是"透明带"，不是彩色塑料壳
 */
const val CASSETTE_H_RATIO = 1.56f
private const val R_MAX = 0.325f
private const val R_MIN = 0.150f
const val HUB_R = 0.115f

/** 两个轮毂中心的高度（相对带高）—— 画带子、画盒腔模位都用它，只有一处真源。 */
const val HUB_V_TOP = 0.288f
const val HUB_V_BOT = 0.712f

/**
 * 磁带**躺在包装盒里**的静止位：尺寸相对盒宽、中心相对盒宽。
 *
 * 飞行体（FoldScreen）与盒腔模位（Cover）都以它为基准：
 *  · 尺寸取 0.88 —— 盒与带同为 1.56 宽高比，0.88 时上下、左右各留约 6% 盒壳壁，
 *    正好是"一盘带塞在它的盒里"那种贴合感（0.76 太松，看着像盒里放了个小人）；
 *  · 中心取 0.525 —— 盒腔模位偏到盖板**自由边之外**那一侧：盖板开到底只压住
 *    0..cos(48°)≈0.669 盒宽，剩下的 0.669..0.965 这一段才是真正露得出来的。
 * 模位的**孔距**就是由它推出来的（`0.424 × 0.88 = 0.373` 盒高），别再按带子本体
 * 的 0.424 盒高去放 —— 那正是"盒子里的两个孔和带子对不上"的原因。
 */
const val CASSETTE_IN_BOX = 0.88f
const val CASSETTE_IN_BOX_X = 0.525f

/** 一个颜色的"彩度"（RGB 通道极差）—— 只用它挑"有没有色"，不做真正的 HSV 换算。 */
private fun chromaticOf(c: Color): Float =
    maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)

/**
 * **无色壳** —— 出厂那盘空白带的壳。
 *
 * 它没有任何封面可染，配色也是纸白/石墨/灰（彩度 0）。要是照常走"按 id 定一个色相"，
 * 就会凭 `b01` 的哈希白捡一个颜色出来 —— 一盘"空白磁带"带着个莫名其妙的蓝壳或紫壳。
 * 空白带就该是**最素的那一盘**：白透塑料，不参与配色这件事。
 */
val NeutralShell = Color(0xFFEDEFEE)

/**
 * 透明壳的**色**从哪来 —— 默认是清壳带，你看到的色是封面透过塑料染上来的：
 *  · 空白带（出厂那盘）→ [NeutralShell]，不上色；
 *  · 有封面图 → 用封面主色（`FoldMachine` 在 IO 里抽好传进来）；
 *  · 没有 → 取盒面配色里最有颜色的那一档（构成法那几盘）；
 *  · 连配色都是灰的 → 按带子 id 定一个色相（同一盘永远同色，不同带子颜色不同）。
 *
 * 结果会**归一化**到薄塑料该有的范围：彩度不过 0.55、明度 0.96 ——
 * 这样壳里的零件、印刷记号、金属件都还读得出来，不会糊成一块彩色塑料。
 */
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
        if (sat < 0.20f) {                       // 封面是灰的 → 灰没有色相，退回按 id 定色
            hue = (abs(tape.id.hashCode()) % 360).toFloat()
            sat = 0.46f
        }
    } else {
        hue = (abs(tape.id.hashCode()) % 360).toFloat()
        sat = 0.46f
    }
    return Color.hsv(hue.coerceIn(0f, 359.9f), sat.coerceIn(0.22f, 0.55f), 0.96f)
}

/**
 * 壳里那盏灯：同色相但更浓更亮 —— 光穿过塑料比塑料本身更"有色"。
 *
 * 无色壳（[NeutralShell]）例外：它没有色相可浓，硬按 1.55× 拉饱和会把那点通道误差
 * 放大成一层粉/青的偏色，所以这里直接给一盏冷白透光。
 */
fun glowOf(tint: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(tint.toArgb(), hsv)
    if (hsv[1] < 0.06f) return Color(0xFFF4F6F6)
    return Color.hsv(hsv[0], (hsv[1] * 1.55f).coerceIn(0.30f, 0.92f), 1f)
}

fun rMaxOf(sw: Float) = R_MAX * sw

fun rMinOf(sw: Float) = R_MIN * sw

/** 供带（上）/ 收带（下）盘半径，单位 px。 */
fun packRadius(tapePos: Float, sw: Float, takeUp: Boolean): Float {
    val p = tapePos.coerceIn(0f, 1f)
    return if (takeUp) mix(R_MIN, R_MAX, p) * sw else mix(R_MAX, R_MIN, p) * sw
}

/** 磁带壳体配色：落在深色凹槽里的那一套。 */
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

/** 圆角矩形 Path（clip 用）。 */
private fun rrPath(x: Float, y: Float, w: Float, h: Float, r: Float): Path =
    Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y, x + w, y + h, r, r)) }

/** 单调链求凸包（逆时针，去掉共线点 —— 纯横/纯竖挤出会退化成矩形，正好靠它合并）。 */
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

/**
 * 挤出体的**真外轮廓**：正面圆角矩形 R 与它平移 (dx,dy) 后的副本 R′ 的凸包
 * （圆角矩形是凸的 → 并集的边界就是凸包边界）。
 *
 * 这一步很关键，别退回"把圆角矩形平移一份当轮廓"：
 * 那样右 / 下转角处会咬掉一块缺口，看着像**后面还压着一片没对齐的玻璃** ——
 * 用户说的"像两片构成的三维"就是从这儿来的。
 *
 * 做法：取两个"圆角圆心矩形"的 8 个角点求凸包（得到六边形，纯横/纯竖时退化成矩形），
 * 再用二次贝塞尔在顶点倒圆 —— 倒出来的就是圆角矩形的挤出体，四条斜棱一步不差。
 */
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

/**
 * 画一盘竖放的磁带：供带盘在上、收带盘在下（与 TFold 卡座一致）。
 *
 * [tapePos] 0 = 磁带全在供带盘，1 = 全绕到收带盘。
 * 两个轮毂的转角 [angleTop] / [angleBot] 由状态机按 1/半径 分别积分给出 ——
 * 所以快进时收带盘明显比供带盘转得快，这是卷带机构最容易被忽略、也最能露馅的细节。
 */
fun DrawScope.drawCassette(
    w: Float,
    h: Float,
    tape: Tape,
    tapePos: Float,
    angleTop: Float,
    angleBot: Float,
    style: CassetteStyle = CassetteStyle.InRecess,
    /** 这盘带的清壳色（= 封面主色）。null 就按盒面配色 / 带子 id 推。 */
    tint: Color? = null,
    /**
     * 立体挤出：**侧壁相对正面的位移**（像素，右 / 下为正）。
     *
     * 0 = 纯平面（卡座里正面朝我们时就是它）。飞行路上给非零值，带子就成了
     * 一块有厚度的塑料 —— 正面与侧壁的交界处那道"折过来的边"是立体感的全部来源。
     * 两个值分开给，是因为"绕竖轴转"露出的是**右**侧壁、"略俯视"露出的是**底**缘。
     */
    depthX: Float = 0f,
    depthY: Float = 0f,
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
    // 印刷红：封面 accent 够艳才用它，否则回到例图那种安全红（暗色/灰色的 accent 印出来是死的）
    val rawAccent = tape.art.getOrElse(1) { Color(0xFFE8433E) }
    val accent = run {
        val mx = maxOf(rawAccent.red, rawAccent.green, rawAccent.blue)
        val mn = minOf(rawAccent.red, rawAccent.green, rawAccent.blue)
        if (mx - mn > 0.25f && mx < 0.98f) rawAccent else Color(0xFFE8433E)
    }
    val shellPath = rrPath(0f, 0f, sw, sh, crf)
    // 清壳的色：默认透明带本身无色，色相来自封面（每盘不同）。
    // `shellTint` = 塑料染上的那层淡色，`glow` = 从里面透出来的灯（同色相、更浓更亮）。
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

    // 侧壁露出的方向（右 / 下 = 正）—— 那两条边是"正面与侧壁的接缝"，
    // 绝不能再描亮边（描了就等于把正面切出来单成一片）。
    val wallR = depthX > 0f
    val wallD = depthY > 0f

    // ============ 0. 壳体厚度：透明亚克力的**侧壁**，不是一块暗塑料 ============
    //
    // 照透明带实拍（清壳带在棚里转）：亚克力的**棱**迎光最亮 —— 一条白线，往里一档还有
    // 一道内反射线，中间是淡淡一层奶色，底下的零件照样透过来。反过来用暗色去堆体积，
    // 叠出来就是一块实心塑料，"透明壳"立刻变回"实体块"。
    //
    // 光有这些还不够。第一版还犯了两个错，**两个都会让带子读成"两片玻璃"**：
    //   ① 给每一层中间副本都描了边 —— 一层层平行的轮廓线，像好几张玻璃片摞着；
    //   ② 正面自己的轮廓还留着一条暗线 / 一圈亮玻璃环。于是"正面"和"远端"各有一条
    //      完整亮边、中间夹一道黑缝 —— 这正好就是用户看到的那两片。
    // 所以现在：中间层**只填色、绝不描边**；正面的玻璃边只在"没有被侧壁接续"的那几条
    // 边上画（见第 9 节）；远端轮廓用挤出体的**真外轮廓**（[extrudeHull]）接出四条斜棱。
    if (depthX != 0f || depthY != 0f) {
        val hull = extrudeHull(sw, sh, crf, depthX, depthY)
        // 壁 = 挤出体 − 正面矩形（正面是透明的，料只该落在这一圈侧壁里）
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
            // 一层层同形状的透明薄片叠出"料"（只填色；描边会画出平行轮廓 = 多片玻璃）
            val steps = 10
            for (i in steps downTo 1) {
                drawPath(
                    rrPath(depthX * i / steps, depthY * i / steps, sw, sh, crf),
                    lerp(shellTint, Color.White, 0.62f).copy(alpha = 0.05f),
                )
            }
            // 靠远端内侧一点"材料感"：宽而极淡的一圈，替掉原来那道平行的内轮廓线
            drawPath(hull, Color(0x14FFFFFF), style = Stroke(4.5f * density))
        }
        // 远端外缘亮线：亚克力的棱迎光就是一条白线，立体感全靠它
        drawPath(hull, Color(0xC4FFFFFF), style = Stroke(1.35f * density))
    }

    // ============ 1. 壳内零件（清壳 → 全部可见） ============
    //
    // 整组零件**往"里"推半个厚度**（depthX/Y 的 0.45）：斜看时，壳里的东西本来就该
    // 相对正面壳偏一点 —— 这点视差是"壳是空的"最直接的证据，和不偏移的正面壳一起看，
    // 一眼就能读出中间夹着一层空气。
    translate(depthX * 0.45f, depthY * 0.45f) {
    tapePack(hubX, topY, rTop, style, angleTop)
    hub(hubX, topY, hubR, angleTop, style)
    tapePack(hubX, botY, rBot, style, angleBot)
    hub(hubX, botY, hubR, angleBot, style)

    // 中央黑带窗（两毂之间的走带路径）
    drawRoundRect(Color(0xF2141210), Offset(winX, winY), Size(winW, winH), CornerRadius(sw * 0.012f))
    drawRect(style.path, Offset(hubX - sw * 0.008f, winY), Size(sw * 0.016f, winH))

    // 开口边（左缘）的两个主导轴孔
    listOf(sh * 0.36f, sh * 0.64f).forEach { cy ->
        drawCircle(Color(0xCC14120F), sw * 0.020f, Offset(sw * 0.040f, cy))
        drawArc(
            color = Color(0x55FFFFFF), startAngle = 100f, sweepAngle = 200f, useCenter = false,
            topLeft = Offset(sw * 0.040f - sw * 0.020f, cy - sw * 0.020f),
            size = Size(sw * 0.040f, sw * 0.040f),
            style = Stroke(hairPx()),
        )
    }

    // ============ 2. 壳内青色灯光：收带盘一圈最亮，底缘整片透光（裁在壳形内） ============
    clipPath(shellPath) {
        drawCircle(
            Brush.radialGradient(
                0f to glow.copy(alpha = 0.44f),
                0.55f to glow.copy(alpha = 0.18f),
                1f to Color.Transparent,
            ),
            radius = sw * 0.58f, center = Offset(hubX, botY),
        )
        drawCircle(
            Brush.radialGradient(
                0f to glow.copy(alpha = 0.24f),
                1f to Color.Transparent,
            ),
            radius = sw * 0.42f, center = Offset(hubX, topY),
        )
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.60f to Color.Transparent,
                1f to glow.copy(alpha = 0.28f),
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
            alpha = 0.9f,
            style = Stroke(cutR * 0.40f),
        )
    }

    // ============ 4. 中央窗板：挖两毂圆孔 + 中央带窗（EvenOdd） ============
    val stripPath = Path().apply {
        addRoundRect(RoundRect(stripX, stripTop, stripX + stripW, stripBot, CornerRadius(sw * 0.020f)))
        addOval(Rect(hubX - cutR, topY - cutR, hubX + cutR, topY + cutR))
        addOval(Rect(hubX - cutR, botY - cutR, hubX + cutR, botY + cutR))
        addRoundRect(RoundRect(winX, winY, winX + winW, winY + winH, CornerRadius(sw * 0.012f)))
        fillType = PathFillType.EvenOdd
    }
    drawPath(stripPath, shellTint.copy(alpha = 0.17f))
    // 孔缘印刷细环 + 带窗内凹边（上暗下亮）
    listOf(topY, botY).forEach { cy ->
        drawCircle(Color(0x40A09E96), cutR + hairPx(), Offset(hubX, cy), style = Stroke(hairPx()))
    }
    drawRoundRect(
        Color(0x59000000), Offset(winX, winY), Size(winW, winH),
        CornerRadius(sw * 0.012f), style = Stroke(hairPx()),
    )
    drawLine(
        Color(0x40FFFFFF),
        Offset(winX + hairPx(), winY + winH), Offset(winX + winW - hairPx(), winY + winH),
        strokeWidth = hairPx(),
    )
    // 板缘（比壳面略凸起的一道细亮缘）
    drawRoundRect(
        Color(0x2EFFFFFF), Offset(stripX, stripTop), Size(stripW, stripBot - stripTop),
        CornerRadius(sw * 0.020f), style = Stroke(hairPx()),
    )
    }   // ← 壳内零件到此（视差平移收尾）

    // ============ 5. 磨砂清壳覆层：让零件“陷进”塑料里 ============
    //  清壳本身无色 —— 这层是**塑料被封面染上的那点色**，每盘带都不一样。
    drawRoundRect(shellTint.copy(alpha = 0.15f), Offset(0f, 0f), Size(sw, sh), cr)
    drawRoundRect(tape.art.getOrElse(0) { Color(0xFFE7DFD2) }, Offset(0f, 0f), Size(sw, sh), cr, alpha = 0.05f)

    // ============ 6. 清壳玻璃感：斜向硬高光（clip 在壳形里） ============
    clipPath(shellPath) {
        rotate(-22f, Offset(sw * 0.5f, sh * 0.5f)) {
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to Color(0x3DFFFFFF),
                    1f to Color.Transparent,
                ),
                Offset(sw * 0.04f, -sh * 0.3f), Size(sw * 0.16f, sh * 1.6f),
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to Color(0x24FFFFFF),
                    1f to Color.Transparent,
                ),
                Offset(sw * 0.52f, -sh * 0.3f), Size(sw * 0.30f, sh * 1.6f),
            )
        }
    }

    // ============ 8. 壳缘细条纹（结构图的壳侧壁纹理） ============
    clipPath(shellPath) {
        for (i in 0..5) {
            val off = sw * (0.014f + i * 0.012f)
            drawLine(Color(0x14FFFFFF), Offset(off, sh * 0.06f), Offset(off, sh * 0.94f), strokeWidth = hairPx())
            drawLine(Color(0x14FFFFFF), Offset(sw - off, sh * 0.06f), Offset(sw - off, sh * 0.94f), strokeWidth = hairPx())
        }
    }

    // ============ 9. 壳体厚度：底缘侧壁刻面 + 青色透光（例图里壳底最亮） ============
    //
    // 正面平看时，"壁厚"就是外缘之内那一窄条：外缘一圈**亮玻璃边**（约 1.4% 宽，
    // 描边一半被裁在壳外，于是留在壳内的正是"壁"），往里一档一道内壁线；
    // 中间那点材料靠底缘这道渐变交代（透明亚克力的边迎光最亮）。
    //
    // **挤出时右 / 下那两条边必须让开**：它们是正面与侧壁的接缝，在那儿收一道亮边
    // 就等于把正面切出来单成一片玻璃（用户看到的"两片构成的三维"）。
    // 用 clipRect 把那两条边裁掉，正面的边直接化进侧壁里。
    val seam = sw * 0.014f                       // = 玻璃边宽的一半（等于留在壳内的壁厚）
    val faceClipR = sw - if (wallR) seam else 0f
    val faceClipB = sh - if (wallD) seam else 0f
    clipPath(shellPath) {
        clipRect(0f, 0f, faceClipR, faceClipB) {
            drawRoundRect(
                Color(0x1FFFFFFF), Offset.Zero, Size(sw, sh), cr,
                style = Stroke(sw * 0.028f),
            )
        }
        // 内壁线只在完全平看时画（挤出时远端轮廓已经把"一壁之厚"说清楚了）
        if (!wallR && !wallD) {
            drawPath(
                rrPath(sw * 0.014f, sh * 0.011f, sw * 0.972f, sh * 0.978f, crf * 0.82f),
                Color(0x26FFFFFF), style = Stroke(hairPx()),
            )
        }
        // 底缘侧壁刻面同理：正面底边一旦是接缝，这一圈就得让给侧壁
        if (!wallD) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color(0x2EFFFFFF),
                    1f to glow.copy(alpha = 0.55f),
                ),
                Offset(0f, sh - sw * 0.05f), Size(sw, sw * 0.05f),
            )
            val facets = 12
            for (i in 1 until facets) {
                val fx = sw * i / facets
                drawLine(Color(0x24FFFFFF), Offset(fx, sh - sw * 0.040f), Offset(fx, sh - hairPx()), strokeWidth = hairPx())
            }
        }
    }
    // 底缘青光：内缘叠两道亮线（底缘是接缝时整条让开）
    if (!wallD) {
        drawLine(glow.copy(alpha = 0.85f), Offset(crf, sh - hairPx() * 1.5f), Offset(sw - crf, sh - hairPx() * 1.5f), strokeWidth = 1.5f * density)
        drawLine(glow.copy(alpha = 0.40f), Offset(hairPx() * 1.5f, sh * 0.70f), Offset(hairPx() * 1.5f, sh - crf), strokeWidth = 1.2f * density)
    }
    if (!wallR) {
        drawLine(glow.copy(alpha = 0.40f), Offset(sw - hairPx() * 1.5f, sh * 0.70f), Offset(sw - hairPx() * 1.5f, sh - crf), strokeWidth = 1.2f * density)
    }

    // 常规轮廓：四缘都是**玻璃边**（透明壳的边迎光发亮，不该有一侧是黑的）。
    // 上 / 左 永远是外轮廓；右 / 下在挤出时是接缝，交给远端轮廓去接。
    drawLine(Color(0x88FFFFFF), Offset(crf, hairPx()), Offset(sw - crf, hairPx()), strokeWidth = 1.5f * density)
    drawLine(Color(0x66FFFFFF), Offset(hairPx(), crf), Offset(hairPx(), sh - crf), strokeWidth = 1.5f * density)
    if (!wallR) {
        drawLine(Color(0x4DFFFFFF), Offset(sw - hairPx() * 1.5f, crf), Offset(sw - hairPx() * 1.5f, sh - crf), strokeWidth = 1.5f * density)
    }
    if (!wallD) {
        drawLine(Color(0x3DFFFFFF), Offset(crf, sh - hairPx() * 1.5f), Offset(sw - crf, sh - hairPx() * 1.5f), strokeWidth = 1.2f * density)
    }
    clipRect(0f, 0f, faceClipR, faceClipB) {
        drawRoundRect(
            lerp(shellTint, Color.White, 0.45f).copy(alpha = 0.62f),
            Offset(0f, 0f), Size(sw, sh), cr, style = Stroke(hairPx() * 1.4f),
        )
    }

    // ============ 10. 螺丝：四角 + 开口边中点；右侧两颗是红色塑料（结构图特征） ============
    val sr = sw * 0.020f
    screw(Offset(sw * 0.052f, sh * 0.028f), sr, red = false)
    screw(Offset(sw * 0.948f, sh * 0.028f), sr, red = true)
    screw(Offset(sw * 0.052f, sh * 0.972f), sr, red = false)
    screw(Offset(sw * 0.948f, sh * 0.972f), sr, red = true)
    screw(Offset(sw * 0.040f, sh * 0.50f), sr, red = false)
}

/** 金属螺丝：暗铬径向渐变 + 亮缘 + 浅色十字槽 + 高光点；red = 红色半透明塑料款。 */
private fun DrawScope.screw(c: Offset, r: Float, red: Boolean) {
    if (red) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFD8564C),
                0.6f to Color(0xFFB02E26),
                1f to Color(0xFF7E1D18),
                center = Offset(c.x - r * 0.3f, c.y - r * 0.3f),
                radius = r * 1.5f,
            ),
            r, c,
        )
        drawCircle(Color(0x73FFFFFF), r, c, style = Stroke(hairPx()))
        drawLine(Color(0x80FF9A90), Offset(c.x - r * 0.62f, c.y), Offset(c.x + r * 0.62f, c.y), strokeWidth = hairPx())
        drawLine(Color(0x80FF9A90), Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y + r * 0.62f), strokeWidth = hairPx())
        drawCircle(Color(0x99FFFFFF), r * 0.16f, Offset(c.x - r * 0.32f, c.y - r * 0.36f))
    } else {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF5A5E60),
                0.6f to Color(0xFF2B2E30),
                1f to Color(0xFF141618),
                center = Offset(c.x - r * 0.3f, c.y - r * 0.3f),
                radius = r * 1.5f,
            ),
            r, c,
        )
        drawCircle(Color(0x73FFFFFF), r, c, style = Stroke(hairPx()))
        drawLine(Color(0x8CFFFFFF), Offset(c.x - r * 0.62f, c.y), Offset(c.x + r * 0.62f, c.y), strokeWidth = hairPx())
        drawLine(Color(0x8CFFFFFF), Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y + r * 0.62f), strokeWidth = hairPx())
        drawCircle(Color(0x66FFFFFF), r * 0.16f, Offset(c.x - r * 0.32f, c.y - r * 0.36f))
    }
}

/** 缠绕带盘：径向渐变的氧化铁盘 + 同心细环 + 一道外侧高光。 */
private fun DrawScope.tapePack(cx: Float, cy: Float, r: Float, st: CassetteStyle, angle: Float = 0f) {
    if (r <= 2f) return
    drawCircle(
        Brush.radialGradient(
            0f to st.oxideRing,
            0.72f to st.oxide,
            1f to st.oxideEdge,
            center = Offset(cx - r * 0.18f, cy - r * 0.22f),
            radius = r * 1.25f,
        ),
        r, Offset(cx, cy),
    )
    val step = (1.4f * density).coerceAtLeast(1.5f)
    val count = (r / step).toInt().coerceIn(3, 48)
    for (i in 1 until count) {
        drawCircle(
            color = st.oxideRing,
            radius = r * i / count,
            center = Offset(cx, cy),
            alpha = if (i % 2 == 0) 0.34f else 0.16f,
            style = Stroke(hairPx() * 1.6f),
        )
    }
    drawCircle(st.oxideEdge, r, Offset(cx, cy), style = Stroke(linePx()))
    // 盘缘受光弧
    drawArc(
        color = Color(0x66C9A37E), startAngle = -150f, sweepAngle = 58f, useCenter = false,
        topLeft = Offset(cx - r * 0.985f, cy - r * 0.985f),
        size = Size(r * 1.97f, r * 1.97f),
        alpha = 0.55f,
        style = Stroke(1.8f * density, cap = StrokeCap.Round),
    )
    // 磁带自由端接缝：随盘转动 —— 同心圆本身转不转看不出来，全靠这条缝
    rotate(angle, Offset(cx, cy)) {
        drawLine(
            Color(0x73D8CBB4),
            Offset(cx, cy - r * 0.34f), Offset(cx, cy - r * 0.96f),
            strokeWidth = hairPx() * 2f,
        )
        drawLine(
            Color(0x40241C16),
            Offset(cx + hairPx() * 1.5f, cy - r * 0.34f), Offset(cx + hairPx() * 1.5f, cy - r * 0.96f),
            strokeWidth = hairPx(),
        )
    }
}

/** 六齿轮毂：径向金属渐变 + 六根辐条 + 中心六角孔（孔内上暗下亮 = 孔深）。 */
private fun DrawScope.hub(cx: Float, cy: Float, r: Float, angleDeg: Float, st: CassetteStyle) {
    rotate(angleDeg, Offset(cx, cy)) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFF4F3EE),
                0.68f to st.hub,
                1f to st.hubEdge,
                center = Offset(cx - r * 0.2f, cy - r * 0.24f),
                radius = r * 1.3f,
            ),
            r, Offset(cx, cy),
        )
        drawCircle(st.hubEdge, r, Offset(cx, cy), style = Stroke(hairPx()))
        for (i in 0 until 6) {
            val a = Math.toRadians(i * 60.0 - 90.0)
            val c = Math.cos(a).toFloat()
            val s = Math.sin(a).toFloat()
            // 辐条一亮一暗 —— 打破 60° 对称，转起来才看得见
            drawLine(
                st.hubEdge,
                Offset(cx + c * r * 0.44f, cy + s * r * 0.44f),
                Offset(cx + c * r * 0.95f, cy + s * r * 0.95f),
                strokeWidth = linePx(), alpha = if (i % 2 == 0) 0.95f else 0.45f,
            )
            // 辐条受光侧一道细亮线，轮毂就不平了
            drawLine(
                Color(0x59FFFFFF),
                Offset(cx + c * r * 0.44f - hairPx(), cy + s * r * 0.44f),
                Offset(cx + c * r * 0.95f - hairPx(), cy + s * r * 0.95f),
                strokeWidth = hairPx(), alpha = 0.5f,
            )
        }
        // 不对称亮标：轮毂上的一颗白点，旋转最直观的信号
        drawCircle(Color(0x8CFFFFFF), r * 0.12f, Offset(cx + r * 0.66f, cy - r * 0.10f))
        val hex = polygon(cx, cy, r * 0.42f, 6, 0f)
        drawPath(hex, Color(0xFF171512))
        drawArc(
            color = Color(0x59FFFFFF), startAngle = 20f, sweepAngle = 160f, useCenter = false,
            topLeft = Offset(cx - r * 0.42f, cy - r * 0.42f),
            size = Size(r * 0.84f, r * 0.84f),
            style = Stroke(hairPx()),
        )
        drawPath(hex, st.hubEdge, style = Stroke(hairPx()))
    }
}

/**
 * 「卡座是空的」这一状态也必须精确、安静 —— 而且一个字都不写。
 *
 * 注意：空卡座 / 外屏空磁带窗的走带结构已经统一到 [drawEmptyWell]（Mechanism.kt）——
 * 磁头块、压带轮、主导轴、导带轮、带盘轴都在那儿，**别在这里再画一份近似版**：
 * 外屏关着时露的就是同一个机构，两处画得不一样，翻开一看就露馅。
 */

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
