package com.workbuddy.duoman.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌 —— 迪特·拉姆斯语汇。
 *
 * 硬规则：
 *  1. 颜色即信息：机身与按键永远中性，赤色只出现在「当前功能 / 待动作」两处。
 *  2. 圆角只用 3 / 6 / 10 三档，线宽只用 0.5 与 1。
 *  3. 磁带窗口是全屏唯一的深色块。
 */
object C {
    // 中性体
    val Porcelain = Color(0xFFF1F0EC)      // 机身瓷白
    val PorcelainDeep = Color(0xFFE6E5E0)  // 机身侧面
    val Stage = Color(0xFFDFDEDA)          // 摄影棚背景
    val Paper = Color(0xFFFAF9F6)          // 内屏纸白
    val Screen = Color(0xFFF4F3EF)         // 展开态左屏
    val Hair = Color(0xFFD5D3CD)           // 分割细线
    val HairSoft = Color(0xFFDCDAD4)
    val HairDeep = Color(0xFFB9B7B0)
    val Steel = Color(0xFFA8A9A5)          // 钢灰丝印
    val SteelDeep = Color(0xFF8A8882)
    val Smoke = Color(0xFF6E7075)          // 次级文字
    val SmokeSoft = Color(0xFF8A8880)

    // 石墨体
    val Graphite = Color(0xFF2A2A2A)
    val Ink = Color(0xFF16181A)
    val KeyBody = Color(0xFF2E2E2E)
    val KeyEdge = Color(0xFF4A4A4A)
    val Recess = Color(0xFF1E1E1E)         // 磁带窗口凹槽
    val DeckFloor = Color(0xFF171717)
    val DeckPanel = Color(0xFF232323)
    val DeckLine = Color(0xFF3A3A3A)
    val Ivory = Color(0xFFEDEBE4)

    // Braun 拟物：浅色按键、黑旋钮、通风栅
    val KeyLight = Color(0xFFE7E6E1)       // 浅色按键体（SK 按钮排）
    val KeyLightTop = Color(0xFFF6F5F1)    // 按键受光面
    val KeyLightEdge = Color(0xFFC9C7C0)   // 按键底缘背光
    val Knob = Color(0xFF26262A)           // 黑旋钮
    val KnobEdge = Color(0xFF4A4A50)
    val KnobMark = Color(0xFFEDEBE4)       // 旋钮定位刻线
    val Vent = Color(0xFF17171A)           // 通风栅槽

    // 唯一功能色 —— Braun 安全橙（FM 旋钮 / Lectron 那一颗）
    val Signal = Color(0xFFE8562A)

    // 内屏展开态（复刻 viditb 概念片：左钢蓝磁带库 / 右黑卡座门，颜色直接采样自视频帧）
    val LibBlue = Color(0xFF2C709A)      // 磁带库底（采样 44,112,154）
    val LibBlueDeep = Color(0xFF1F5B7E)  // 库底暗角（采样 31,91,126）
    val LibInk = Color(0xFFF2F0EA)       // 蓝底上的丝印（＋钮、指示轨）
    val DoorBlack = Color(0xFF121212)    // 卡座门板（采样 18,18,18）
    val DoorEdge = Color(0xFF0A0A0A)     // 门板外沿
    val DoorWeave = Color(0x0FFFFFFF)    // 门板织纹
    val Screw = Color(0xFF838383)        // 门板螺丝（采样 131,131,131）

    // 磁带本体
    val Label = Color(0xFFEFEDE6)
    val LabelLine = Color(0xFFC9C7C0)
    val Hub = Color(0xFFCFCDC6)
    val HubDeep = Color(0xFFB4B2AB)
    val Oxide = Color(0xFF3A2F26)
    val OxideRing = Color(0xFF4A3D31)
    val OxideEdge = Color(0xFF241C16)
    val TapePath = Color(0xFF8A6B4E)

    val Shade = Color(0x1A000000)
    val ShadeSoft = Color(0x0D000000)
}

object D {
    val hair = 0.5.dp
    val line = 1.dp
    val r3 = 3.dp
    val r6 = 6.dp
    val r10 = 10.dp

    /** 展开态左屏满载设计宽度（与 [DESIGN_W] 同源，所有左屏尺寸以此为基准等比缩放）。 */
    val PaneDesignWidth = DESIGN_W
}

fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

fun ramp(x: Float, from: Float, to: Float): Float = ((x - from) / (to - from)).coerceIn(0f, 1f)
