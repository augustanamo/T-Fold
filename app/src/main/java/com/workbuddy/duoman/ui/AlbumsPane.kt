package com.workbuddy.duoman.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import com.workbuddy.duoman.machine.DuoMachine
import com.workbuddy.duoman.ui.parts.CASSETTE_H_RATIO
import com.workbuddy.duoman.ui.parts.hairPx
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 左栏：磁带库 —— 复刻 viditb 概念片的展开态左屏，**一个字都没有**。
 *
 * 钢蓝底（颜色直接采样自视频帧）+ 竖版封面大卡居中，两侧露出相邻卡的边；
 * 右上角一颗「＋」钮 = 给当前这盘带装一首本机音乐（SAF 选曲，全程无文字）。
 *
 * 交互：
 *  · 左右滑动 = 换一盘带
 *  · 点中间的磁带 = 送进卡座（装带动画的起点就是它自己的矩形）
 *  · 点两侧露出的边 = 翻到相邻那盘
 *  · 点「＋」= 给当前磁带绑定本机音乐
 *
 * 卡位宽度同时受「栏宽」与「栏高」夹住，取小者，并永远居中：
 * 于是任何宽高比下看到的是同一盘带、同一套比例，只是留白多少不同。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsPane(
    machine: DuoMachine,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val realW = constraints.maxWidth.toFloat()
        val realH = constraints.maxHeight.toFloat()
        val u = realW / DESIGN_W
        val pw = DESIGN_W
        val ph = realH / u
        val pad = PANE_PAD

        val railY = ph - pad * 1.5f
        val bandTop = pad * 2.6f
        val bandH = railY - pad * 1.2f - bandTop
        val tw = minOf((pw - pad * 2f) * 0.80f, bandH * 0.94f / CASSETTE_H_RATIO)
        val th = tw * CASSETTE_H_RATIO
        val cy = bandTop + bandH / 2f

        // 「＋」钮：右上角，给当前卡位那盘带装曲目
        val plusCx = (pw - pad * 0.95f) * u
        val plusCy = pad * 1.15f * u
        val plusR = pad * 0.55f * u

        val scope = rememberCoroutineScope()
        val pager = rememberPagerState { machine.tapes.size }
        LaunchedEffect(pager.currentPage) { machine.focusIndex(pager.currentPage) }

        // 「卡位在根坐标系里的矩形」上报给状态机 —— 装带动画的起点与这里画的是同一个矩形
        LaunchedEffect(pager.currentPage, realW, realH, pager.isScrollInProgress) {
            if (!pager.isScrollInProgress) {
                machine.cardRect = Rect(
                    (pw / 2f - tw / 2f) * u,
                    (cy - th / 2f) * u,
                    (pw / 2f + tw / 2f) * u,
                    (cy + th / 2f) * u,
                )
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            // 钢蓝底 + 底部暗角（视频里左屏的微弱纵深）
            drawRect(C.LibBlue, Offset.Zero, size)
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to C.LibBlueDeep.copy(alpha = 0.55f),
                    startY = size.height * 0.55f,
                    endY = size.height,
                ),
                Offset.Zero, size,
            )
            drawAlbums(machine, pager.currentPage, pager.currentPageOffsetFraction, u, pw, cy, tw, th, railY)
            drawPlusButton(plusCx, plusCy, plusR)
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !machine.busy,
        ) { /* 视觉由上面的 Canvas 负责，这里只保留翻页手感 */ }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(machine.busy) {
                    detectTapGestures { pos ->
                        if (machine.busy) return@detectTapGestures
                        // 「＋」：给当前这盘带装一首本机音乐
                        val dx = pos.x - plusCx
                        val dy = pos.y - plusCy
                        if (dx * dx + dy * dy <= plusR * plusR * 1.7f) {
                            machine.requestAudio(machine.tapes[pager.currentPage].id)
                            return@detectTapGestures
                        }
                        val frac = pager.currentPageOffsetFraction
                        val cur = cardRectAt(0, frac, u, pw, cy, tw, th)
                        // 露出来的两侧边：翻页。中间：装带。
                        when {
                            cur.contains(pos) -> machine.insert(pager.currentPage)
                            pos.x < cur.left -> scope.launch {
                                pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0))
                            }

                            pos.x > cur.right -> scope.launch {
                                pager.animateScrollToPage(
                                    (pager.currentPage + 1).coerceAtMost(machine.tapes.lastIndex)
                                )
                            }
                        }
                    }
                }
        )
    }
}

