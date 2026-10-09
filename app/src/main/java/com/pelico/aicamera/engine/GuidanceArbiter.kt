package com.pelico.aicamera.engine

import com.pelico.aicamera.contract.Guidance
import com.pelico.aicamera.contract.GuidanceSource
import com.pelico.aicamera.contract.PersonResult
import com.pelico.aicamera.contract.Severity
import kotlin.math.abs

/**
 * L4 决策层：把所有「可测量的偏差」收敛成**一条**指令。
 *
 * 上一版的失败点不是识别不准，是输出太吵：三个姿势卡片并列，
 * 用户一眼看完等于一条都没看。这里强制一次只出一条，按优先级挑最该改的。
 *
 * 优先级顺序（数字越小越先说）：
 *  10 没检到人 → 20 人被挡/没进全 → 30 水平 → 40 景别 → 50 站位 → 60 姿势角度
 *  → 75 多人提示 → 95 降级说明 → 100 可以拍了
 *
 * 刻意不给的建议：机位高度、 expresses 表情、眼神——这些都测不出来，给就是编的。
 */
object GuidanceArbiter {

    data class Input(
        val poseEnabled: Boolean,
        val person: PersonResult?,
        val template: PoseTemplate?,
        val spec: PoseSpec?,
        val tiltDeg: Float,
        val hasTarget: Boolean
    )

    fun decide(input: Input): Guidance =
        candidates(input).minByOrNull { it.priority } ?: ready()

