package com.workbuddy.duoman.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.util.Log
import com.workbuddy.duoman.R

/**
 * 音频引擎：本地音乐（MediaPlayer）+ 机械音效（SoundPool）。
 * 不申请任何权限 —— 音频 URI 由系统文件选择器（SAF）授予持久读权限。
 */
class AudioEngine(context: Context) {

    private val app: Context = context.applicationContext
    private var player: MediaPlayer? = null
    private var looper: MediaPlayer? = null

    private val pool: SoundPool
    private val sKey: Int
    private val sInsert: Int
    private val sEject: Int
    private val sWhir: Int
    private val sMotor: Int
    private var whirStream = 0
    private var motorStream = 0

    var durationMs: Int = 0
        private set
    var ready: Boolean = false
        private set
    var currentUri: String? = null
        private set

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

    /** 绑定一盘磁带的音频文件；返回是否可用。 */
    fun attach(uri: Uri?): Boolean {
        detach()
        if (uri == null) return false
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
            mp.setOnCompletionListener { /* 由状态机按位置判定到头 */ }
            mp.setOnErrorListener { _, _, _ -> true }
            player = mp
            durationMs = mp.duration.coerceAtLeast(0)
            ready = true
            currentUri = uri.toString()
            true
        } catch (e: Exception) {
            Log.w("DuoMan", "attach failed: ${e.message}")
            durationMs = 0
            ready = false
            currentUri = null
            false
        }
    }

    fun detach() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
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

    fun seekTo(ms: Int) {
        if (!ready) return
        try {
            player?.seekTo(ms.coerceIn(0, durationMs.coerceAtLeast(0)))
        } catch (_: Exception) {
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
