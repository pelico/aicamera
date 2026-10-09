package com.pelico.aicamera.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.pelico.aicamera.contract.LmPoint
import com.pelico.aicamera.contract.PersonResult
import com.pelico.aicamera.contract.ShotSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * L1 姿态层：MediaPipe Pose Landmarker（lite，CPU）。
 *
 * 三条硬约束（都是踩过才知道的）：
 * 1. **帧只旋转一次。** 上游 `FrameConverter` 已经把 YUV 转到屏幕正立方向，
 *    这里用 IMAGE 模式同步跑，不再传 rotationDegrees，否则会转两次、姿态全反。
 * 2. **模型文件不能压缩。** `.task` 必须在 `build.gradle.kts` 的 noCompress 里声明。
 * 3. **降级要静默。** 模型缺失或初始化失败时 available=false，
 *    上层切成「只有场景 + 站位框 + 参考线」的确定性模式，而不是崩掉。
 */
class PoseEngine(context: Context) {

    private var landmarker: PoseLandmarker? = null

    val available: Boolean
        get() = landmarker != null

    init {
        landmarker = runCatching { create(context) }.getOrNull()
    }

    private fun create(context: Context): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_FILE)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(MAX_POSES)
            .setMinPoseDetectionConfidence(CONF)
            .setMinPosePresenceConfidence(CONF)
            .setMinTrackingConfidence(CONF)
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }

    /**
     * @param upright 已旋转到屏幕方向的 ARGB_8888 Bitmap，调用期间不可回收
     * @return 画面里**占比最大的一个人**；没检到人返回 null
     */
    fun detect(upright: Bitmap): PersonResult? {
        val engine = landmarker ?: return null
        val image = BitmapImageBuilder(upright).build()
        val result = engine.detect(image)

        val poses = result.landmarks()
        if (poses.isEmpty()) return null

        // 多人时只跟踪占比最大的一个，并在 UI 上说明这件事
        val picked = poses
            .filter { it.size >= 33 }
            .maxByOrNull { span(it) } ?: return null

        val points = picked.map { LmPoint(it.x(), it.y(), 1f) }
        val box = bounds(points)

        var outOfFrame = 0
        for (idx in Lm.CORE) {
            val pt = points[idx]
            if (pt.x < 0f || pt.x > 1f || pt.y < 0f || pt.y > 1f) outOfFrame++
        }
        val visibleRatio = 1f - outOfFrame.toFloat() / Lm.CORE.size

        val footY = max(
            max(points[Lm.ANKLE_L].y, points[Lm.ANKLE_R].y),
            max(points[Lm.KNEE_L].y, points[Lm.KNEE_R].y)
        ).coerceIn(0f, 1f)
        val topY = min(
            points[Lm.NOSE].y,
            min(points[Lm.SHOULDER_L].y, points[Lm.SHOULDER_R].y)
        )
        val heightRatio = (footY - topY).coerceIn(0f, 2f)

        val centerX = ((box.left + box.right) / 2f).coerceIn(0f, 1f)
        val centerY = ((box.top + box.bottom) / 2f).coerceIn(0f, 1f)

        val cropped = box.left < 0.01f || box.right > 0.99f || box.top < 0.01f || box.bottom > 0.99f

        val clamped = RectF(
            box.left.coerceIn(0f, 1f),
            box.top.coerceIn(0f, 1f),
            box.right.coerceIn(0f, 1f),
            box.bottom.coerceIn(0f, 1f)
        )

        return PersonResult(
            landmarks = points,
            bbox = clamped,
            shotSize = ShotSize.of(heightRatio),
            facing = facingOf(points),
            centerX = centerX,
            centerY = centerY,
            footY = footY,
            heightRatio = heightRatio,
            visibleRatio = visibleRatio,
            personCount = poses.size,
            cropped = cropped
        )
    }

    fun anglesOf(person: PersonResult): Map<AngleKey, Float> = PoseAngles.compute(person.landmarks)

    fun close() {
        runCatching { landmarker?.close() }
        landmarker = null
    }

    private fun span(points: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for (p in points) {
            minX = min(minX, p.x())
            maxX = max(maxX, p.x())
            minY = min(minY, p.y())
            maxY = max(maxY, p.y())
        }
        return (maxX - minX) * (maxY - minY)
    }

    private fun bounds(points: List<LmPoint>): RectF {
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for (p in points) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }
        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * 朝向只区分 FRONT / SIDE_45 / SIDE_90。
     * 判据是「肩宽相对躯干长度」：正对镜头时肩膀展开，全侧时肩膀几乎叠成一条线。
     * 背对镜头在本方法里和正面对镜头**长得一样**，所以不判 BACK —— 这不是保守，是测不出来。
     */
    private fun facingOf(p: List<LmPoint>): BodyFacing {
        val shoulderW = dist(p[Lm.SHOULDER_L], p[Lm.SHOULDER_R])
        val hipMid = (p[Lm.HIP_L].x + p[Lm.HIP_R].x) / 2f to (p[Lm.HIP_L].y + p[Lm.HIP_R].y) / 2f
        val shoulderMid = (p[Lm.SHOULDER_L].x + p[Lm.SHOULDER_R].x) / 2f to
            (p[Lm.SHOULDER_L].y + p[Lm.SHOULDER_R].y) / 2f
        val torso = sqrt(
            sqr(shoulderMid.first - hipMid.first) + sqr(shoulderMid.second - hipMid.second)
        )
        if (torso < 0.05f) return BodyFacing.FRONT
        val openness = shoulderW / torso
        return when {
            openness > 0.72f -> BodyFacing.FRONT
            openness > 0.50f -> BodyFacing.SIDE_45
            else -> BodyFacing.SIDE_90
        }
    }

    private fun dist(a: LmPoint, b: LmPoint): Float = sqrt(sqr(a.x - b.x) + sqr(a.y - b.y))
    private fun sqr(v: Float): Float = v * v

    companion object {
        const val MODEL_FILE = "pose_landmarker_lite.task"
        private const val MAX_POSES = 2
        private const val CONF = 0.5f
    }
}