    private fun candidates(i: Input): List<Guidance> {
        val out = ArrayList<Guidance>(8)

        if (!i.poseEnabled) {
            out += Guidance(
                source = GuidanceSource.FALLBACK,
                severity = Severity.HINT,
                priority = 95,
                text = "当前设备未启用姿态层",
                detail = "仍可使用场景推荐 + 参考线 + 站位框，这部分不依赖姿态检测",
                progress = 0.5f,
                key = "fallback_pose_off"
            )
            return out
        }

        val p = i.person
        if (p == null) {
            out += Guidance(
                source = GuidanceSource.NO_PERSON,
                severity = Severity.BLOCKER,
                priority = 10,
                text = "画面里没检测到人",
                detail = "把人放进画面；光线过暗、完全背身、衣物过于宽松时都可能漏检",
                progress = 0f,
                key = "no_person"
            )
            return out
        }

        if (p.visibleRatio < 0.75f || p.cropped) {
            out += Guidance(
                source = GuidanceSource.OCCLUDED,
                severity = Severity.BLOCKER,
                priority = 20,
                text = if (p.cropped) "人被画面边缘切到了，退后一点让人进全" else "人被挡住了一部分，换个角度或往前站",
                detail = "关键点在画面内的比例 %.0f%%".format(p.visibleRatio * 100),
                progress = p.visibleRatio,
                key = "occluded"
            )
        }

        val tilt = abs(i.tiltDeg)
        if (tilt > TILT_TOL) {
            out += Guidance(
                source = GuidanceSource.LEVEL,
                severity = if (tilt > TILT_WARN) Severity.WARN else Severity.HINT,
                priority = 30,
                text = "端平手机：现在歪了 %.1f°".format(tilt),
                detail = if (i.tiltDeg > 0) "把画面左边抬高一点" else "把画面右边抬高一点",
                progress = (1f - tilt / 10f).coerceIn(0f, 1f),
                key = "level"
            )
        }

        val targetRatio = i.spec?.bboxHRatio ?: i.template?.placement?.height
        if (targetRatio != null) {
            val gap = p.heightRatio - targetRatio
            if (abs(gap) > RATIO_TOL) {
                val want = com.pelico.aicamera.contract.ShotSize.of(targetRatio)
                out += Guidance(
                    source = GuidanceSource.SHOT_SIZE,
                    severity = if (abs(gap) > RATIO_WARN) Severity.WARN else Severity.HINT,
                    priority = 40,
                    text = if (gap < 0) "走近一点（目标：${want.zh}）" else "退远一点（目标：${want.zh}）",
                    detail = "人占画面高度 %.0f%%，目标 %.0f%%".format(p.heightRatio * 100, targetRatio * 100),
                    progress = (1f - abs(gap) / 0.3f).coerceIn(0f, 1f),
                    key = "shot_size"
                )
            }
        }

        val targetCx = i.spec?.targetCx ?: i.template?.placement?.cx
        if (targetCx != null) {
            val dx = cxDelta(p.centerX, targetCx, i.spec?.mirrored ?: false)
            if (abs(dx) > CX_TOL) {
                out += Guidance(
                    source = GuidanceSource.POSITION,
                    severity = Severity.HINT,
                    priority = 50,
                    text = if (dx > 0) "人往画面左边挪一点" else "人往画面右边挪一点",
                    detail = "目标让重心落在三分线附近",
                    progress = (1f - abs(dx) / 0.25f).coerceIn(0f, 1f),
                    key = "position_x"
                )
            }
        }

        val targetFootY = i.spec?.targetFootY ?: i.template?.placement?.footY
        if (targetFootY != null) {
            val dy = p.footY - targetFootY
            if (abs(dy) > FOOT_TOL) {
                out += Guidance(
                    source = GuidanceSource.POSITION,
                    severity = Severity.HINT,
                    priority = 52,
                    text = if (dy > 0) "手机往上抬一点，脚下留太多空白了" else "手机往下压一点，脚要留出一点边",
                    detail = "当前脚线 %.0f%%，目标 %.0f%%".format(p.footY * 100, targetFootY * 100),
                    progress = (1f - abs(dy) / 0.25f).coerceIn(0f, 1f),
                    key = "position_y"
                )
            }
        }

        val spec = i.spec
        if (spec != null && spec.angles.isNotEmpty()) {
            PoseSpecMatcher.worst(p, spec)?.let { diff ->
                out += Guidance(
                    source = GuidanceSource.POSE_ANGLE,
                    severity = Severity.HINT,
                    priority = 60,
                    text = diff.key.coach(diff.delta),
                    detail = "现在 %.0f，目标 %.0f（±%.0f）".format(diff.actual, diff.target, spec.tolOf(diff.key)),
                    progress = PoseSpecMatcher.score(p, spec),
                    key = "angle_${diff.key.name}"
                )
            }
        }

        if (p.personCount > 1) {
            out += Guidance(
                source = GuidanceSource.MULTI_PERSON,
                severity = Severity.HINT,
                priority = 75,
                text = "画面里有 ${p.personCount} 个人",
                detail = "当前只引导占比最大的那一个，其他人不会被考虑",
                progress = 1f,
                key = "multi_person"
            )
        }

        if (out.isEmpty()) {
            out += ready(i.hasTarget, spec?.verified)
        }
        return out
    }

    private fun ready(hasTarget: Boolean = true, verified: Boolean? = null): Guidance {
        val detail = when {
            !hasTarget -> "还没选模板，只做了基础构图检查"
            verified == false -> "站位与景别已到位；角度目标是未经校准的初值"
            else -> "站位、景别、角度都在容差内"
        }
        return Guidance(
            source = GuidanceSource.READY,
            severity = Severity.OK,
            priority = 100,
            text = "可以拍了",
            detail = detail,
            progress = 1f,
            key = "ready"
        )
    }

    /** 允许左右镜像时，取离目标更近的那条三分线 */
    private fun cxDelta(actual: Float, target: Float, mirrored: Boolean): Float {
        val direct = actual - target
        if (!mirrored) return direct
        val flipped = actual - (1f - target)
        return if (abs(flipped) < abs(direct)) flipped else direct
    }

    private const val TILT_TOL = 3f
    private const val TILT_WARN = 6f
    private const val RATIO_TOL = 0.06f
    private const val RATIO_WARN = 0.15f
    private const val CX_TOL = 0.08f
    private const val FOOT_TOL = 0.10f
}
