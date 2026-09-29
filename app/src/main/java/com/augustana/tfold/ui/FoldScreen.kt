package com.augustana.tfold.ui

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
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
import com.augustana.tfold.machine.FoldMachine
import com.augustana.tfold.model.Tape
import com.augustana.tfold.ui.parts.CASSETTE_IN_BOX
import com.augustana.tfold.ui.parts.CASSETTE_IN_BOX_X
import com.augustana.tfold.ui.parts.drawCassette
import com.augustana.tfold.ui.parts.hairPx
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
fun FoldScreen(machine: FoldMachine) {
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
            // 注意：这台 Fold 上选曲期间 TFold 会被压到后台，进程随时可能被回收。
            // 重启后系统仍会把 uri 补投回来，但内存里的「给谁选的」没了 ——
            // claimPickedTapeId() 会从盘上把 id 捡回来，绝不静默丢歌。
            if (uri != null) {
                val id = machine.claimPickedTapeId()
                android.util.Log.i("TFold", "picker result uri=$uri -> id=$id")
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

        // 麦克风权限：按 REC 那一刻才要，拿到就把那一下录音做完
        val mic = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) machine.onRecPermissionGranted()
            else machine.hint("没有麦克风权限 · 录不了")
        }
        LaunchedEffect(machine.recAsk) {
            if (machine.recAsk > 0) mic.launch(android.Manifest.permission.RECORD_AUDIO)
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
private fun FlightLayer(machine: FoldMachine, density: Float) {
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
                    drawFlyingTape(tape, machine.flyIn.value, src, dst, density, machine.tintFor(tape))
                }
            }
            machine.outgoing?.let { id ->
                machine.tapeById(id)?.let { tape ->
                    drawFlyingTape(tape, machine.flyOut.value, src, dst, density, machine.tintFor(tape))
                }
            }
        }
    }
}

/**
 * 一盘飞行中的磁带：沿 src→dst 的弧线走，**并且是一块有厚度的塑料**。
 *
 * p = 0 是**躺在盒腔里**的静止位（这一点很关键：装带时盒子一开，带子就已经在那儿了）：
 *  —— 尺寸比盒子收一圈（盒与带同为 1.56 宽高比），中心取偏盒口那一侧，
 *     于是它的两个轮毂正好落在盖板自由边之外，从盒口看得见；
 *  —— 出盒阶段（appear）再抬一点、开始沿弧线飞向卡座。
 * 退带就是这条路径的倒放：带子从右侧滑回盒腔、被盖板逐渐吃掉，盖合上。
 *
 * 立体感来自**四件事同时到中途最大**（[spin]，q = 0.5 时为 1）—— 原来只有平移 + 缩放，
 * 所以看着像一张卡片滑过去：
 *  · **偏航**：正面横向按 cos 收窄。带子在空中真的"转了一下"，而不是平移；
 *  · **厚度**：侧壁露出来一道（[drawCassette] 的 depthX/depthY）。真实带盒 100×64×12mm，
 *    厚度只有高度的 0.19 —— 绕竖轴转 26° 露出 0.19·sin26° ≈ 0.083 高的右侧壁，
 *    略俯 22° 露出底缘同理。屏幕上看就是"它有边"；
 *  · **投影**：壳外一圈软影，浮得越高越远越淡。
 *
 * 两头**必须回零**：盒里那盘留一点点斜角（它躺在盒腔里，本来就不是正面贴平），
 * 落进卡座时则要完全归零 —— 卡座里那盘是正面贴平的，留一道侧壁会和它错位、像跳了一下。
 */
