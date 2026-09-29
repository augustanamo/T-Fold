package com.workbuddy.duoman.machine

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.workbuddy.duoman.audio.AudioEngine
import com.workbuddy.duoman.model.Tape
import com.workbuddy.duoman.model.TapeLibrary
import com.workbuddy.duoman.ui.mix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt

enum class Transport { STOP, PLAY, PAUSE, FF, REW, REC }

/** 走带机构状态机的可调参数。 */
private const val R_MAX = 0.325f      // 满卷带盘半径（相对带盒宽度）
private const val R_MIN = 0.150f      // 空卷带盘半径
private const val BASE_OMEGA = 3.4f   // 满盘时的角速度 rad/s
private const val WIND_RATE = 18f     // 快进/快退倍速（相对走带）

/**
 * Duo-Man 状态机。
 *
 * 对外只暴露状态与动作，动画与物理跑在 composition 提供的作用域里：
 *  - opened : 内屏（横屏）= true 露出磁带库/卡座；外屏（竖屏）= false 是正面播放器
 *  - flyIn / flyOut : 磁带「落座进度」，0 = 在磁带库卡位，1 = 已在卡座
 *
 * 这台机器的开合就是设备本身的开合 —— 这里不做任何"假装折叠"的动画。
 */
class DuoMachine(
    context: Context,
    val audio: AudioEngine,
    /**
     * 所有动画与物理都跑在这个作用域里。
     *
     * **必须由 composition 提供**（`rememberCoroutineScope()`）：
     * 只有 composition 的 CoroutineContext 里带 MonotonicFrameClock。
     * 自己 new 一个 `Dispatchers.Main.immediate` 的作用域，会在第一次
     * `withFrameNanos` / `Animatable.animateTo` 时抛：
     *   IllegalStateException: A MonotonicFrameClock is not available in this CoroutineContext.
     */
    private val scope: CoroutineScope,
) {
    val tapes: List<Tape> = TapeLibrary
    private val app: Context = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("duoman", Context.MODE_PRIVATE)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val flyIn = Animatable(1f)
    val flyOut = Animatable(0f)

    /**
     * 盒盖开合进度：0 = 合着，1 = 向右开到底。
     *
     * 装带是「开盒 → 磁带飞出 → 落座」，退带是同一条链倒放（磁带飞回 → 落进盒里 → 合盖）。
     * 和 flyIn/flyOut 一样必须是 Animatable —— 它要在同一帧时钟里插值。
     */
    val box = Animatable(0f)

    /** 此刻正在开合的那张卡片（装/退带的主角）；null = 没有卡片在做开合动作。 */
    var boxAnimId by mutableStateOf<String?>(null)
        private set

    /** 盒盖开度，供 UI 直接读（走 loadedId / boxAnimId 两条路径见 AlbumsPane）。 */
    val boxOpen: Float get() = box.value

    var index by mutableStateOf(0)
        private set
    var loadedId by mutableStateOf<String?>(null)
        private set
    var transport by mutableStateOf(Transport.STOP)
        private set
    var tapePos by mutableStateOf(0f)
        private set
    var counter by mutableStateOf(0)
        private set
    var frame by mutableStateOf(0L)
        private set
    var hintText by mutableStateOf<String?>(null)
        private set
    var endOfTape by mutableStateOf(false)
        private set
    var pendingTapeId by mutableStateOf<String?>(null)
        private set

    /** 正在飞入的磁带 id（卡片 → 卡座） */
    var incoming by mutableStateOf<String?>(null)
        private set

    /** 正在飞出的磁带 id（卡座 → 卡片） */
    var outgoing by mutableStateOf<String?>(null)
        private set

    var assigned by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** 磁带 id → 盒面图（绑歌内嵌封面，抽出来缓存；没有封面图的盘回落到程序化构成）。 */
    var coverArt by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set

    /** 磁带库中「当前卡位」在根坐标系里的矩形，用于飞行起点。 */
    var cardRect by mutableStateOf<Rect?>(null)

    /** 卡座落座点在根坐标系里的矩形，用于飞行终点。 */
    var slotRect by mutableStateOf<Rect?>(null)

    /** 正在机械动作（装带/退带动画中），期间屏蔽重复输入。 */
    var busy by mutableStateOf(false)
        private set

    /**
     * 机器是否「打开」= 正在内屏（横屏）上，露出磁带库与卡座。
     *
     * 这是一台真折叠机：物理折上（外屏，竖屏）→ 正面播放器；
     * 物理展开（内屏，横屏）→ 选带 / 装带。由 UI 按窗口宽高比赋值，不在这层做动画。
     */
    var opened by mutableStateOf(false)

    /**
     * Jetpack WindowManager 报告的物理折叠态（FoldingFeature）：
     *  true  = 铰链完全展开（FLAT，内屏摊开）
     *  false = 半开 / 合上
     *  null  = 没有折叠信息（普通手机，或已在外屏 —— 外屏没有铰链特征）
     * 有它时它说了算，UI 只在 null 时才退回宽高比判断。
     */
    var foldState by mutableStateOf<Boolean?>(null)

    /** WindowArea 探测：设备是否报告了可呈现的「后背/外屏区域」。 */
    var windowAreaSupported by mutableStateOf(false)

    /** 外屏呈现会话（WindowArea）是否活跃。活动时主窗口帧循环让位给外屏帧循环。 */
    var presenting by mutableStateOf(false)

    /** 指示器当前位置 0=REW 1=PLAY 2=FF 3=EJECT 4=REC */
    var indicatorAt by mutableStateOf(1)
        private set

    /** 音乐音量 0..1，由正面的音量旋钮驱动。 */
    var volume by mutableStateOf(0.85f)
        private set

    // ---- 不外露的物理量，只驱动 Canvas 绘制（必须是 snapshot 状态，否则转动不触发重绘） ----
    var angleTop by mutableStateOf(0f)
        private set

    var angleBot by mutableStateOf(0f)
        private set

    private var virtualMs = 0f
    private var hintToken = 0
    private var flightJob: Job? = null

    init {
        val restored = mutableMapOf<String, String>()
        tapes.forEach { t -> prefs.getString("uri_${t.id}", null)?.let { restored[t.id] = it } }
        assigned = restored
        restored.forEach { (id, uri) -> loadCoverArt(id, uri) }

        // 搬屏（startActivity + finish）会重建 Activity —— 装带状态必须落盘恢复
        prefs.getString("loaded", null)?.let { id -> tapes.firstOrNull { it.id == id } }?.let { loadedId = it.id }
        virtualMs = prefs.getFloat("posMs", 0f)

        // 旋钮初始位置 = 系统媒体音量的当前位置，不能凭空定在 0.85
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max > 0) volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    /** 装带状态落盘：搬屏重建 Activity 后恢复「哪盘带在卡座里、走到哪」。 */
    private fun persistLoaded() {
        prefs.edit().putString("loaded", loadedId).putFloat("posMs", virtualMs).apply()
    }

    /** 从绑定的音频里抽内嵌封面图（IO 线程做，抽不到就安静放弃 —— 盒面用构成法兜底）。 */
    private fun loadCoverArt(tapeId: String, uriString: String) {
        scope.launch {
            val art = withContext(Dispatchers.IO) {
                runCatching {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(app, Uri.parse(uriString))
                    val bytes = mmr.embeddedPicture
                    runCatching { mmr.release() }
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }.getOrNull()
            }
            if (art != null) coverArt = coverArt + (tapeId to art)
        }
    }

    /**
     * 音量旋钮。
     *
     * 真机的电位器拧的是整机增益，不是某个播放器实例 —— 所以这里直接驱动
     * 系统媒体音量（没放磁带时也真实有效），同时同步给 MediaPlayer 做细调。
     */
    fun adjustVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        audio.setVolume(volume)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (volume * max).roundToInt().coerceIn(0, max),
            0,
        )
    }

    /**
     * 音量键短按一格。
     *
     * 一格 = 系统媒体音量的**一档**（1 / max），不是固定百分比 ——
     * 这样按键和系统音量条逐格对齐，拧旋钮与按音量键走的是同一个量。
     */
    fun nudgeVolume(up: Boolean) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        adjustVolume(volume + (if (up) 1f else -1f) / max)
    }

    /**
     * 换上一盘 / 下一盘带（长按音量键）。
     *
     * 以**卡座里那盘**为基准而不是当前高亮位 —— 合上盖子时看不到磁带库，
     * 用户心里的「当前」只有正在转的那盘。卡座空着时就装入相邻那盘。
     */
    fun stepTape(delta: Int) {
        if (tapes.isEmpty()) return
        val n = tapes.size
        val from = loadedId
            ?.let { id -> tapes.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: index
        insert(((from + delta) % n + n) % n)
    }

    // ------------------------------------------------------------------ 查询

    fun tapeById(id: String?): Tape? = tapes.firstOrNull { it.id == id }

    val loadedTape: Tape? get() = tapeById(loadedId)

    val currentTape: Tape get() = tapes[index.coerceIn(0, tapes.lastIndex)]

    fun hasAudio(id: String?): Boolean = id != null && assigned[id] != null

    fun durationMsOf(tape: Tape?): Float {
        if (audio.ready && audio.durationMs > 0) return audio.durationMs.toFloat()
        return ((tape?.minutes ?: 60) * 60_000).toFloat()
    }

    fun positionMs(): Int = (virtualMs).toInt()

    // ------------------------------------------------------------------ 交互

    fun focusIndex(i: Int) {
        index = i.coerceIn(0, tapes.lastIndex)
    }

    /** 选带并装入卡座。若卡座已有磁带 → 一进一出，即「换带」。 */
    fun insert(idx: Int, autoPlay: Boolean = false) {
        val target = tapes[idx.coerceIn(0, tapes.lastIndex)]
        index = idx.coerceIn(0, tapes.lastIndex)
        if (busy) return
        if (loadedId == target.id) {
            hint("这盘已经在卡座里了")
            return
        }
        audio.key()
        applyTransport(Transport.STOP)
        val prev = loadedId
        busy = true
        flightJob?.cancel()
        flightJob = scope.launch {
            if (prev != null) {
                // 换带 = 先退出旧带，再装入新带。
                // 两盘带共用同一条弧线，同时飞会完全重叠 —— 所以必须串行。
                // 旧带的飞行要先把 flyOut 拨到 1（= 在卡座里）再从 1 → 0，
                // 否则它起始值就是 0，等于原地不动、直接凭空消失。
                // 它的盒子本来就是开着的（带子在卡座里），所以先置 1，落座后再合盖。
                boxAnimId = prev
                box.snapTo(1f)
                loadedId = null
                outgoing = prev
                audio.detach()
                flyOut.snapTo(1f)
                flyOut.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
                audio.ejectThunk()
                outgoing = null
                flyOut.snapTo(0f)
                // 磁带落回盒里 → 盖合上
                box.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                boxAnimId = null
                delay(80)
            }
            // 新带：先把盒子打开（盖向右转开），磁带才飞出去 —— 顺序不能颠倒
            boxAnimId = target.id
            box.snapTo(0f)
            box.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
            incoming = target.id
            flyIn.snapTo(0f)
            flyIn.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
            audio.insertClack()

            loadedId = target.id
            incoming = null
            // 交接给「卡座里那盘 = 常开」的静态路径：盒子保持开着，不闪
            box.snapTo(1f)
            boxAnimId = null
            flyOut.snapTo(0f)
            outgoing = null
            tapePos = 0f
            virtualMs = 0f
            angleTop = 0f
            angleBot = 0f
            counter = 0
            endOfTape = false
            bindAudio(target.id)
            persistLoaded()
            busy = false
            hint(if (prev != null) "已换带 · ${target.title}" else "已落座 · 合上 Duo-Man 播放")

            if (autoPlay) {
                if (hasAudio(target.id)) {
                    resumePlay()
                    hint("正在播放 · ${target.title}")
                } else {
                    pendingTapeId = target.id
                    hint("空白磁带 · 请先为它选一首本机音乐")
                }
            }
        }
    }

    /**
     * 取出磁带。
     *
     * 内屏（打开）：把磁带从卡座飞回磁带库卡位。
     * 外屏（合上）：看不到磁带库，直接卸下 —— 下一次展开时它已在库里。
     */
    fun eject() {
        val cur = loadedId ?: run {
            hint("卡座是空的")
            return
        }
        audio.key()
        applyTransport(Transport.STOP)
        busy = true
        flightJob?.cancel()
        if (!opened) {
            loadedId = null
            audio.detach()
            audio.ejectThunk()
            tapePos = 0f
            virtualMs = 0f
            angleTop = 0f
            angleBot = 0f
            counter = 0
            busy = false
            persistLoaded()
            hint("已取出 · ${tapeById(cur)?.title ?: ""}")
            return
        }
        flightJob = scope.launch {
            audio.ejectThunk()
            // 退带 = 装带的倒放：带子飞回盒里，然后盖合上
            boxAnimId = cur
            box.snapTo(1f)
            outgoing = cur
            loadedId = null
            audio.detach()
            flyOut.snapTo(1f)
            flyOut.animateTo(0f, tween(680, easing = FastOutSlowInEasing))
            outgoing = null
            flyOut.snapTo(0f)
            box.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            boxAnimId = null
            tapePos = 0f
            virtualMs = 0f
            angleTop = 0f
            angleBot = 0f
            counter = 0
            busy = false
            persistLoaded()
            hint("已取出 · ${tapeById(cur)?.title ?: ""}")
        }
    }

    // ------------------------------------------------------------------ 走带

    fun pressPlay() {
        audio.key()
        if (opened) {
            hint("合上 Duo-Man 才能播放")
            return
        }
        val tape = loadedTape ?: return
        if (!hasAudio(tape.id)) {
            pendingTapeId = tape.id
            hint("空白磁带 · 请先为它选一首本机音乐")
            return
        }
        when (transport) {
            Transport.PLAY -> applyTransport(Transport.PAUSE)
            Transport.FF, Transport.REW -> resumePlay()
            else -> resumePlay()
        }
    }

    private fun resumePlay() {
        if (tapePos >= 0.9995f) {
            tapePos = 0f
            virtualMs = 0f
            audio.seekTo(0)
        }
        if (audio.ready) {
            audio.seekTo(virtualMs.toInt())
            audio.play()
        }
        endOfTape = false
        audio.motor(true)
        audio.whir(false)
        transport = Transport.PLAY
        indicatorAt = 1
    }

    fun pressFF() {
        audio.key()
        if (loadedId == null || opened) return
        if (!hasAudio(loadedId)) {
            pendingTapeId = loadedId
            hint("空白磁带 · 请先为它选一首本机音乐")
            return
        }
        if (transport == Transport.FF) {
            resumePlay()
            return
        }
        if (transport == Transport.PLAY) virtualMs = audio.positionMs().toFloat()
        applyTransport(Transport.FF)
        indicatorAt = 2
    }

    fun pressREW() {
        audio.key()
        if (loadedId == null || opened) return
        if (!hasAudio(loadedId)) {
            pendingTapeId = loadedId
            hint("空白磁带 · 请先为它选一首本机音乐")
            return
        }
        if (transport == Transport.REW) {
            resumePlay()
            return
        }
        if (transport == Transport.PLAY) virtualMs = audio.positionMs().toFloat()
        applyTransport(Transport.REW)
        indicatorAt = 0
    }

    fun pressEject() {
        if (busy) return
        if (loadedId == null) {
            audio.key()
            hint("卡座是空的")
            return
        }
        applyTransport(Transport.STOP)
        indicatorAt = 3
        eject()
    }

    /**
     * 录音键。
     *
     * 纯模拟里没有麦克风输入，所以 REC 表达的是「抹音头压下、带头以 1× 前进」：
     * 走带照常、输出静音、轮毂照常转 —— 也就是"在录"这件事看起来的样子。
     * 再按一次 = 停录。
     *
     * 与 PLAY / FF / REW 不同，REC 不要求这盘带已经装过曲目 —— 空白带本来就是用来录的。
     */
    fun pressRec() {
        audio.key()
        if (opened) {
            hint("合上 Duo-Man 才能录音")
            return
        }
        val tape = loadedTape ?: run {
            hint("卡座是空的 · 先选一盘磁带")
            return
        }
        if (transport == Transport.REC) {
            applyTransport(Transport.STOP)
            indicatorAt = 4
            return
        }
        if (transport == Transport.PLAY) virtualMs = audio.positionMs().toFloat()
        applyTransport(Transport.REC)
        indicatorAt = 4
        hint("录音中 · ${tape.title}")
    }

    private fun applyTransport(t: Transport) {
        if (t == transport) return
        transport = t
        when (t) {
            Transport.PLAY -> {
                audio.motor(true)
                audio.whir(false)
            }
            // 录音：带头照常走、电机照常转，但没有声音出来（抹音头压着）
            Transport.REC -> {
                audio.pause()
                audio.motor(true)
                audio.whir(false)
            }
            Transport.FF, Transport.REW -> {
                audio.pause()
                audio.motor(false)
                audio.whir(true, if (t == Transport.FF) 1.7f else 1.4f)
            }
            Transport.PAUSE, Transport.STOP -> {
                audio.pause()
                audio.motor(false)
                audio.whir(false)
            }
        }
    }

    // ------------------------------------------------------------------ 音频绑定

    fun requestAudio(tapeId: String) {
        pendingTapeId = tapeId
    }

    fun consumeAudioRequest() {
        pendingTapeId = null
    }

    fun assignAudio(tapeId: String, uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        prefs.edit().putString("uri_$tapeId", uri.toString()).apply()
        assigned = assigned + (tapeId to uri.toString())
        pendingTapeId = null
        loadCoverArt(tapeId, uri.toString())
        if (loadedId == tapeId) bindAudio(tapeId)
        hint("已装曲目 · ${tapeById(tapeId)?.title ?: ""}")
    }

    private fun bindAudio(tapeId: String) {
        val s = assigned[tapeId]
        if (s == null) {
            audio.detach()
            return
        }
        audio.attach(Uri.parse(s))
        virtualMs = 0f
    }

    // ------------------------------------------------------------------ 提示

    fun hint(msg: String?) {
        hintToken++
        val token = hintToken
        hintText = msg
        if (msg != null) {
            scope.launch {
                delay(3000)
                if (token == hintToken) hintText = null
            }
        }
    }

    fun release() {
        applyTransport(Transport.STOP)
        audio.release()
        // 作用域归 composition 所有，随 Activity 一起销毁 —— 这里绝不能 cancel。
    }

    /** 退到后台：走带停住，但磁带留在卡座里 —— 和真机一样。 */
    fun onBackground() {
        when (transport) {
            Transport.PLAY -> applyTransport(Transport.PAUSE)
            Transport.REC -> applyTransport(Transport.PAUSE)
            Transport.FF, Transport.REW -> applyTransport(Transport.STOP)
            else -> Unit
        }
        persistLoaded()
    }

    // ------------------------------------------------------------------ 物理

    /** 帧循环驱动：由 composition 每帧调用一次，dt 上限 64ms（防止后台回来时跳帧）。 */
    fun advance(dtMs: Float) {
        val tape = loadedTape
        val moving = tape != null && (
            transport == Transport.PLAY || transport == Transport.FF ||
                transport == Transport.REW || transport == Transport.REC
            )

        if (moving) {
            val dur = durationMsOf(tape)
            var pos = tapePos
            when (transport) {
                Transport.PLAY ->
                    if (audio.ready && audio.durationMs > 0) {
                        virtualMs = audio.positionMs().toFloat()
                        pos = virtualMs / dur
                    } else {
                        virtualMs += dtMs
                        pos = virtualMs / dur
                    }

                Transport.FF -> {
                    virtualMs += dtMs * WIND_RATE
                    pos = virtualMs / dur
                }

                Transport.REW -> {
                    virtualMs -= dtMs * WIND_RATE
                    pos = virtualMs / dur
                }

                // 录音：1× 前进，但位置只由虚拟时间推 —— 没有音频可同步
                Transport.REC -> {
                    virtualMs += dtMs
                    pos = virtualMs / dur
                }

                else -> Unit
            }

            if (pos >= 1f) {
                tapePos = 1f
                if (!endOfTape) {
                    endOfTape = true
                    applyTransport(Transport.STOP)
                    hint("A 面到头了 · 按 ⏏ 取出翻面")
                }
            } else if (pos <= 0f) {
                tapePos = 0f
                if (transport == Transport.REW) {
                    applyTransport(Transport.STOP)
                    hint("已回到带头")
                }
            } else {
                tapePos = pos
            }

            // 恒线速度走带 → 角速度 ∝ 1/半径（收带盘越绕越慢，供带盘越放越快）
            val rT = mix(R_MAX, R_MIN, tapePos)
            val rB = mix(R_MIN, R_MAX, tapePos)
            val k = when (transport) {
                Transport.PLAY -> 1f
                Transport.REC -> 1f
                Transport.FF, Transport.REW -> 6f
                else -> 0f
            }
            if (k > 0f) {
                val dt = dtMs / 1000f
                val dir = if (transport == Transport.REW) -1f else 1f
                angleTop += dir * BASE_OMEGA * (R_MAX / rT) * k * dt
                angleBot += dir * BASE_OMEGA * (R_MAX / rB) * k * dt
                val revs = angleBot / (2f * PI.toFloat())
                counter = (revs / 2.2f).coerceAtLeast(0f).toInt() % 1000
            }
        }
        frame++
    }

    companion object {
        const val RMIN = R_MIN
        const val RMAX = R_MAX
    }
}
