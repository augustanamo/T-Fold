package com.augustana.tfold

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.augustana.tfold.audio.AudioEngine
import com.augustana.tfold.machine.FoldMachine
import com.augustana.tfold.ui.FoldScreen
import com.augustana.tfold.ui.FoldedFace
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "TFold"

class MainActivity : ComponentActivity() {

    private var machine: FoldMachine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val audio = AudioEngine(this)

        setContent {
            // 作用域必须来自 composition —— 只有它的 CoroutineContext 带 MonotonicFrameClock，
            // 状态机里的 Animatable.animateTo（装带、退带）才跑得起来。
            val scope = rememberCoroutineScope()
            val m = remember { FoldMachine(applicationContext, audio, scope).also { machine = it } }
            MachineTicker(m)
            FoldScreen(m)
        }

        observeFoldState()
        watchWindowArea()
        watchDisplays()
    }

    // ---------------------------------------------------------- 面板方向（外屏恒竖屏）

    /**
     * 按**面板原生形状**钉方向，不看传感器。
     *
     * 合上盖子时 app 落在 1248×1972 的竖幅外屏上，一旦系统自动旋转打开、机身被转 90°，
     * 窗口就变成 1972×1248 —— 宽高比判定随即把**内屏那套横屏双栏**搬到外屏上（实测过）。
     * 所以竖幅面板必须强制 PORTRAIT：方向跟着面板本身，而不是跟着手持姿势。
     *
     * 判定用「原生尺寸」而不是窗口尺寸：`getRealSize` 给的是**当前旋转下**的尺寸，
     * 先用 `rotation` 反推回原生 —— 否则我们自己改的方向会污染下一次判定，形成回路。
     *
     * 横幅面板（内屏 2448×1848）不做任何锁定：给横幅面板请求 PORTRAIT 会被 letterbox 成半屏。
     */
    private fun pinOrientation() {
        val d = display ?: return
        val p = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        val swapped = d.rotation == Surface.ROTATION_90 || d.rotation == Surface.ROTATION_270
        val nw = if (swapped) p.y else p.x
        val nh = if (swapped) p.x else p.y
        val want = if (nh > nw) {
            // 竖幅面板（外屏 1248×1972）→ 钉死竖屏。
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            // 横幅面板（内屏 2448×1848）→ 钉死横屏。
            // 请求方向必须和面板原生方向**一致**：给横幅面板请求竖屏才会被 letterbox 成半屏，
            // 请求横屏就是整块面板，和 UNSPECIFIED 下的正常态一样满屏。
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (requestedOrientation != want) {
            Log.i(TAG, "pinOrientation panel=${nw}x$nh -> $want")
            requestedOrientation = want
        }
    }

    /**
     * 面板切换（内屏亮/灭、外屏接手机身旋转）都会走 DisplayListener —— 每次重新钉一遍方向。
     * 钉完若方向没变则不再发起请求，所以不会自激。
     */
    private fun watchDisplays() {
        pinOrientation()
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = onDisplaySwitch()
                override fun onDisplayRemoved(displayId: Int) = onDisplaySwitch()
                override fun onDisplayChanged(displayId: Int) = onDisplaySwitch()
            },
            null,
        )
    }

    /**
     * 面板换了（内屏亮/灭、Activity 被挪到另一块屏）。
     *
     * Activity 的窗口被系统搬到另一块屏上时，`display` 会变但 **onCreate/onResume 都不重跑**，
     * 所以这里必须手动做三件事，否则表现就是「合上再展开，内屏上还停在播放面」：
     *  1. 重新钉方向 —— 新面板的原生形状可能不一样；
     *  2. 重新订阅折叠特征 —— 旧订阅绑在旧窗口上，不会再发新值，
     *     而 `FoldScreen` 在竖幅窗口上会去问 `foldState`，僵值直接导致画面错。
     * 外屏呈现那条 flow 是全局的（不绑窗口），不需要在这里重订。
     */
    private fun onDisplaySwitch() {
        pinOrientation()
        observeFoldState()
    }

    override fun onResume() {
        super.onResume()
        pinOrientation()
        observeFoldState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 里声明了 orientation，所以这里拿得到「方向变了」而不会被重建。
        pinOrientation()
    }

    // ---------------------------------------------------------- 音量键

    private val volumeKeys = VolumeKeyHandler { machine }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (volumeKeys.onKeyEvent(event)) true else super.dispatchKeyEvent(event)

    /**
     * 官方折叠态通道：Jetpack WindowManager 的 FoldingFeature。
     *
     * 内屏摊开 = FLAT → 选带 / 装带；半开或合上 → 播放器。
     * 外屏没有铰链特征（foldState 置 null），UI 退回宽高比判断；
     * 系统会把顶层 Activity 连续地挪到外屏 —— 播放面因此始终落在外屏。
     */
    /** 上次订阅折叠特征时所在的屏 —— 换屏就必须重订。 */
    private var foldDisplayId = Int.MIN_VALUE
    private var foldJob: Job? = null

    /**
     * 折叠态（FoldingFeature）。
     *
     * **订阅是绑在 Activity 的窗口上的**：窗口被系统从内屏挪到外屏（或反过来）之后，
     * 原来那个 flow 不会再发新值，`foldState` 就僵在换屏前的那个值上 ——
     * 实测表现正是「合上再展开，内屏上还按半开给出播放面」。
     * 所以每次**换屏**：取消旧订阅 → 把状态退回 null（让宽高比判定先顶上）→ 重新订阅。
     */
    private fun observeFoldState() {
        val id = display?.displayId ?: -1
        if (id == foldDisplayId && foldJob?.isActive == true) return
        foldDisplayId = id
        foldJob?.cancel()
        machine?.foldState = null
        Log.i(TAG, "foldState: re-bind on display $id")
        foldJob = lifecycleScope.launch {
            WindowInfoTracker.getOrCreate(this@MainActivity)
                .windowLayoutInfo(this@MainActivity)
                .collect { info ->
                    // 折叠特征一变，正在用的面板多半也变了 —— 顺手重钉一次方向
                    pinOrientation()
                    val ff = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    val st = when {
                        ff == null -> null
                        ff.state == FoldingFeature.State.FLAT -> true
                        else -> false
                    }
                    if (machine?.foldState != st) {
                        Log.i(TAG, "foldState: $st (feature=${ff?.state}, display=${display?.displayId})")
                    }
                    machine?.foldState = st
                }
        }
    }

    // ---------------------------------------------------------- 外屏呈现（Window Area）

    private var coverView: ComposeView? = null

    /** 呈现会话请求去重：flow 在同一毫秒可能连发多次 AVAILABLE。 */
    @Volatile
    private var presentationRequested = false

    /** 外屏 composition 的生命周期宿主：外屏不是 Activity，ComposeView 得自己带一个。 */
    private var coverOwner: CoverOwner? = null

    /**
     * 外屏窗口的 LifecycleOwner。
     *
     * 置 RESUMED 后不再变化 —— 折叠时内屏熄屏、主 Activity 走 onPause，
     * 但外屏上的播放面必须继续出帧，所以不能跟随主 Activity 的生命周期。
     */
    private class CoverOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    /**
     * 监听外屏区域：设备一报告「后背外屏可呈现」，就请求一次呈现会话。
     * 会话里 `onSessionStarted(presenter)` 会把播放面（FoldedFace）挂到外屏的窗口上 ——
     * 折叠后内屏熄灭，这台机器的正面就常驻外屏。
     */
    private fun watchWindowArea() {
        lifecycleScope.launch {
            WindowAreaController.getOrCreate().windowAreaInfos.collect { areas ->
                val rear = areas.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                val status = rear
                    ?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)
                    ?.status
                Log.i(TAG, "WindowArea rear presentation status: $status")
                if (status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE &&
                    machine?.presenting != true && !presentationRequested
                ) {
                    val token = rear?.token ?: return@collect
                    presentationRequested = true
                    Log.i(TAG, "Requesting rear presentation session")
                    WindowAreaController.getOrCreate().presentContentOnWindowArea(
                        token,
                        this@MainActivity,
                        androidx.core.content.ContextCompat.getMainExecutor(this@MainActivity),
                        presentationCallback,
                    )
                }
            }
        }
    }

    private val presentationCallback = object : WindowAreaPresentationSessionCallback {
        override fun onSessionStarted(presenter: WindowAreaSessionPresenter) {
            Log.i(TAG, "Rear presentation started")
            val m = machine ?: return
            m.presenting = true
            val owner = CoverOwner()
            val content = ComposeView(presenter.context).apply {
                layoutParams = matchParent()
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { CoverFace(m) }
            }
            // 外屏是**另一个窗口**，按键不会经过 Activity.dispatchKeyEvent ——
            // 所以音量键得在这个窗口自己的根 View 上接管。
            val root = CoverRoot(presenter.context, volumeKeys).apply {
                // 不给布局参数的话会被当成 wrap_content —— 尺寸 0，外屏全黑
                layoutParams = matchParent()
                // 外屏不在 Activity 里，找不到 ViewTree 上的宿主 —— 手动挂。
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                addView(content)
            }
            coverOwner = owner
            coverView = content
            presenter.setContentView(root)
        }

        override fun onSessionEnded(t: Throwable?) {
            Log.i(TAG, "Rear presentation ended", t)
            presentationRequested = false
            machine?.presenting = false
            coverView?.disposeComposition()
            coverView = null
            coverOwner = null
        }

        override fun onContainerVisibilityChanged(isVisible: Boolean) = Unit
    }

    /** 外屏窗口里所有 View 都铺满 —— 少一个都可能被当成 wrap_content。 */
    private fun matchParent() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    override fun onDestroy() {
        super.onDestroy()
        machine?.release()
    }
}

