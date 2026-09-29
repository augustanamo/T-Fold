package com.augustana.tfold.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.augustana.tfold.R

/**
 * 音频引擎：本地音乐（MediaPlayer）+ 机械音效（SoundPool）。
 *
 * **一盘磁带 = 一串曲目**（同一张专辑 / 同一个目录）。引擎把这一串当成一条连续的时间轴：
 *  - [totalMs] 是整盘的带长，[albumPosMs] 是这条时间轴上的当前位置；
 *  - 一首放完自动接下一首（[open]），所以状态机只看"带走到哪了"，不必管曲目边界；
 *  - [seekTo] 收的是**整盘时间轴**上的位置，内部自己做"该在第几首、第几毫秒"的换算
 *    —— 快进/倒带/装带恢复都走它，状态机那边完全不用改。
 *
 * 权限：本机音乐库里来的 content://media/... 在持有 READ_MEDIA_AUDIO 时可直接读；
 * SAF 选来的文件另带持久读权限，两条路都不需要长期授权。
 */
class AudioEngine(context: Context) {

    private val app: Context = context.applicationContext
    private var player: MediaPlayer? = null
    private var looper: MediaPlayer? = null

    /** 这盘带的曲目顺序。 */
    private var uris: List<Uri> = emptyList()

    /** 每首的时长（毫秒）。给进来的提示值打底，真开播时用播放器的实测值覆盖。 */
    private var durMs: IntArray = IntArray(0)

    /** 现在在第几首（0 起）。 */
    var index: Int = 0
        private set

    private val pool: SoundPool
    private val sKey: Int
    private val sInsert: Int
    private val sEject: Int
    private val sWhir: Int
    private val sMotor: Int
    private var whirStream = 0
    private var motorStream = 0

    /** 当前这首的时长（单曲磁带时 = 整盘带长）。 */
    var durationMs: Int = 0
        private set
    var ready: Boolean = false
        private set
    var currentUri: String? = null
        private set

    /** 整盘带长 = 各曲之和（还没开播的曲目用提示值）。 */
    val totalMs: Int get() = durMs.sum().coerceAtLeast(0)

