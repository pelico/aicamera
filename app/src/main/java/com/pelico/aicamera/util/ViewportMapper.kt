package com.pelico.aicamera.util

import androidx.compose.ui.geometry.Rect

/**
 * 把「画面归一化坐标」映射到 PreviewView 上真实可见的那块矩形。
 *
 * 为什么必须有这一层：ImageAnalysis 是 4:3 的分析帧，PreviewView 是全屏且按 FIT_CENTER 缩放，
 * 二者的坐标系根本不是一个。直接用全屏尺寸画叠加层，在高瘦屏上目标框会整体偏到画面外 ——
 * 这是 v1 就可能存在的隐患，也是接姿态检测最容易出隐蔽 bug 的地方。
 *
 * @param srcAspect 画面显示方向上的宽高比（竖屏 4:3 内容 = 3f / 4f）
 */
fun contentRect(viewW: Float, viewH: Float, srcAspect: Float): Rect {
    if (viewW <= 0f || viewH <= 0f || srcAspect <= 0f) return Rect(0f, 0f, 0f, 0f)
    return if (viewW / viewH > srcAspect) {
        // 屏幕相对更宽：内容按高度撑满，左右留下黑边
        val w = viewH * srcAspect
        Rect((viewW - w) / 2f, 0f, (viewW - w) / 2f + w, viewH)
    } else {
        val h = viewW / srcAspect
        Rect(0f, (viewH - h) / 2f, viewW, (viewH - h) / 2f + h)
    }
}

/** 把归一化 0..1 的点投到 [rect] 内 */
fun Rect.project(x: Float, y: Float): androidx.compose.ui.geometry.Offset =
    androidx.compose.ui.geometry.Offset(left + x * width, top + y * height)
