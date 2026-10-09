package com.pelico.aicamera.engine

import com.pelico.aicamera.contract.LmPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 从 MediaPipe 的 33 个关键点算出**可测量的关节特征**。
 *
 * 全部用两点之间的几何关系算，单位统一为「度」或「相对肩/髋宽的百分比」。
 * 左/右指的是**画面**的左右（`_L` = 画面左侧），不是被拍者的左右 —— 用户看的是屏幕，
 * 说「画面左边那只手」不会搞反。
 *
 * 刻意不做的：
 * - 机位高度（不可测量）
 * - 正脸 / 后脑勺的区分（2D 骨架投影无法区分）
 * - 表情、视线是否看镜头（33 点不足以可靠判断）
 */
enum class AngleKey(val zh: String) {
    ARM_L_ABDUCT("画面左臂抬起"),
    ARM_R_ABDUCT("画面右臂抬起"),
    ELBOW_L("画面左肘"),
    ELBOW_R("画面右肘"),
    KNEE_L("画面左膝"),
    KNEE_R("画面右膝"),
    TORSO_LEAN("上身倾斜"),
    LEG_SPREAD("两脚张开"),
    HEAD_TURN("脸的转向");

    /** 左右互换时对应的另一项；TORSO_LEAN / HEAD_TURN / LEG_SPREAD 自对称 */
    fun mirrored(): AngleKey = when (this) {
        ARM_L_ABDUCT -> ARM_R_ABDUCT
        ARM_R_ABDUCT -> ARM_L_ABDUCT
        ELBOW_L -> ELBOW_R
        ELBOW_R -> ELBOW_L
        KNEE_L -> KNEE_R
        KNEE_R -> KNEE_L
        else -> this
    }

    /** 镜像时数值需要取反的项（倾斜与转头有方向性，张开和弯折没有） */
    val flipsOnMirror: Boolean
        get() = this == TORSO_LEAN || this == HEAD_TURN

    companion object {
        fun parse(name: String): AngleKey? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

// MediaPipe Pose 的关键点索引
internal object Lm {
    const val NOSE = 0
    const val SHOULDER_L = 11
    const val SHOULDER_R = 12
    const val ELBOW_L = 13
    const val ELBOW_R = 14
    const val WRIST_L = 15
    const val WRIST_R = 16
    const val HIP_L = 23
    const val HIP_R = 24
    const val KNEE_L = 25
    const val KNEE_R = 26
    const val ANKLE_L = 27
    const val ANKLE_R = 28

    /** 判断「人有没有完全进画面」时看的核心点 */
    val CORE = intArrayOf(NOSE, SHOULDER_L, SHOULDER_R, HIP_L, HIP_R, KNEE_L, KNEE_R, ANKLE_L, ANKLE_R)
}

object PoseAngles {

    fun compute(p: List<LmPoint>): Map<AngleKey, Float> {
        if (p.size < 33) return emptyMap()
        val out = HashMap<AngleKey, Float>(9)

        out[AngleKey.ARM_L_ABDUCT] = included(p[Lm.HIP_L], p[Lm.SHOULDER_L], p[Lm.ELBOW_L])
        out[AngleKey.ARM_R_ABDUCT] = included(p[Lm.HIP_R], p[Lm.SHOULDER_R], p[Lm.ELBOW_R])
        out[AngleKey.ELBOW_L] = included(p[Lm.SHOULDER_L], p[Lm.ELBOW_L], p[Lm.WRIST_L])
        out[AngleKey.ELBOW_R] = included(p[Lm.SHOULDER_R], p[Lm.ELBOW_R], p[Lm.WRIST_R])
        out[AngleKey.KNEE_L] = included(p[Lm.HIP_L], p[Lm.KNEE_L], p[Lm.ANKLE_L])
        out[AngleKey.KNEE_R] = included(p[Lm.HIP_R], p[Lm.KNEE_R], p[Lm.ANKLE_R])

        val shoulderMid = mid(p[Lm.SHOULDER_L], p[Lm.SHOULDER_R])
        val hipMid = mid(p[Lm.HIP_L], p[Lm.HIP_R])
        val shoulderW = dist(p[Lm.SHOULDER_L], p[Lm.SHOULDER_R])
        val hipW = dist(p[Lm.HIP_L], p[Lm.HIP_R])

        // 上身倾斜：躯干方向与「垂直向下」的夹角，正 = 上身向画面右侧倾
        val torsoDx = shoulderMid.first - hipMid.first
        val torsoDy = shoulderMid.second - hipMid.second
        // atan2(Float, Float) 返回 Float，而 Math.toDegrees 只收 Double —— 必须显式转换
        out[AngleKey.TORSO_LEAN] = Math.toDegrees(atan2(torsoDx, torsoDy).toDouble()).toFloat()

        out[AngleKey.LEG_SPREAD] = ratio(abs(p[Lm.ANKLE_L].x - p[Lm.ANKLE_R].x), hipW)
        out[AngleKey.HEAD_TURN] = ratio(p[Lm.NOSE].x - shoulderMid.first, shoulderW) * 2f

        return out
    }

    /** 三点夹角，顶点是中间那个点：180° = 完全伸直，越小越弯 */
    private fun included(a: LmPoint, vertex: LmPoint, b: LmPoint): Float {
        val v1x = a.x - vertex.x
        val v1y = a.y - vertex.y
        val v2x = b.x - vertex.x
        val v2y = b.y - vertex.y
        val n1 = sqrt(v1x * v1x + v1y * v1y)
        val n2 = sqrt(v2x * v2x + v2y * v2y)
        if (n1 < 1e-4f || n2 < 1e-4f) return 180f
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
    }

    private fun ratio(a: Float, ref: Float): Float =
        if (ref < 1e-4f) 0f else a / ref * 100f

    private fun dist(a: LmPoint, b: LmPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun mid(a: LmPoint, b: LmPoint): Pair<Float, Float> =
        (a.x + b.x) / 2f to (a.y + b.y) / 2f
}

/** 把角度偏差翻译成一句人话。delta = 实际值 − 目标值 */
fun AngleKey.coach(delta: Float): String = when (this) {
    AngleKey.ARM_L_ABDUCT -> if (delta > 0) "画面左边的手臂放低一点" else "画面左边的手臂再抬高一点"
    AngleKey.ARM_R_ABDUCT -> if (delta > 0) "画面右边的手臂放低一点" else "画面右边的手臂再抬高一点"
    AngleKey.ELBOW_L -> if (delta > 0) "画面左边的手肘再弯一点" else "画面左边的手臂伸直一点"
    AngleKey.ELBOW_R -> if (delta > 0) "画面右边的手肘再弯一点" else "画面右边的手臂伸直一点"
    AngleKey.KNEE_L -> if (delta > 0) "画面左边的腿再弯一点" else "画面左边的腿伸直一点"
    AngleKey.KNEE_R -> if (delta > 0) "画面右边的腿再弯一点" else "画面右边的腿伸直一点"
    AngleKey.TORSO_LEAN -> if (delta > 0) "上身向画面左边回一点" else "上身向画面右边倾一点"
    AngleKey.LEG_SPREAD -> if (delta > 0) "两脚收拢一点" else "两脚再分开一点"
    AngleKey.HEAD_TURN -> if (delta > 0) "脸再转向画面左边一点" else "脸再转向画面右边一点"
}