    /** 整盘时间轴上的当前位置 = 已放完的曲目之和 + 当前这首的播放位置。 */
    val albumPosMs: Int
        get() {
            if (!ready) return 0
            var base = 0
            for (i in 0 until index) base += durMs.getOrElse(i) { 0 }
            return base + positionMs()
        }

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        sKey = pool.load(app, R.raw.sfx_key, 1)
        sInsert = pool.load(app, R.raw.sfx_insert, 1)
        sEject = pool.load(app, R.raw.sfx_eject, 1)
        sWhir = pool.load(app, R.raw.sfx_whir, 1)
        sMotor = pool.load(app, R.raw.sfx_motor, 1)
    }

    /**
     * 绑定一盘磁带。[msHint] 是各曲时长的先验值（导入时用 MediaMetadataRetriever 量过），
     * 让整盘带长在初始化当刻就是准的 —— 走带速度、计数器读的都是它。
     */
    fun attach(list: List<Uri>, msHint: List<Int> = emptyList()): Boolean {
        detach()
        uris = list
        durMs = IntArray(list.size) { msHint.getOrElse(it) { 0 }.coerceAtLeast(0) }
        if (list.isEmpty()) return false
        return open(0, 0, play = false)
    }

    /** 单曲绑定（内置磁带、SAF 选曲走这条）。 */
    fun attach(uri: Uri?, msHint: Int = 0): Boolean =
        attach(listOfNotNull(uri), if (msHint > 0) listOf(msHint) else emptyList())

    /** 换到第 [i] 首并从 [offsetMs] 开始；[play] = 此刻机器应当在放。 */
    fun switchTo(i: Int, offsetMs: Int, play: Boolean): Boolean {
        if (i == index && player != null) {
            seekTo(offsetMs)
            if (play) play() else pause()
            return true
        }
        return open(i, offsetMs, play)
    }

    /** 开第 [i] 首。会把上一首彻底放掉（一首边界只发生一次，简单可靠优先）。 */
    private fun open(i: Int, offsetMs: Int, play: Boolean): Boolean {
        releasePlayer()
        val uri = uris.getOrNull(i) ?: run {
            ready = false
            durationMs = 0
            currentUri = null
            return false
        }
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(app, uri)
            mp.prepare()
            mp.isLooping = false
            mp.setOnErrorListener { _, _, _ -> true }
            if (offsetMs > 0) mp.seekTo(offsetMs)
            mp.setOnCompletionListener {
                // 一首放完自动接下一首（整盘走完就不再接）。
                // 用 post 而不是直接调：open() 会 release 掉**正在回调的这个** player，
                // 在它自己的 completion 回调里 release 属于踩边界，绕一帧最稳。
                val next = index + 1
                if (next < uris.size) {
                    Handler(Looper.getMainLooper()).post { open(next, 0, true) }
                }
            }
            player = mp
            index = i
            durationMs = mp.duration.coerceAtLeast(0)
            if (durationMs > 0) durMs[i] = durationMs      // 实测值覆盖提示值
            ready = true
            currentUri = uri.toString()
            if (play) mp.start()
            Log.i("TFold", "track ${i + 1}/${uris.size} @${offsetMs}ms play=$play dur=$durationMs")
            true
        } catch (e: Exception) {
            Log.w("TFold", "attach failed: ${e.message}")
            durationMs = 0
            ready = false
            currentUri = null
            false
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    fun detach() {
        releasePlayer()
        uris = emptyList()
        durMs = IntArray(0)
        index = 0
        ready = false
        durationMs = 0
        currentUri = null
    }

    fun play() {
        if (!ready) return
        try {
            player?.start()
        } catch (_: Exception) {
        }
    }

    fun pause() {
        if (!ready) return
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (_: Exception) {
        }
    }

    /** 定位（**整盘时间轴**上的毫秒）：换算成"第几首 + 这首里的第几毫秒"。 */
    fun seekTo(ms: Int) {
        if (!ready) return
        val target = ms.coerceIn(0, totalMs.coerceAtLeast(0))
        var base = 0
        for (i in uris.indices) {
            val d = durMs.getOrElse(i) { 0 }.coerceAtLeast(1)
            if (target < base + d || i == uris.lastIndex) {
                val off = (target - base).coerceIn(0, d)
                if (i == index && player != null) {
                    try {
                        player?.seekTo(off)
                    } catch (_: Exception) {
                    }
                } else {
                    open(i, off, play = false)
                }
                return
            }
            base += d
        }
    }

    fun positionMs(): Int = try {
        if (ready) player?.currentPosition ?: 0 else 0
    } catch (_: Exception) {
        0
    }

    /** 音乐音量 0..1（只调 MediaPlayer，不动系统音量与机械音效）。 */
    fun setVolume(v: Float) {
        val vv = v.coerceIn(0f, 1f)
        try {
            player?.setVolume(vv, vv)
        } catch (_: Exception) {
        }
    }

    // ---------- 机械音效 ----------

    fun key() = pool.play(sKey, 0.55f, 0.55f, 2, 0, 1f)

    fun insertClack() = pool.play(sInsert, 0.9f, 0.9f, 2, 0, 1f)

    fun ejectThunk() = pool.play(sEject, 0.9f, 0.9f, 2, 0, 1f)

    /** 卷带噪声：rate 随走带速度变化。 */
    fun whir(on: Boolean, rate: Float = 1f) {
        if (on) {
            if (whirStream == 0) {
                whirStream = pool.play(sWhir, 0.30f, 0.30f, 1, -1, rate.coerceIn(0.5f, 2f))
            } else {
                pool.setRate(whirStream, rate.coerceIn(0.5f, 2f))
            }
        } else if (whirStream != 0) {
            pool.stop(whirStream)
            whirStream = 0
        }
    }

    fun motor(on: Boolean) {
        if (on) {
            if (motorStream == 0) motorStream = pool.play(sMotor, 0.22f, 0.22f, 0, -1, 1f)
        } else if (motorStream != 0) {
            pool.stop(motorStream)
            motorStream = 0
        }
    }

    fun release() {
        whir(false)
        motor(false)
        detach()
        try {
            looper?.release()
        } catch (_: Exception) {
        }
        looper = null
        pool.release()
    }
}