/**
 * 主窗口帧循环：驱动走带物理、卷带半径与轮毂转角。
 *
 * 放在 LaunchedEffect 里而不是状态机构造函数里，是因为
 * `withFrameNanos` 需要一个 MonotonicFrameClock —— composition 天然提供，普通协程作用域不提供。
 * 外屏呈现会话活跃时这里让位（帧驱动换到 CoverFace 的循环），避免双重积分。
 */
@Composable
private fun MachineTicker(machine: FoldMachine) {
    LaunchedEffect(machine) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L && !machine.presenting) {
                    machine.advance(((now - last) / 1_000_000f).coerceIn(0f, 64f))
                }
                last = now
            }
        }
    }
}

/**
 * 外屏上的机器正面：一份独立的 composition（自己的 TextMeasurer / 帧循环），
 * 内容与合盖竖屏时的 FoldedFace 完全一致 —— 磁带窗是主角，右缘竖排走带键，右上音量旋钮。
 *
 * 外屏**恒竖屏**：展开状态下后置屏窗口报的是横向坐标系（如 1972×1248），
 * 这时按外屏竖向比例（1248/1972）把画面信箱式居中，绝不横向拉伸。
 */
@Composable
private fun CoverFace(machine: FoldMachine) {
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current.density
    LaunchedEffect(machine) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last != 0L && machine.presenting) {
                    machine.advance(((now - last) / 1_000_000f).coerceIn(0f, 64f))
                }
                last = now
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0B0B0C))) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (w / h > COVER_RATIO) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(((h * COVER_RATIO) / density).dp)
                    .fillMaxHeight()
            ) {
                FoldedFace(machine, tm, Modifier.fillMaxSize())
            }
        } else {
            FoldedFace(machine, tm, Modifier.fillMaxSize())
        }
    }
}