/** 卡位间距 = 卡宽 × 1.18 —— 相邻卡正好从两侧露出一条边（概念片里那种走马灯）。 */
private fun cardRectAt(delta: Int, frac: Float, u: Float, pw: Float, cy: Float, tw: Float, th: Float): Rect {
    val d = delta - frac
    val s = 1f - 0.12f * abs(d)
    val w = tw * s * u
    val h = th * s * u
    val cx = (pw / 2f + d * tw * 1.18f) * u
    val y = (cy - th * s / 2f) * u
    return Rect(cx - w / 2f, y, cx + w / 2f, y + h)
}

/** 「＋」钮：装曲目。一道圆、一个加号 —— 不需要任何文字。 */
private fun DrawScope.drawPlusButton(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0x2EFFFFFF), r, Offset(cx, cy))
    drawCircle(C.LibInk, r, Offset(cx, cy), alpha = 0.85f, style = Stroke(1f * density))
    val l = r * 0.44f
    val w = 1.6f * density
    drawLine(C.LibInk, Offset(cx - l, cy), Offset(cx + l, cy), strokeWidth = w)
    drawLine(C.LibInk, Offset(cx, cy - l), Offset(cx, cy + l), strokeWidth = w)
}

private fun DrawScope.drawAlbums(
    machine: DuoMachine,
    page: Int,
    frac: Float,
    u: Float,
    pw: Float,
    cy: Float,
    tw: Float,
    th: Float,
    railY: Float,
) {
    // 卡位流：远的先画。相邻卡位只做降透明度 + 略缩，全部对齐同一条中线。
    listOf(page - 1, page + 1, page).forEach { i ->
        if (i !in machine.tapes.indices) return@forEach
        val tape = machine.tapes[i]
        val d = (i - page) - frac
        val s = 1f - 0.12f * abs(d)
        val w = tw * s * u
        val h = th * s * u
        val cx = (pw / 2f + d * tw * 1.18f) * u
        val y = (cy - th * s / 2f) * u
        val a = (1f - 0.52f * abs(d)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val away = tape.id == machine.loadedId ||
            tape.id == machine.incoming ||
            tape.id == machine.outgoing

        // 卡位状态 → 盒盖开合度：
        //  卡座里那盘 = 常开（带子已被拿走）
        //  正在装/退的那盘 = 跟着状态机的开合动画走
        //  其余 = 合着
        val open = when (tape.id) {
            machine.loadedId -> 1f
            machine.boxAnimId -> machine.boxOpen
            else -> 0f
        }

        translate(cx - w / 2f, y) {
            if (open > 0.004f) {
                // 盒盖正在开 / 已开 —— 从合到开是连续的，不会再"先消失再出现"
                drawCoverCard(tape, 0f, 0f, w, h, open, a, machine.coverArt[tape.id])
            } else if (away) {
                // 磁带正飞往/飞离卡座，盒子又还没开始开 —— 卡位留一个精确的空框
                drawRoundRect(
                    color = C.LibInk,
                    topLeft = Offset.Zero,
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w * 0.03f),
                    alpha = a * 0.5f,
                    style = Stroke(
                        width = hairPx() * 2f,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4f * density, 3f * density), 0f
                        ),
                    ),
                )
            } else {
                drawCoverCard(tape, 0f, 0f, w, h, 0f, a, machine.coverArt[tape.id])
            }
        }
    }

    // 位置指示器：一根细轨 + 一个安全橙滑块（蓝底上用白色轨）
    val pad = PANE_PAD
    val railC = Color(0x59FFFFFF)
    drawLine(railC, Offset(pad * u, railY * u), Offset((pw - pad) * u, railY * u), strokeWidth = hairPx())
    val n = machine.tapes.size
    val rel = (page + frac).coerceIn(0f, (n - 1).toFloat())
    val knobX = (pad + (pw - pad * 2f) * (if (n <= 1) 0f else rel / (n - 1))) * u
    drawRoundRect(
        if (machine.loadedId != null) C.Signal else C.LibInk,
        Offset(knobX - 13f * u, railY * u - 1.5f * density),
        Size(26f * u, 3f * density),
        CornerRadius(1.5f * density),
    )
}
