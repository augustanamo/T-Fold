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

val TapeLibrary: List<Tape> = listOf(
    Tape(
        id = "t01", title = "黄昏公路", artist = "陈默", minutes = 60,
        typeLabel = "TYPE I · 60 MIN", style = CoverStyle.HORIZON,
        art = listOf(Color(0xFFE7DFD2), Color(0xFF2B2B28), Color(0xFFD8CFC0)),
    ),
    Tape(
        id = "t02", title = "午夜电台", artist = "Kaito Mori", minutes = 90,
        typeLabel = "TYPE II · 90 MIN", style = CoverStyle.GRID,
        art = listOf(Color(0xFF2E3A3C), Color(0xFFD9D7CE), Color(0xFF8A9A9C)),
    ),
    Tape(
        id = "t03", title = "雨季霓虹", artist = "Mira Lin", minutes = 60,
        typeLabel = "TYPE I · 60 MIN", style = CoverStyle.STRIPES,
        art = listOf(Color(0xFFC9C4B8), Color(0xFFCE4A1E), Color(0xFF8A8880)),
    ),
    Tape(
        id = "t04", title = "Summer Tape", artist = "The Pocket Tones", minutes = 46,
        typeLabel = "TYPE I · 46 MIN", style = CoverStyle.ORBIT,
        art = listOf(Color(0xFFEEEBE4), Color(0xFF2A2A28), Color(0xFFB9B7B0)),
    ),
)

/**
 * 用户用「＋」**新加**的磁带。
 *
 * 盒面用的是那首音频内嵌的专辑图（`DuoMachine.coverArt`），
 * 只有抽不到图时才回落到下面这些程序化构成 —— 按加入顺序轮流取，看起来不至于千篇一律。
 */
val UserTapeStyles = listOf(CoverStyle.ORBIT, CoverStyle.HORIZON, CoverStyle.STRIPES, CoverStyle.GRID)

val UserTapePalettes = listOf(
    listOf(Color(0xFFEEEBE4), Color(0xFF2A2A28), Color(0xFFB9B7B0)),
    listOf(Color(0xFFE7DFD2), Color(0xFF2B2B28), Color(0xFFD8CFC0)),
    listOf(Color(0xFF2E3A3C), Color(0xFFD9D7CE), Color(0xFF8A9A9C)),
    listOf(Color(0xFFC9C4B8), Color(0xFFCE4A1E), Color(0xFF8A8880)),
)
