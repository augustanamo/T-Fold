package com.augustana.tfold.ui

import androidx.compose.ui.geometry.Rect
import com.augustana.tfold.ui.parts.CASSETTE_H_RATIO

/** 单栏设计宽度（单位 dp，随屏幕等比缩放）。 */
const val DESIGN_W = 197f

/** 栏内内容留白（设计单位）。 */
const val PANE_PAD = 19f

/**
 * 双屏设备的几何 —— **满屏**。
 *
 * 不做「产品摄影棚」那种居中摆件：整块屏幕就是这台机器。
 * 左右两半各占一半宽度、占满高度，中缝就是铰链。
 *
 * 这样做的代价与收益：
 *  · 收益：任何屏幕比例下都满屏，且每一栏都拿到最大可用面积（文字、磁带都不会缩到看不清）；
 *  · 代价：合上的那一面是「半宽 × 全高」，与整屏并非同比例，
 *    所以闭合动画用的是**各向异性**缩放（x: 0.5, y: 1.0）—— 这正是竖向铰链的物理真相，
 *    而不是把整屏等比缩小 0.5 倍。
 */
class Geo(val W: Float, val H: Float) {
    val leafW: Float = W * 0.5f
    val leafH: Float = H

    /** 合上时，整屏内容要横向压到 1/2 —— 半宽面板就是这么来的。 */
    val faceScaleX: Float = leafW / W

    val hingeX: Float = leafW
    val leftLeafRect: Rect = Rect(0f, 0f, leafW, leafH)
    val rightLeafRect: Rect = Rect(leafW, 0f, W, H)
    val deviceRect: Rect = Rect(0f, 0f, W, H)

    /** 一栏内的设计单位。 */
    fun unitOf(leafWidth: Float): Float = leafWidth / DESIGN_W
}

/** 一栏的自适应布局：宽度定标，高度弹性。 */
class PaneBox(val realW: Float, val realH: Float) {
    /** 1 设计单位 = u 像素。 */
    val u: Float = realW / DESIGN_W

    /** 画布宽度（设计单位）—— 恒等于设计宽度。 */
    val pw: Float = DESIGN_W

    /** 画布高度（设计单位）—— 弹性，等于这一栏的真实高度换算过来。 */
    val ph: Float = realH / u

    /** 卡位/磁带尺寸：宽高都被画布夹住，永不溢出。 */
    val tapeW: Float = run {
        val byWidth = (pw - PANE_PAD * 2f) * 0.86f
        val byHeight = (ph * 0.46f)
        minOf(byWidth, byHeight / CASSETTE_H_RATIO)
    }
    val tapeH: Float = tapeW * CASSETTE_H_RATIO

    /** 左栏原点（根坐标）。 */
    val originX: Float = 0f
    val originY: Float = 0f
}