private fun DrawScope.drawFlyingTape(
    tape: Tape,
    p: Float,
    src: Rect,
    dst: Rect,
    density: Float,
    tint: Color,
) {
    val p2 = p.coerceIn(0f, 1f)
    val appear = 0.18f                              // 「出盒 / 回盒」阶段占比
    val q = ramp(p2, appear, 1f)                    // 飞行阶段 0..1

    val boxW = src.width
    val boxH = src.height
    val rest = CASSETTE_IN_BOX                     // 躺在盒里时的尺寸（相对盒宽）
    val restX = src.left + src.width * CASSETTE_IN_BOX_X   // 盒里的模位一带（盒腔模位按同一个数画）
    val lift = ramp(p2, 0f, appear) * 12f * density  // 出盒先抬一点，再飞
    val dist = abs(dst.center.x - restX)
    // 0 → 1 → 0：两头贴平、中途最立体
    val spin = sin((PI * q).toFloat())
    val cx = mix(restX, dst.center.x, q)
    val cy = mix(src.center.y, dst.center.y, q) - lift - dist * 0.14f * spin
    // 尺寸在**出盒那一段**就长到卡座尺寸 —— 这一段大半还被盖板挡着。
    // 反过来（一路线性长大）会看到带子在半空里边飞边涨：两栏的尺度本来就不一样
    // （卡位盒小、卡座井大），只能挑个看不见的地方把差值补掉。
    val sQ = ramp(p2, 0f, appear * 1.6f)
    val sx = mix(rest, dst.width / boxW, sQ)
    val sy = mix(rest, dst.height / boxH, sQ)

    // ---- 立体：角度 → 侧壁位移 ----
    val deg = (PI / 180f).toFloat()
    val restBud = 1f - ramp(q, 0f, 0.25f)           // 盒里那点斜角，一起飞就收回
    val yaw = (26f * spin + 11f * restBud) * deg    // 绕竖轴转：露出右侧壁
    val pitch = (21f * spin + 15f * restBud) * deg  // 略俯视：露出底缘
    // 真实带盒厚 12mm ÷ 宽 63.8mm = 0.19 × **宽度**。
    // 别写成 boxH * 0.19 —— 带子是竖着画的（h = 1.56w），那样侧壁会厚出 56%，
    // 一整条宽边摊在右边，"一片玻璃"就更像"两片"了。
    val thick = boxW * 0.19f
    val depX = thick * sin(yaw)
    val depY = thick * sin(pitch)
    // 正面横向收窄（偏航的前缩）：两头严格 1.0，免得和盒腔 / 卡座里那盘对不上
    val faceX = 1f - 0.11f * spin

    withTransform({
        translate(cx, cy)
        rotate(-8f * spin, pivot = Offset.Zero)
        scale(sx * faceX, sy, pivot = Offset.Zero)
        translate(-boxW / 2f, -boxH / 2f)
    }) {
        // 投影：只画在**带子之外**。清壳是半透明的，影子压进壳里会像糊了一块脏东西；
        // 而且"物体挡住自己的影子"本来也是对的。
        // 带子真正占的地方是 [0,boxW+depX]×[0,boxH+depY]（侧壁朝右 / 下挤出），
        // 影子得正好被这块挡住，不是四边都往外扩一圈
        val slab = Path().apply {
            addRoundRect(
                RoundRect(0f, 0f, boxW + depX, boxH + depY, CornerRadius(boxW * 0.05f))
            )
        }
        val outside = Path.combine(
            PathOperation.Difference,
            Path().apply { addRect(Rect(-boxW * 3f, -boxH * 3f, boxW * 4f, boxH * 4f)) },
            slab,
        )
        // 浮得越高：影子落得越远、越大、越淡；落座前淡出，把卡座交还给卡座自己的画法
        val fly = spin * (1f - ramp(q, 0.88f, 1f))
        clipPath(outside) {
            softShadow(
                footW = boxW * 0.96f,
                footH = boxH * 0.96f,
                centerX = boxW * 0.5f + depX * 0.6f + boxW * 0.02f,
                centerY = boxH * 0.5f + boxH * (0.13f + 0.15f * fly),
                r = boxW * 0.05f,
                alpha = 0.15f * (0.42f + 0.58f * spin) * (1f - ramp(q, 0.88f, 1f)),
            )
        }

        drawCassette(
            w = boxW,
            h = boxH,
            tape = tape,
            tapePos = 0f,
            angleTop = 0f,
            angleBot = 0f,
            tint = tint,
            depthX = depX,
            depthY = depY,
        )
    }
}

/**
 * 软影：几层**逐渐长大、逐渐变淡**的圆角矩形叠出模糊感（Canvas 没有高斯模糊）。
 * 层数不多是有意的 —— 这东西每帧都要画，层数就是这里的成本。
 */
private fun DrawScope.softShadow(
    footW: Float,
    footH: Float,
    centerX: Float,
    centerY: Float,
    r: Float,
    alpha: Float,
) {
    if (alpha <= 0.002f) return
    val layers = 5
    for (i in 0 until layers) {
        val k = i / (layers - 1f)
        drawRoundRect(
            Color(0xFF14120F),
            topLeft = Offset(centerX - footW * (0.5f + 0.035f * k), centerY - footH * (0.5f + 0.035f * k)),
            size = Size(footW * (1f + 0.07f * k), footH * (1f + 0.07f * k)),
            cornerRadius = CornerRadius(r * (1f + 0.12f * k)),
            alpha = alpha * (1f - 0.55f * k) * 0.42f,
        )
    }
}
