package com.augustana.tfold.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import com.augustana.tfold.machine.DuoMachine
import com.augustana.tfold.ui.parts.CASSETTE_H_RATIO
import com.augustana.tfold.ui.parts.hairPx
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 「咬住」判定 —— 先按住不动 1 秒，这一秒里什么都不算（松手就是普通点击）。
 * 过了这一关才进入删除计时，从这一秒之后起，松手只等于**撤销**，不再是点击。
 */
private const val HOLD_ARM_MS = 1000

/**
 * 删除计时本身 —— 3 秒。故意比系统的 longPressTimeout 长得多：
 * 这一栏没有撤销，慢一点、看得见进度线走完，才不会误删。
 */
private const val HOLD_MS = 3000

/** 删掉一格后「后一盘顶上来」的滑行时长；状态机的抽走动画也差不多这么长，两者同时跑。 */
private const val REMOVE_MS = 280

/** 只为在组合里持有一个可取消的动画句柄 —— 局部 `var` 会被手势闭包固化，不能用。 */
private class AnimHandle {
    var job: Job? = null
}

/**
 * 左栏：磁带库 —— 复刻 viditb 概念片的展开态左屏，**一个字都没有**。
 *
 * 钢蓝底（颜色直接采样自视频帧）+ 竖版封面大卡居中，两侧露出相邻卡的边；
 * 右上角一颗「＋」钮 = 给当前这盘带装一首本机音乐（SAF 选曲，全程无文字）。
 *
 * 交互 —— **整栏都是滑动区**（下方那片空白、左侧留白、指示轨那一带全算），
 * 拖拽改成自己攒：`page`（当前卡位）+ `Animatable frac`（拖动进度 0..1，画的就是它）。
 *
 *  · 横划（任意起手位置，含下方 / 左侧留白）= 走马灯跟手切换；松手按阈值或甩速定页
 *  · 点中间的磁带 = 送进卡座（装带动画的起点就是它自己的矩形）
 *  · 点两侧露出的边 = 翻到相邻那盘
 *  · 点「＋」= **新增一盘**（扫本机音乐库，把同一张专辑 / 同一个目录的歌收成一盘，并自动翻到它）
 *  · **长按中间的磁带** = 抽掉这盘：先咬住 1s（这一秒松手仍算点击），过了这一秒才开始 3s 的
 *    删除计时（卡缘橙线走满、机身持续震动）；计时中途松手只等于**撤销**，绝不会被当成点击；
 *    走满则卡位下沉、缩小、淡出。卡座里那盘和库里仅剩的那盘删不动（会提示），
 *    其余（含内置 4 盘）都能删，且落盘不再回来。
 *
 * 为什么不用 `HorizontalPager`：视觉是**自己画的**（`Canvas`），pager 只负责手感，
 * 但它和点击层是兄弟节点，点击层在前面、`detectTapGestures` 一到 down 就 consume，
 * pager 再也收不到拖拽 —— 于是下方/左侧横划全无反应，而"拖过卡片"还会被当成点击误装带。
 * 现在只有一个手势层：先判是否超过 touchSlop，越过才算拖拽，否则才走点击分支。
 *
 * 卡位宽度同时受「栏宽」与「栏高」夹住，取小者，并永远居中：
 * 于是任何宽高比下看到的是同一盘带、同一套比例，只是留白多少不同。
 */
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
        // 盒面 J 卡上的文字（目录号 / 艺术家 / 专辑 / 规格 / 轨数）要靠它量宽高。
        // 必须在 composition 作用域里拿 —— Canvas 的 draw lambda 不是 @Composable。
        val tm = rememberTextMeasurer()

        // page / frac 都用 MutableState **对象**保存，不用 `by` 局部委托：
        // 手势 lambda 的 keys 不含它们，重新组合时 lambda 不会重建，
        // 局部委托变量会被闭包固化在旧值上（经典 stale-capture 坑）——读对象属性永远是最新值。
        val pageState = remember { mutableIntStateOf(machine.index.coerceIn(0, maxOf(machine.tapes.lastIndex, 0))) }
        // 拖动进度：0 = 当前卡位居中，+1 = 下一盘已居中、-1 = 上一盘已居中。
        // 用普通 Float 状态而不是 Animatable：手势作用域是 @RestrictsSuspension，
        // `Animatable.snapTo` 在里面编译不过，而经 scope.launch 转发又会慢一帧。
        val frac = remember { mutableFloatStateOf(0f) }
        val anim = remember { AnimHandle() }
        val step = tw * 1.18f * u      // 相邻卡位间距（像素）

        // ---- 长按删除：先咬住 1s → 再算 3s 的进度 → 卡缘橙线走满才抽走那盘
        val holdIdx = remember { mutableIntStateOf(-1) }   // 正在被按住的那格（-1 = 没有）
        val holdP = remember { mutableFloatStateOf(0f) }   // 进度 0..1（画成沿卡边走的细线）
        val holdFired = remember { mutableStateOf(false) } // 这一轮已经触发过删除（松手时不许再当成点击）
        val armed = remember { mutableStateOf(false) }     // 过了 1s 那一关 —— 从此松手只算撤销，不算点击

        /** 弹回 / 滑到某个进度。新的一段动画先掐掉上一段，句柄必须存在对象里（局部 var 会被闭包固化）。 */
        fun glide(target: Float, spec: AnimationSpec<Float>, then: (() -> Unit)? = null) {
            anim.job?.cancel()
            anim.job = scope.launch {
                animate(frac.floatValue, target, animationSpec = spec) { v, _ -> frac.floatValue = v }
                then?.invoke()
            }
        }

        // 删除的收尾：抽走动画与「后一盘顶上来」同时跑，最后靠状态机的 focusTick
        // 把页号 + 进度一次性对齐（见下面的 effect），所以这里不需要 then 回调。
        fun fireRemove(i: Int) {
            val id = machine.tapes.getOrNull(i)?.id ?: return
            holdFired.value = true
            armed.value = false
            holdIdx.intValue = -1
            holdP.floatValue = 0f
            if (!machine.canRemove(id)) {
                machine.removeTape(id)     // 删不动（在卡座里 / 只剩一盘）—— 借它吐一句提示就走
                return
            }
            // 删掉中间那格之后，右边那盘顶上来；删的是最后一盘则左边那盘滑过来
            glide(
                if (i < machine.tapes.lastIndex) 1f else -1f,
                tween(REMOVE_MS, easing = FastOutSlowInEasing),
            )
            machine.removeTape(id)
        }

        LaunchedEffect(holdIdx.intValue) {
            if (holdIdx.intValue < 0) {
                holdP.floatValue = 0f
                machine.buzzStopDelete()   // 撤销（松手 / 拖走 / 删完）—— 持续震动也跟着停
                return@LaunchedEffect
            }
            val i = holdIdx.intValue
            if (machine.tapes.getOrNull(i) == null) return@LaunchedEffect
            holdP.floatValue = 0f
            // 第一关：按住不动 1 秒。这一秒里进度线不走，松手就是普通点击。
            delay(HOLD_ARM_MS.toLong())
            if (machine.tapes.getOrNull(i) == null) return@LaunchedEffect
            armed.value = true
            machine.buzzDelete()           // 计时开始 → 一直嗡嗡响到松手或走满
            animate(0f, 1f, animationSpec = tween(HOLD_MS, easing = LinearEasing)) { v, _ ->
                holdP.floatValue = v
            }
            fireRemove(i)
        }

        LaunchedEffect(pageState.intValue) { machine.focusIndex(pageState.intValue) }

        // 外部要求翻页（新加磁带后自动翻到它 / 删掉一盘后对齐新库）——
        // 页号与进度**一起**落定：被打断的滑行动画先掐掉，否则会差出一格。
        // 左栏自己的翻页不会碰 focusTick，所以不会回跳。
        LaunchedEffect(machine.focusTick) {
            if (machine.tapes.isNotEmpty()) {
                anim.job?.cancel()
                pageState.intValue = machine.index.coerceIn(0, machine.tapes.lastIndex)
                frac.floatValue = 0f
            }
        }

        // 「卡位在根坐标系里的矩形」上报给状态机 —— 装带动画的起点与这里画的是同一个矩形
        // （只上报**静止**位置：点击只在没拖动时才会走到装带分支）
        LaunchedEffect(pageState.intValue, realW, realH) {
            machine.cardRect = Rect(
                (pw / 2f - tw / 2f) * u,
                (cy - th / 2f) * u,
                (pw / 2f + tw / 2f) * u,
                (cy + th / 2f) * u,
            )
        }

        /** 点击翻页：先横滑一格到位，再落页 —— 免得瞬间跳变。 */
        fun stepPage(delta: Int) {
            val target = pageState.intValue + delta
            if (target !in machine.tapes.indices) return
            glide(if (delta > 0) 1f else -1f, tween(230, easing = FastOutSlowInEasing)) {
                pageState.intValue = target
                frac.floatValue = 0f
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
            // 在 draw 里读状态 → 只触发重绘，不引起重组
            drawAlbums(
                machine, pageState.intValue, frac.floatValue, u, pw, cy, tw, th, railY,
                holdIdx.intValue, holdP.floatValue, tm,
            )
            drawPlusButton(plusCx, plusCy, plusR)
        }

        // 唯一的手势层：铺满整栏（含下方留白、左侧留白、指示轨那一带）
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(step) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (machine.busy) return@awaitEachGesture

                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        var originX = down.position.x
                        var base = frac.floatValue

                        // 手指落在中间那张卡上 → 起「咬住」计时（1s 只认不动，之后 3s 才算删除）
                        if (!machine.busy &&
                            cardRectAt(0, frac.floatValue, u, pw, cy, tw, th).contains(down.position)
                        ) {
                            holdFired.value = false
                            armed.value = false
                            holdIdx.intValue = pageState.intValue
                            machine.buzzHold()   // 起手"嗒"一下：告诉手指长按已经开始了
                        }

                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(ch.uptimeMillis, ch.position)

                            if (!dragging && abs(ch.position.x - originX) > viewConfiguration.touchSlop) {
                                // 越过 touchSlop 才算拖拽；基准挪到此刻，避免起手跳一截
                                dragging = true
                                originX = ch.position.x
                                base = frac.floatValue
                                anim.job?.cancel()   // 上一段的收尾动画让位，别跟手指抢 frac
                                armed.value = false
                                holdIdx.intValue = -1 // 动了就不是长按，进度环撤掉
                            }
                            if (dragging) {
                                ch.consume()
                                val raw = base - (ch.position.x - originX) / step
                                // 两端加阻尼：到头还能拉出一点点，但拉不动整格
                                frac.floatValue = when {
                                    raw > 0f && pageState.intValue >= machine.tapes.lastIndex -> raw * 0.30f
                                    raw < 0f && pageState.intValue <= 0 -> raw * 0.30f
                                    else -> raw
                                }.coerceIn(-1f, 1f)
                            }
                            if (ch.changedToUpIgnoreConsumed()) break
                        }

                        if (dragging) {
                            armed.value = false
                            holdIdx.intValue = -1
                            val v = frac.floatValue
                            val vx = tracker.calculateVelocity().x
                            val dir = when {
                                v > 0.30f || vx < -900f -> 1
                                v < -0.30f || vx > 900f -> -1
                                else -> 0
                            }
                            val commit = dir.takeIf {
                                it != 0 && pageState.intValue + it in machine.tapes.indices
                            } ?: 0
                            if (commit != 0) {
                                // 落页：页号走一格、进度同步回退一格 —— 两者相抵消，画面不跳
                                pageState.intValue += commit
                                frac.floatValue = v - commit
                            }
                            glide(0f, spring(dampingRatio = 0.92f, stiffness = 420f))
                        } else if (holdFired.value) {
                            // 长按已经把带子抽走了，这一下松手什么都不做
                            holdFired.value = false
                            armed.value = false
                            holdIdx.intValue = -1
                        } else if (armed.value) {
                            // 咬住过了 1s、进度线还没走满就松手 = **反悔**。
                            // 这一下绝不能落进点击分支 —— 否则"想删又不敢删"的一松手
                            // 会把那盘带直接装进卡座（删不动它，倒先把它装上了）。
                            armed.value = false
                            holdIdx.intValue = -1
                            machine.buzzStopDelete()
                        } else {
                            // 1s 内就松手 = 就是点了一下（那 1s 的等待本身就是"反悔窗口"）
                            armed.value = false
                            holdIdx.intValue = -1
                            // ---- 点击分支（原语义全部保留）
                            val pos = down.position
                            val dx = pos.x - plusCx
                            val dy = pos.y - plusCy
                            if (dx * dx + dy * dy <= plusR * plusR * 1.7f) {
                                // 「＋」：扫本机音乐库，把**同一张专辑/同一个目录**的歌收成一盘磁带
                                // （没给过读取权限就先要权限，见 DuoManScreen）
                                machine.requestImport()
                            } else {
                                val cur = cardRectAt(0, frac.floatValue, u, pw, cy, tw, th)
                                when {
                                    cur.contains(pos) -> machine.insert(pageState.intValue)
                                    pos.x < cur.left -> stepPage(-1)
                                    pos.x > cur.right -> stepPage(1)
                                }
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
    holdIdx: Int,
    holdP: Float,
    tm: TextMeasurer,
) {
    // 卡位流：**远的先画**，中心的最后画（压在最上）。
    // 得画到 ±2：拖动进度能到整格（frac 0..±1），此时 ±1 那张已经居中、
    // ±2 那张的透明度还有约 0.5 —— 只画 ±1 会看到它"凭空跳出来"。
    // `a = 1 - 0.52*|d|` 在 |d| >= 1.92 处归零，所以 ±2 刚好够。
    listOf(page - 2, page + 2, page - 1, page + 1, page).forEach { i ->
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
        //  正在装/退的那盘 = 跟着状态机的开合动画走（**开度 0 也要算**：合着的也是这只盒子）
        //  其余 = 合着
        val animateBox = tape.id == machine.boxAnimId
        val open = when {
            tape.id == machine.loadedId -> 1f
            animateBox -> machine.boxOpen
            else -> 0f
        }

        // 正在被长按抽走的那盘：跟着 ejectAnim 下沉 + 缩小 + 淡出，像从库架上抽下来
        val gone = if (tape.id == machine.removingId) machine.ejectAnim.value else 0f

        translate(cx - w / 2f + w * 0.10f * gone, y + h * 0.66f * gone) {
            scale(1f - 0.20f * gone, pivot = Offset(w / 2f, h / 2f)) {
                if (open > 0.004f || animateBox) {
                    // 盒盖正在开 / 已开 —— 从合到开是连续的，不会再"先消失再出现"。
                    // `animateBox` 这一支是给"开度还是 0"那一帧用的：装带的带子是在
                    // 盒子还没动时就已经放进盒里（incoming 先置上），少了它开头会闪一个空框。
                    drawCoverCard(
                        tape, 0f, 0f, w, h, open, a * (1f - gone), machine.coverArt[tape.id],
                        tm, machine.trackCountOf(tape.id),
                    )
                } else if (away) {
                    // 磁带正飞往/飞离卡座，盒子又还没开始开 —— 卡位留一个精确的空框
                    drawRoundRect(
                        color = C.LibInk,
                        topLeft = Offset.Zero,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w * 0.03f),
                        alpha = a * 0.5f * (1f - gone),
                        style = Stroke(
                            width = hairPx() * 2f,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(4f * density, 3f * density), 0f
                            ),
                        ),
                    )
                } else {
                    drawCoverCard(
                        tape, 0f, 0f, w, h, 0f, a * (1f - gone), machine.coverArt[tape.id],
                        tm, machine.trackCountOf(tape.id),
                    )
                }
            }
        }
    }

    // 长按进度线：按住中间那张卡时，橙线沿**卡边本身**从左上角起走满一圈 ——
    // 这一栏一个字都没有，这就是"确认删除"的唯一提示。
    // 咬住那一秒（进度还是 0）先给一圈极淡的整轮廓：手别抬，抬了就是点击。
    if (holdIdx >= 0) {
        val r = cardRectAt(0, frac, u, pw, cy, tw, th).inflate(9f * u)
        val outline = Path().apply {
            addRoundRect(RoundRect(r, CornerRadius(10f * u)))
        }
        if (holdP > 0.002f) {
            val measure = PathMeasure().apply { setPath(outline, false) }
            val drawn = Path()
            if (measure.getSegment(0f, measure.length * holdP.coerceIn(0f, 1f), drawn, true)) {
                drawPath(drawn, C.Signal, style = Stroke(width = 3f * density, cap = StrokeCap.Round))
            }
        } else {
            drawPath(
                outline, C.Signal,
                alpha = 0.30f,
                style = Stroke(width = 2f * density, cap = StrokeCap.Round),
            )
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
