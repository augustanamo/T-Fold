package com.augustana.tfold.model

import androidx.compose.ui.graphics.Color

/** 封面构图 —— 全部程序化绘制，不使用任何外部素材。 */
enum class CoverStyle { HORIZON, GRID, STRIPES, ORBIT }

/**
 * 一盘磁带。
 * @param minutes 标称时长，决定虚拟走带速度（未绑定音频文件时）。
 * @param art     封面构图的 2~3 个色
 */
data class Tape(
    val id: String,
    val title: String,
    val artist: String,
    val minutes: Int,
    val typeLabel: String,
    val style: CoverStyle,
    val art: List<Color>,
)

/**
 * 出厂那盘带 —— **一盘空白磁带**，也就是全部的内置内容。
 *
 * 曾经这里有 4 盘编好的「假磁带」（黄昏公路 / 午夜电台 …）：装上应用就摆一排不存在的专辑，
 * 放不出任何声音，用户也没法把它们的壳打开换掉。现在换成**这一盘真的空白带**：
 *  · 库架永远不会是空的（空库那一堆 `tapes.lastIndex` 的下标假设也就不会咬人）；
 *  · 它是**能录的** —— 合上盖子按 REC 就是真录音（见 `FoldMachine.pressRec`）；
 *  · 它**删不掉**（长按不抽走），长按改的是它的盒面图 —— 空白带的封面本来就该由用户决定。
 * 别的磁带都从「＋」进来：扫本机音乐（一张专辑一盘）或手动选一首。
 */
const val BLANK_TAPE_ID = "b01"

val BlankTape = Tape(
    id = BLANK_TAPE_ID,
    title = "空白磁带",
    artist = "TFOLD",
    minutes = 60,
    typeLabel = "TYPE I · BLANK",
    style = CoverStyle.GRID,
    // 纸白 + 石墨 + 灰 —— 彩度为 0，所以它的清壳是**无色**的（见 shellTintOf），
    // 真正"空白"的那一盘：没封面、没声音，等着用户按下那个红点。
    art = listOf(Color(0xFFFAF9F5), Color(0xFF2B2B28), Color(0xFFC9C7C0)),
)

/** 内置（出厂即有、删不掉）的磁带。用户加的都在 `user_tapes` 里，和这一列分开。 */
val BuiltinTapes: List<Tape> = listOf(BlankTape)

/** 这盘是不是出厂那盘空白带（删不掉、长按换封面）。 */
fun isBuiltinTape(id: String?): Boolean = id != null && BuiltinTapes.any { it.id == id }

/**
 * 用户用「＋」**新加**的磁带。
 *
 * 盒面用的是那首音频内嵌的专辑图（`FoldMachine.coverArt`），
 * 只有抽不到图时才回落到下面这些程序化构成 —— 按加入顺序轮流取，看起来不至于千篇一律。
 */
val UserTapeStyles = listOf(CoverStyle.ORBIT, CoverStyle.HORIZON, CoverStyle.STRIPES, CoverStyle.GRID)

val UserTapePalettes = listOf(
    listOf(Color(0xFFEEEBE4), Color(0xFF2A2A28), Color(0xFFB9B7B0)),
    listOf(Color(0xFFE7DFD2), Color(0xFF2B2B28), Color(0xFFD8CFC0)),
    listOf(Color(0xFF2E3A3C), Color(0xFFD9D7CE), Color(0xFF8A9A9C)),
    listOf(Color(0xFFC9C4B8), Color(0xFFCE4A1E), Color(0xFF8A8880)),
)