/** 外屏物理比例：1248 × 1972（竖）。 */
private const val COVER_RATIO = 1248f / 1972f

/** 长按换带后的连跳间隔：一直按住就一直翻。 */
private const val VOLUME_SKIP_REPEAT_MS = 450L

/**
 * 音量键语义：**短按调一格音量，长按换上一首 / 下一首**（继续按住就连续跳）。
 *
 * 全部吃掉 ACTION_DOWN 是必须的：只要放一个给系统，系统就会在按住期间连续调音量 ——
 * 长按换带的同时音量会被一路拧到底。
 */
private class VolumeKeyHandler(private val machine: () -> FoldMachine?) {

    private var lastSkipAt = 0L
    private var longFired = false

    /** 返回 true = 这个事件被机器接管了，不要往下传。 */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        val m = machine() ?: return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()
                if (event.repeatCount == 0) {
                    lastSkipAt = 0L
                    longFired = false
                } else if (lastSkipAt == 0L || now - lastSkipAt >= VOLUME_SKIP_REPEAT_MS) {
                    // 能收到「重复」事件，就说明系统已经把它判成长按了（首个重复只在
                    // 长按超时之后才发出），所以这里不必再自己掐表判长按；时间只用来节流，
                    // 让按住不放变成按 SKIP_REPEAT_MS 的节奏连续翻带。
                    lastSkipAt = now
                    longFired = true
                    Log.i(TAG, "volume long press -> ${if (up) "next" else "prev"} tape")
                    m.stepTape(if (up) 1 else -1)
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                // 长按已经换过带了，抬手不再动音量。
                if (!longFired) {
                    Log.i(TAG, "volume tap -> ${if (up) "up" else "down"}")
                    m.nudgeVolume(up)
                }
                longFired = false
                return true
            }
        }
        return false
    }
}

/**
 * 外屏窗口的根 View。
 *
 * 外屏呈现窗口是**独立于 Activity 的另一个窗口**，按键由它自己接收 ——
 * 光在 Activity 里覆写 dispatchKeyEvent 是收不到外屏音量键的，所以这里再挂一道。
 */
private class CoverRoot(
    context: Context,
    private val keys: VolumeKeyHandler,
) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (keys.onKeyEvent(event)) true else super.dispatchKeyEvent(event)
}
