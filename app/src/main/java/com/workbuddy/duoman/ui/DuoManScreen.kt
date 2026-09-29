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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * 总装 —— 一台真折叠机。
 *
 * 开合不在 App 里假装：**设备本身就是这台机器的合页**。
 *  · 横屏窗口（= 展开的内屏）：整屏是「打开的机器」—— 左边磁带库、右边卡座。
 *  · 竖屏窗口（= 合上的外屏）：整屏是「机器的正面」—— 磁带窗、指示器、一排键。
 *
 * 判断用「窗口宽高比」+「面板原生形状」两层（见下面 `opened` 的注释），所以：
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
        // 开合判定：**面板原生形状 + 窗口形状**两层。
        //
        // 只看窗口宽高比是不够的 —— 合盖时 `pinOrientation()` 会把方向钉成 PORTRAIT，
        // 再展开时如果方向还没来得及重钉，窗口就被 letterbox 压成竖幅（实测：合上再展开，
        // 2448×1848 的内屏上出现一个竖幅窗口），单看宽高比就会把内屏判成"合上"→ 出播放面。
        // 所以多问一句**面板原生是不是横幅**：是横幅面板，就算窗口现在是竖幅也按内屏处理。
        //
        // `LocalConfiguration` 作 key —— 换屏/改方向都会让配置变，这个判定随之重算。
        val cfg = LocalConfiguration.current
        val act = LocalContext.current as? android.app.Activity
        val panelLandscape = remember(cfg, act) {
            val d = runCatching { act?.display }.getOrNull()
            if (d == null) null else {
                val p = android.graphics.Point()
                @Suppress("DEPRECATION") d.getRealSize(p)
                val swapped = d.rotation == android.view.Surface.ROTATION_90 ||
                    d.rotation == android.view.Surface.ROTATION_270
                val nw = if (swapped) p.y else p.x
                val nh = if (swapped) p.x else p.y
                nw > nh
            }
        }
        val opened = when {
            w >= h -> true                          // 横幅窗口 = 展开的内屏
            panelLandscape == true -> true          // 被 letterbox 的竖幅窗口，但面板本身是横幅 → 仍是内屏
            machine.foldState == true -> true       // 竖幅窗口 + 折叠特征报 FLAT（半开的内屏）
            else -> false                           // 外屏 / 普通手机竖屏 = 播放器
        }
        val leafW = w * 0.5f

        // 把「当前是开是合」同步给状态机 —— 它决定按键是否通电、退带往哪飞
        LaunchedEffect(opened) { machine.opened = opened }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            // 注意：这台 Fold 上选曲期间 DuoMan 会被压到后台，进程随时可能被回收。
            // 重启后系统仍会把 uri 补投回来，但内存里的「给谁选的」没了 ——
            // claimPickedTapeId() 会从盘上把 id 捡回来，绝不静默丢歌。
            if (uri != null) {
                val id = machine.claimPickedTapeId()
                android.util.Log.i("DuoMan", "picker result uri=$uri -> id=$id")
                machine.assignAudio(id, uri)
            } else {
                machine.consumeAudioRequest()
            }
        }
        LaunchedEffect(machine.pendingTapeId) {
            if (machine.pendingTapeId != null) picker.launch(arrayOf("audio/*"))
        }

        // 读本机音乐库的权限：「＋」按下时才要（不预先弹），拿到就立刻扫描导入
        val perm = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) machine.importLocalMusic()
            else machine.hint("没有权限 · 只能手动选曲")
        }
        LaunchedEffect(machine.permissionAsk) {
            if (machine.permissionAsk > 0) {
                perm.launch(
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        android.Manifest.permission.READ_MEDIA_AUDIO
                    else android.Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
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
 * **遮挡用的是"盖板"，不是"整只盒子"** —— 这是关键：
 *  · 盒体本身是敞开的（盖板绕左缘向观众转开 ≈48°），右边露出来的那块是**内腔**，
 *    磁带正是从那儿滑出去的，它当然应该看得见；
 *  · 真正挡住磁带的是那块斜着的盖板（占左边 cos(48°) ≈ 2/3 宽）。
 * 曾按**整只盒子**做差集蒙版：盒内的一切全被裁掉，磁带得整个越过盒子的右外缘
 * 才第一次现形 —— 看着就是从盒子**背后**冒出来，而不是从盒子里滑出来。
 * 现在蒙版按盖板当前开度算宽（合着时盖板 = 整只盒子宽，等于原来的行为），
 * 所以"合 → 开 → 带子滑出"是一条连续的线。
 */
@Composable
private fun FlightLayer(machine: DuoMachine, density: Float) {
    val src = machine.cardRect ?: return
    val dst = machine.slotRect ?: return

    Canvas(Modifier.fillMaxSize()) {
        // 盖板宽度 = 盒宽 × cos(开度角)，与 Cover.kt 里 drawCoverCard 的透视压扁同源
        val e = machine.boxOpen.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val lidW = src.width * cos((PI * 0.306 * e).toFloat())
        val screen = Path().apply { addRect(Rect(Offset.Zero, size)) }
        val lid = Path().apply {
            addRect(
                Rect(
                    src.left, src.top - src.height * 0.028f,
                    src.left + lidW, src.bottom + src.height * 0.028f,
                )
            )
        }
        val visible = Path.combine(PathOperation.Difference, screen, lid)
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

/**
 * 一盘飞行中的磁带：沿 src→dst 的弧线走。
 *
 * p = 0 是**躺在盒腔里**的静止位（这一点很关键：装带时盒子一开，带子就已经在那儿了）：
 *  —— 尺寸比盒子收一圈（盒与带同为 1.56 宽高比），中心取偏盒口那一侧，
 *     于是它的两个轮毂正好落在盖板自由边之外，从盒口看得见；
 *  —— 出盒阶段（appear）再抬一点、开始沿弧线飞向卡座。
 * 退带就是这条路径的倒放：带子从右侧滑回盒腔、被盖板逐渐吃掉，盖合上。
 */
private fun DrawScope.drawFlyingTape(
    tape: Tape,
    p: Float,
    src: Rect,
    dst: Rect,
    density: Float,
) {
    val p2 = p.coerceIn(0f, 1f)
    val appear = 0.18f                              // 「出盒 / 回盒」阶段占比
    val q = ramp(p2, appear, 1f)                    // 飞行阶段 0..1

    val boxW = src.width
    val boxH = src.height
    val rest = 0.76f                                // 躺在盒里时的尺寸（相对盒宽）
    val restX = src.left + src.width * 0.60f        // 盒口内侧的模位一带
    val lift = ramp(p2, 0f, appear) * 12f * density  // 出盒先抬一点，再飞
    val cx = mix(restX, dst.center.x, q)
    val cy = mix(src.center.y, dst.center.y, q) -
        lift - abs(dst.center.x - restX) * 0.12f * sin((PI * q).toFloat())
    val sx = mix(rest, dst.width / boxW, q)
    val sy = mix(rest, dst.height / boxH, q)

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
