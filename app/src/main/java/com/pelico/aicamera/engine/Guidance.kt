package com.pelico.aicamera.engine

import kotlin.math.abs

/** 归一化构图框（相对当前画面，0..1） */
data class CompositionBox(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float
) {
    val left: Float get() = cx - w / 2f
    val top: Float get() = cy - h / 2f
    val right: Float get() = cx + w / 2f
    val bottom: Float get() = cy + h / 2f

    fun lerpTo(other: CompositionBox, t: Float) = CompositionBox(
        cx + (other.cx - cx) * t,
        cy + (other.cy - cy) * t,
        w + (other.w - w) * t,
        h + (other.h - h) * t
    )
}

enum class GuideDirection { LEFT, RIGHT, UP, DOWN, ZOOM_IN, READY }

data class Guidance(
    val directions: List<GuideDirection>,
    val text: String,
    val aligned: Boolean,
    val dx: Float,
    val dy: Float,
    val zoomFactor: Float
)

/**
 * 把模型给出的裁剪框翻译成人能执行的动作。
 *
 * 语义：模型输出的是「当前画面里最值得保留的那块区域」。
 * 想让取景框直接变成这块区域，用户需要：
 *   - 把镜头朝这块区域的方向平移（框中心偏左就往左移）
 *   - 再拉近，让这块区域填满画面
 */
object GuidanceCalculator {

    private const val CENTER_TOLERANCE = 0.06f
    private const val ZOOM_TOLERANCE = 0.08f

    fun compute(box: CompositionBox): Guidance {
        val dx = box.cx - 0.5f
        val dy = box.cy - 0.5f
        val zoomFactor = (1f / box.w.coerceIn(0.2f, 1f))

        val directions = mutableListOf<GuideDirection>()
        val hints = mutableListOf<String>()

        if (dx < -CENTER_TOLERANCE) {
            directions += GuideDirection.LEFT
            hints += "向左移"
        } else if (dx > CENTER_TOLERANCE) {
            directions += GuideDirection.RIGHT
            hints += "向右移"
        }

        if (dy < -CENTER_TOLERANCE) {
            directions += GuideDirection.UP
            hints += "向上移"
        } else if (dy > CENTER_TOLERANCE) {
            directions += GuideDirection.DOWN
            hints += "向下移"
        }

        val needsZoomIn = box.w < 1f - ZOOM_TOLERANCE && box.h < 1f - ZOOM_TOLERANCE
        if (needsZoomIn) {
            directions += GuideDirection.ZOOM_IN
            hints += "拉近 %.1f×".format(zoomFactor)
        }

        val aligned = directions.isEmpty()
        if (aligned) directions += GuideDirection.READY

        val text = when {
            aligned -> "构图就绪，可以按下快门"
            needsZoomIn && hints.size == 1 -> "画面留白偏多，${hints[0]}"
            else -> hints.joinToString("，")
        }

        return Guidance(
            directions = directions,
            text = text,
            aligned = aligned,
            dx = dx,
            dy = dy,
            zoomFactor = zoomFactor
        )
    }

    /** 框与画面中心的偏差距离，用于画引导线长度 */
    fun offsetMagnitude(box: CompositionBox): Float =
        kotlin.math.sqrt((box.cx - 0.5f) * (box.cx - 0.5f) + (box.cy - 0.5f) * (box.cy - 0.5f))

    fun isMeaningfulOffset(v: Float) = abs(v) > CENTER_TOLERANCE
}
