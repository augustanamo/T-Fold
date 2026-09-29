package com.workbuddy.duoman.machine

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.workbuddy.duoman.audio.AudioEngine
import com.workbuddy.duoman.model.CoverStyle
import com.workbuddy.duoman.model.Tape
import com.workbuddy.duoman.model.TapeLibrary
import com.workbuddy.duoman.model.UserTapePalettes
import com.workbuddy.duoman.model.UserTapeStyles
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

private const val TAG = "DuoMan"

/** 读本机音乐库要的权限：13 起是细分的 READ_MEDIA_AUDIO，12 及以下退回读外部存储。 */
private val AUDIO_PERMISSION: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_AUDIO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

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
    /**
     * 磁带库 = 4 盘内置（`TapeLibrary`）+ 用户用右上角「＋」新加的。
     * 新加的一律**追加在末尾**，顺序就是加入顺序。
     */
    var tapes by mutableStateOf(TapeLibrary)
        private set

    /** 请求左栏翻到 [index]：自增一次即触发。左栏自己翻页不会碰它，避免来回打架。 */
    var focusTick by mutableStateOf(0)
        private set
    private val app: Context = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("duoman", Context.MODE_PRIVATE)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 一张专辑/一个目录收成一盘带的登记表 —— 导入是幂等的，不会重复吃进同一张专辑。 */
    private var importedAlbums: MutableSet<String> =
        prefs.getStringSet("imported", emptySet())?.toMutableSet() ?: mutableSetOf()

    /** 触感马达：没有就静默跳过（模拟器 / 平板）。 */
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

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

    /**
     * 删除（长按卡位）的抽走进度：0 = 还在卡位上，1 = 已抽走。
     *
     * 和 flyIn/box 一样是 Animatable —— 要在同一帧时钟里插值。卡位那格按它
     * 下沉 + 缩小 + 淡出，像把带子从库架上抽下来；走完才真正从 [tapes] 里摘掉。
     */
    val ejectAnim = Animatable(0f)

    /** 此刻正在被抽走的那盘；null = 没有。 */
    var removingId by mutableStateOf<String?>(null)
        private set

    /** 内置磁带里被删掉的（用户新加的那几盘直接不出现在 user_tapes 里，不用记）。 */
    private var removed: MutableSet<String> = mutableSetOf()

    /** 选曲请求占用的 id（进程被杀重启后靠它把 pick 结果对上号）；不驱动 UI。 */
    private var armedTapeId: String? = null

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

    /** 「＋」被按下但还没拿到读取音乐库的权限 —— 自增一次，UI 收到就去要权限。 */
    var permissionAsk by mutableStateOf(0)
        private set

    /** 正在扫描本机音乐库（导入专辑）—— UI 可以据此显示一个转动的指示。 */
    var importing by mutableStateOf(false)
        private set

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
        // 先把用户新加的磁带接回库尾，再统一恢复各盘的曲目绑定。
        // 被长按删掉的盘（含内置那 4 盘）记在 removed 名单里 —— 每次启动都要把它们滤掉。
        removed = prefs.getStringSet("removed", emptySet())?.toMutableSet() ?: mutableSetOf()
        tapes = (TapeLibrary + restoreUserTapes()).filterNot { it.id in removed }

        // 进程被系统杀掉重启后，选曲请求还在半路上（pick 结果由系统补投回重建的 Activity）：
        // 把占好的 id 从盘上捡回来，回调里才认得这是哪盘带。
        armedTapeId = prefs.getString("pendingTape", null)

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

    // ------------------------------------------------------------------ 触感

    /**
     * 震动。`timings` 是毫秒交替的「停 / 振」表（第一项必须是停），`repeat` = 从此下标起循环（-1 = 只响一遍）。
     * 删除这种不可撤销的动作必须有手感：起手一下轻的（长按咬住了）、
     * 计时中一直嗡嗡的（还没松手就还来得及）、落定一下沉的（带子真没了）。
     */
    private fun buzz(timings: LongArray, repeat: Int = -1) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(
                VibrationEffect.createWaveform(
                    timings,
                    IntArray(timings.size) { VibrationEffect.DEFAULT_AMPLITUDE },
                    repeat,
                )
            )
        }
    }

    /** 手指按住卡位那一刻的「嗒」。 */
    fun buzzHold() = buzz(longArrayOf(0, 12))

    /**
     * 删除计时**一直响着**：短促的连续脉冲（20ms 振 / 42ms 停）循环到松手或落定。
     * 首项 0 = 立刻开始；`repeat = 1` 表示从第 1 段（那 20ms）起循环，前面那项 0 只在第一轮当延迟。
     */
    fun buzzDelete() = buzz(longArrayOf(0, 20, 42), repeat = 1)

    /** 松手/拖走 —— 撤销那段持续震动（`cancel()` 只作用于本应用）。 */
    fun buzzStopDelete() {
        runCatching { vibrator?.cancel() }
    }

    /** 删除落定的两下：沉、短、有间隔 —— 和起手那下听得出来是两件事。 */
    fun buzzRemove() {
        buzzStopDelete()
        buzz(longArrayOf(0, 28, 70, 42))
    }

    /**
     * 从绑定的音频里抽内嵌封面图（IO 线程做，抽不到就安静放弃 —— 盒面用构成法兜底）。
     * 传进来的可以是**整盘曲目串**：取第一首的封面当这盘带的盒面。
     */
    private fun loadCoverArt(tapeId: String, uriString: String) {
        val first = uriString.split('\n').firstOrNull()?.trim().orEmpty()
        val uriPart = first.substringBeforeLast('|', first)
        if (uriPart.isBlank()) return
        scope.launch {
            val art = withContext(Dispatchers.IO) {
                runCatching {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(app, Uri.parse(uriPart))
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
        if (audio.ready && audio.totalMs > 0) return audio.totalMs.toFloat()
        return ((tape?.minutes ?: 60) * 60_000).toFloat()
    }

    fun positionMs(): Int = (virtualMs).toInt()

    // ------------------------------------------------------------------ 交互

    fun focusIndex(i: Int) {
        index = i.coerceIn(0, tapes.lastIndex)
    }

    /** 让左栏跟着翻到第 [i] 盘（新加磁带之后主动调用）；左栏自己翻页用的是 [focusIndex]，不会触发回跳。 */
    private fun jumpTo(i: Int) {
        index = i.coerceIn(0, tapes.lastIndex)
        focusTick++
    }

    /**
     * 这盘带能不能删。长按的 UI 先问一声再起动画 —— 免得"进度环都填满了才被告知删不掉"。
     * 闸门同 [removeTape]。
     */
    fun canRemove(id: String): Boolean =
        !busy && id != loadedId && tapes.size > 1 && tapes.any { it.id == id }

    /**
     * 长按卡位 = 把这盘带从库架上抽走（含内置那 4 盘，删了就不再出现）。
     *
     * 三道闸门，都是"宁可不动也不做错"：
     *  · 有动画在飞 → 不理（[busy]）；
     *  · 这盘正在卡座里转 → 不理，提示先取出（删了它，卡座就成了悬空的幽灵）；
     *  · 库里只剩这一盘 → 不理（`tapes[index.coerceIn(0, lastIndex)]` 在空库上会炸）。
     *
     * 通过之后先播「抽走」动画（[ejectAnim] 0→1，卡位下沉缩小淡出），走完才摘。
     * 摘完 [jumpTo] 让左栏把页号与进度一起对齐到新库 —— 左栏那边同时也在做
     * 「后一盘从侧面顶上来」的滑行，两者终态一致，所以接得上、不跳。
     */
    fun removeTape(id: String) {
        val tape = tapes.firstOrNull { it.id == id } ?: return
        if (busy) return
        if (loadedId == id) {
            hint("这盘正在卡座里 · 先取出再删")
            return
        }
        if (tapes.size <= 1) {
            hint("库里至少要留一盘")
            return
        }
        removingId = id
        scope.launch {
            ejectAnim.snapTo(0f)
            ejectAnim.animateTo(1f, tween(280, easing = FastOutSlowInEasing))

            val i = tapes.indexOfFirst { t -> t.id == id }
            if (i < 0) {                       // 中途被别处删掉了，收尾就好
                removingId = null
                ejectAnim.snapTo(0f)
                return@launch
            }
            val next = tapes.filterNot { t -> t.id == id }
            tapes = next

            // 落盘：内置那 4 盘进 removed 名单；用户带靠重写 user_tapes 自然消失
            if (TapeLibrary.any { t -> t.id == id }) {
                removed += id
                prefs.edit().putStringSet("removed", removed).apply()
            }
            prefs.edit()
                .remove("uri_$id")
                .putString("user_tapes", serializeUserTapes(next))
                .apply()
            assigned = assigned - id
            coverArt = coverArt - id

            // 焦点跟着挪：删的那盘在当前之前 → 前移一格；就是当前那盘 → 停在原处（露出后一盘）
            val keep = (if (index > i) index - 1 else index).coerceIn(0, next.lastIndex)
            jumpTo(keep)

            removingId = null
            ejectAnim.snapTo(0f)
            buzzRemove()
            hint("已移除 · ${tape.title}")
        }
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
                    arm(target.id)
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
            arm(tape.id)
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
            loadedId?.let { arm(it) }
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
            loadedId?.let { arm(it) }
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
        arm(tapeId)
    }

    /**
     * 记下「这次选曲是给谁选的」，并**同时落盘**。
     *
     * 落盘这件事不是保险起见 —— 是必须的：系统文件选择器会把 DuoMan 压到后台，
     * 而它（在一台内存吃紧的 Fold 上）随时可能被回收。Activity 重建后系统仍会把
     * pick 结果补投回来，但**内存里的 `pendingTapeId` 已经没了**，
     * 于是回调只能拿到 `id = null`、这首歌被默默丢掉。
     * 现在重启后从盘上把 id 捡回来，选曲照样落进库里。
     */
    private fun arm(tapeId: String) {
        pendingTapeId = tapeId
        armedTapeId = tapeId
        prefs.edit().putString("pendingTape", tapeId).apply()
    }

    fun consumeAudioRequest() {
        pendingTapeId = null
        armedTapeId = null
        prefs.edit().remove("pendingTape").apply()
    }

    /**
     * pick 回来时问「这盘带装到哪儿」：当前进程里的 [pendingTapeId] 优先，
     * 进程被杀重启过就用落盘的 [armedTapeId] 兜底，两者都没有（理论上不会）
     * 才现配一个新 id。取完即清，不留悬空请求。
     */
    fun claimPickedTapeId(): String {
        val id = pendingTapeId ?: armedTapeId ?: nextUserTapeId()
        consumeAudioRequest()
        return id
    }

    /** 下一盘用户带的内码：`u01`、`u02`… 序号落盘，删带不会回收号段（免得和旧记录撞名）。 */
    private fun nextUserTapeId(): String {
        val seq = prefs.getInt("userSeq", 0) + 1
        prefs.edit().putInt("userSeq", seq).apply()
        return "u%02d".format(seq)
    }

    /**
     * 右上角「＋」= **新增一盘磁带**（不是给现有的换曲）。
     *
     * 这里先把 id 占好（`u01`、`u02`…）再开 SAF 选曲 —— 选完那首歌才知道标题/时长，
     * 所以磁带实体是在 [assignAudio] 里读元数据之后才追加进库的。
     * 用户取消选曲就丢掉这个 id，库不变。
     */
    fun requestNewTape() {
        if (busy) return
        arm(nextUserTapeId())
        Log.i(TAG, "requestNewTape -> $pendingTapeId (库 ${tapes.size} 盘)")
    }

    fun assignAudio(tapeId: String, uri: Uri) {
        Log.i(TAG, "assignAudio id=$tapeId new=${tapes.none { it.id == tapeId }} uri=$uri")
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        consumeAudioRequest()

        if (tapes.none { it.id == tapeId }) {
            addNewTape(tapeId, uri)
            return
        }

        // 已有磁带：换/装它的曲目
        prefs.edit().putString("uri_$tapeId", uri.toString()).apply()
        assigned = assigned + (tapeId to uri.toString())
        loadCoverArt(tapeId, uri.toString())
        if (loadedId == tapeId) bindAudio(tapeId)
        hint("已装曲目 · ${tapeById(tapeId)?.title ?: ""}")
    }

    /** 从这首音频里读标题/艺术家/时长，造一盘新磁带追加到库尾并翻过去。 */
    private fun addNewTape(tapeId: String, uri: Uri) {
        scope.launch {
            val meta = withContext(Dispatchers.IO) { readMeta(uri) }
            val k = (tapes.size - TapeLibrary.size).coerceAtLeast(0)
            val tape = Tape(
                id = tapeId,
                title = meta.title,
                artist = meta.artist,
                minutes = meta.minutes,
                typeLabel = "TYPE I · ${meta.minutes} MIN",
                style = UserTapeStyles[k % UserTapeStyles.size],
                art = UserTapePalettes[k % UserTapePalettes.size],
            )
            tapes = tapes + tape
            assigned = assigned + (tapeId to uri.toString())
            prefs.edit()
                .putString("uri_$tapeId", uri.toString())
                .putString("user_tapes", serializeUserTapes(tapes))
                .apply()
            loadCoverArt(tapeId, uri.toString())
            jumpTo(tapes.lastIndex)
            hint("新磁带 · ${meta.title}")
        }
    }

    private data class Meta(val title: String, val artist: String, val minutes: Int)

    /** 读音频元数据。读不到就退回文件名 + 60 分钟，绝不因为一首歌没打标签就崩。 */
    private fun readMeta(uri: Uri): Meta = runCatching {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(app, uri)
        val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名磁带"
        val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: "未知艺术家"
        val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        runCatching { mmr.release() }
        Meta(title, artist, (ms / 60_000L).toInt().coerceAtLeast(1))
    }.getOrDefault(Meta("未命名磁带", "未知艺术家", 60))

    // ---- 用户新加的磁带落盘（内置那 4 盘是常量，不存） ----

    private fun serializeUserTapes(all: List<Tape>): String = all
        .filter { t -> TapeLibrary.none { it.id == t.id } }
        .joinToString("\u0002") { t ->
            listOf(
                t.id, t.title, t.artist, t.minutes.toString(), t.typeLabel, t.style.name,
                t.art.joinToString(",") { "%08X".format(it.toArgb()) },
            ).joinToString("\u0001")
        }

    private fun restoreUserTapes(): List<Tape> {
        val raw = prefs.getString("user_tapes", null) ?: return emptyList()
        return raw.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0001")
            if (f.size < 7) return@mapNotNull null
            runCatching {
                Tape(
                    id = f[0], title = f[1], artist = f[2], minutes = f[3].toInt(),
                    typeLabel = f[4], style = CoverStyle.valueOf(f[5]),
                    art = f[6].split(",").map { Color(it.toLong(16)) },
                )
            }.getOrNull()
        }
    }

    private fun bindAudio(tapeId: String) {
        val tracks = tracksOf(tapeId)
        if (tracks.isEmpty()) {
            audio.detach()
            return
        }
        audio.attach(tracks.map { it.first }, tracks.map { it.second })
        virtualMs = 0f
    }

    // ------------------------------------------------------------------ 曲目 / 专辑

    /**
     * 把落盘的那串曲目拆开：一行一首，`uri|毫秒`。
     *
     * 认两种写法：老数据里是**光秃秃一条 uri**（SAF 选来的单曲），
     * 现在导入专辑写的是 `uri|时长`（时长用来算整盘带长，导入那刻已经量好了）。
     * content:// 里不会出现 `|`，所以按最后一个 `|` 切是安全的。
     */
    private fun tracksOf(tapeId: String?): List<Pair<Uri, Int>> {
        val s = assigned[tapeId] ?: return emptyList()
        return s.split('\n').mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val cut = t.lastIndexOf('|')
            val ms = if (cut > 0) t.substring(cut + 1).toIntOrNull() else null
            val uriPart = if (ms != null) t.substring(0, cut) else t
            runCatching { Uri.parse(uriPart) to (ms ?: 0) }.getOrNull()
        }
    }

    /** 这盘带里有几首（0 = 空白带）。 */
    fun trackCountOf(tapeId: String?): Int = tracksOf(tapeId).size

    /** 正在放第几首（1 起，0 = 没有在放）。 */
    val trackNo: Int get() = if (audio.ready && trackCountOf(loadedId) > 0) audio.index + 1 else 0

    private data class Scanned(val key: String, val title: String, val artist: String, val tracks: List<Pair<Uri, Int>>) {
        val totalMs: Int get() = tracks.sumOf { it.second }
    }

    /**
     * 「＋」被按下：有权限就直接扫，没有就先让 UI 去要。
     *
     * 没扫到任何音乐（或者权限被拒）时**退回老的 SAF 选一首单曲** ——
     * 全自动是主路，但手动这条路不能断。
     */
    fun requestImport() {
        if (busy || importing) return
        if (!hasAudioPermission()) {
            permissionAsk++
            Log.i(TAG, "requestImport -> 等权限 $AUDIO_PERMISSION")
            return
        }
        importLocalMusic()
    }

    private fun hasAudioPermission(): Boolean =
        app.checkSelfPermission(AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 扫本机音乐库，**按专辑分组**（同一张专辑 = 一盘磁带），追加到库尾并翻到第一盘新带。
     *
     * 分组用 `ALBUM_ID`；没有专辑信息（或者整张专辑只有一首）的，退回按**所在目录**分组
     * —— 这正是用户在文件管理器里看到的样子。导入过的专辑记在盘上，再扫不会重复。
     */
    fun importLocalMusic() {
        if (importing) return
        importing = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { scanAlbums() }.getOrDefault(emptyList()) }
            val fresh = found.filter { it.key !in importedAlbums }
            if (fresh.isEmpty()) {
                importing = false
                if (found.isEmpty()) {
                    hint("没找到本机音乐 · 手动选一首")
                    requestNewTape()          // 退回 SAF 单曲
                } else {
                    hint("本机音乐都已经在库架上了")
                }
                return@launch
            }

            val added = mutableListOf<Tape>()
            fresh.forEach { s ->
                val id = nextUserTapeId()
                val k = (tapes.size - TapeLibrary.size + added.size).coerceAtLeast(0)
                val minutes = (s.totalMs / 60_000).coerceAtLeast(1)
                val tape = Tape(
                    id = id, title = s.title, artist = s.artist, minutes = minutes,
                    typeLabel = "TYPE I · $minutes MIN",
                    style = UserTapeStyles[k % UserTapeStyles.size],
                    art = UserTapePalettes[k % UserTapePalettes.size],
                )
                added += tape
                importedAlbums += s.key
                val blob = s.tracks.joinToString("\n") { (u, ms) -> "$u|$ms" }
                assigned = assigned + (id to blob)
                prefs.edit().putString("uri_$id", blob).apply()
                loadCoverArt(id, blob)
            }

            tapes = tapes + added
            prefs.edit()
                .putString("user_tapes", serializeUserTapes(tapes))
                .putStringSet("imported", importedAlbums)
                .apply()
            val firstNew = tapes.size - added.size
            jumpTo(firstNew)
            importing = false
            val songs = fresh.sumOf { it.tracks.size }
            Log.i(TAG, "importLocalMusic: ${added.size} 盘 / $songs 首")
            hint("已上架 ${added.size} 盘 · 共 $songs 首")
        }
    }

    /** 读 MediaStore，按专辑/目录分组。返回顺序按专辑名，稳定的库架顺序。 */
    private fun scanAlbums(): List<Scanned> {
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.IS_MUSIC,
        )
        // 一首一首收，先按专辑归堆；专辑信息不全的按目录归堆
        data class Row(val albumKey: String, val dirKey: String, val album: String, val artist: String, val id: Long, val ms: Int)
        val rows = mutableListOf<Row>()
        app.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media._ID} ASC",
        )?.use { c: Cursor ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val albumId = c.getLong(iAlbumId)
                val album = c.getString(iAlbum)?.takeIf { it.isNotBlank() }
                val title = c.getString(iTitle)
                val dir = c.getString(iData)?.substringBeforeLast('/')?.substringAfterLast('/')
                    ?: (title ?: "本机音乐")
                val ms = c.getInt(iDur).coerceAtLeast(0)
                if (ms <= 0) continue                  // 读不到时长的直接跳过，免得算不准带长
                rows += Row(
                    albumKey = if (album != null && albumId > 0) "a$albumId" else "d$dir",
                    dirKey = "d$dir",
                    album = album ?: dir,
                    artist = c.getString(iArtist)?.takeIf { !it.isNullOrBlank() && it != "<unknown>" } ?: "未知艺术家",
                    id = c.getLong(iId),
                    ms = ms,
                )
            }
        }
        return rows.groupBy { it.albumKey }.map { (key, list) ->
            Scanned(
                key = key,
                title = list.first().album,
                artist = list.first().artist,
                tracks = list.map {
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.id) to it.ms
                },
            )
        }.sortedBy { it.title }
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
                    if (audio.ready && audio.totalMs > 0) {
                        // 一盘 = 一串曲目：引擎给的就是**整盘时间轴**上的位置
                        // （单曲磁带时它等于播放器位置，行为和以前一模一样）
                        virtualMs = audio.albumPosMs.toFloat()
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
