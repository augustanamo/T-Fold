package com.workbuddy.duoman.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.workbuddy.duoman.machine.DuoMachine
import com.workbuddy.duoman.model.Tape
import com.workbuddy.duoman.ui.parts.drawCassette
import com.workbuddy.duoman.ui.parts.hairPx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 总装 —— 一台真折叠机。
 *
 * 开合不在 App 里假装：**设备本身就是这台机器的合页**。
 *  · 横屏窗口（= 展开的内屏）：整屏是「打开的机器」—— 左边磁带库、右边卡座。
 *  · 竖屏窗口（= 合上的外屏）：整屏是「机器的正面」—— 磁带窗、指示器、一排键。
 *
 * 判断只用窗口宽高比，所以：
 *  · Z Fold 展开（2448×1848 横）→ 选带/装带；折上（1248×1972 竖）→ 播放器；
 *  · 普通手机竖屏 → 播放器；普通手机横屏 → 选带/装带。同一份布局，处处成立。
 */
@Composable
fun DuoManScreen(machine: DuoMachine) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current.density

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // 开合判定：WindowManager 的 FoldingFeature 说了算；没有折叠信息
        // （普通手机，或已经在外屏 —— 外屏没有铰链特征）才退回宽高比。
        val opened = machine.foldState ?: (w >= h)
        val leafW = w * 0.5f

        // 把「当前是开是合」同步给状态机 —— 它决定按键是否通电、退带往哪飞
        LaunchedEffect(opened) { machine.opened = opened }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            val id = machine.pendingTapeId
            if (uri != null && id != null) machine.assignAudio(id, uri) else machine.consumeAudioRequest()
        }
        LaunchedEffect(machine.pendingTapeId) {
            if (machine.pendingTapeId != null) picker.launch(arrayOf("audio/*"))
        }

        // 底色：防一帧闪白
        Canvas(Modifier.fillMaxSize()) { drawRect(C.Porcelain, Offset.Zero, size) }

        if (opened) {
            // 打开的机器：左库右座，中缝一道刻线
            Box(Modifier.size(Dp(leafW / density), Dp(h / density))) {
                AlbumsPane(machine, Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .offset(x = Dp(leafW / density))
                    .size(Dp(leafW / density), Dp(h / density))
            ) {
                DeckPane(machine, Modifier.fillMaxSize())
            }
            Canvas(Modifier.fillMaxSize()) { drawSeam(leafW) }

            // 装带 / 退带飞行层（起止矩形由两栏自己上报）
            FlightLayer(machine, density)
        } else {
            // 合上的机器：整屏正面
            FoldedFace(machine, tm, Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------- 中缝

/** 打开态两栏之间的一道缝：机身中脊 —— 深色，两端各一道定位槽。 */
private fun DrawScope.drawSeam(leafW: Float) {
    val band = 5f * density
    drawRect(C.DoorEdge, Offset(leafW - band, 0f), Size(band * 2f, size.height))
    drawRect(Color(0x38FFFFFF), Offset(leafW - band, 0f), Size(hairPx(), size.height))
    drawRect(Color(0x59FFFFFF), Offset(leafW + band - hairPx(), 0f), Size(hairPx(), size.height))
    val notch = size.height * 0.055f
    drawRect(C.Screw, Offset(leafW - hairPx(), notch * 0.35f), Size(hairPx() * 2f, notch))
    drawRect(
        C.Screw, Offset(leafW - hairPx(), size.height - notch * 1.35f),
        Size(hairPx() * 2f, notch),
    )
}

// ---------------------------------------------------------------- 磁带飞行

/**
 * 装带 / 退带。飞的都是**磁带本体**（包装盒留在库里）：
 * 磁带从盒口（盒体右侧）滑出，沿弧线飞进卡座落位。
 * 起止矩形由两栏自己上报（`machine.cardRect` / `machine.slotRect`），永远与画面对齐。
 *
 * **盒子是不透明的**：还在盒里 / 正落回盒里的那一段被盒体挡住 ——
 * 用「全屏 − 盒体矩形」的差集蒙版裁掉，磁带看起来是从开口里滑进滑出的。
 * 退带就是同一条路径倒放（flyOut 1 → 0）：飞出卡座、缩回盒里。
 */
@Composable
private fun FlightLayer(machine: DuoMachine, density: Float) {
    val src = machine.cardRect ?: return
    val dst = machine.slotRect ?: return

    Canvas(Modifier.fillMaxSize()) {
        val screen = Path().apply { addRect(Rect(Offset.Zero, size)) }
        val boxBody = Path().apply { addRect(src) }
        val visible = Path.combine(PathOperation.Difference, screen, boxBody)
        clipPath(visible) {
            machine.incoming?.let { id ->
                machine.tapeById(id)?.let { tape ->
                    drawFlyingTape(tape, machine.flyIn.value, src, dst, density)
                }
            }
            machine.outgoing?.let { id ->
                machine.tapeById(id)?.let { tape ->
                    drawFlyingTape(tape, machine.flyOut.value, src, dst, density)
                }
            }
        }
    }
}

/** 一盘飞行中的磁带：沿 src→dst 的弧线走，appear 阶段从盒口探出（放大 + 微抬）。 */
private fun DrawScope.drawFlyingTape(
    tape: Tape,
    p: Float,
    src: Rect,
    dst: Rect,
    density: Float,
) {
    val p2 = p.coerceIn(0f, 1f)
    val appear = 0.26f                              // 「出现」阶段占比
    val q = ramp(p2, appear, 1f)                    // 飞行阶段 0..1
    val pop = mix(0.62f, 1f, ramp(p2, 0f, appear))  // 从盒里升出时的放大

    val boxW = src.width
    val boxH = src.height
    val cx = mix(src.center.x, dst.center.x, q)
    val cy = mix(src.center.y, dst.center.y, q) -
        abs(dst.center.x - src.center.x) * 0.12f * sin((PI * q).toFloat()) -
        (1f - ramp(p2, 0f, appear)) * 14f * density
    val sx = mix(1f, dst.width / boxW, q) * pop
    val sy = mix(1f, dst.height / boxH, q) * pop

    // 围绕磁带中心做旋转/缩放（translate 到中心 → rotate/scale → 平移回左上角）
    withTransform({
        translate(cx, cy)
        rotate(-9f * sin((PI * q).toFloat()), pivot = Offset.Zero)
        scale(sx, sy, pivot = Offset.Zero)
        translate(-boxW / 2f, -boxH / 2f)
    }) {
        drawCassette(
            w = boxW,
            h = boxH,
            tape = tape,
            tapePos = 0f,
            angleTop = 0f,
            angleBot = 0f,
        )
    }
}
