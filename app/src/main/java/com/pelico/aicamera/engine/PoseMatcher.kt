package com.pelico.aicamera.engine

/**
 * 场景 → 姿势模板的匹配打分。
 *
 * 打分构成：
 * - 标签精确命中：4.0 × 场景概率（模板的 tags 与 Places365 标签完全一致）
 * - 标签片段命中：2.0 × 场景概率（例如模板写 `forest`，预测是 `forest/broadleaf`）
 * - 场景分组命中：1.5 × 场景概率（兜底，保证任何场景都有对应模板）
 * - 时段命中：+1.2
 * - 光线命中：+1.0
 *
 * 分组命中是关键的兜底：即便模板没覆盖这个具体标签，也能给出同大类的建议，
 * 不会出现"识别出来却什么都不推荐"的空窗。
 */
data class ScoredPose(
    val template: PoseTemplate,
    val score: Float,
    val reasons: List<String>
)

object PoseMatcher {

    private const val TAG_HIT = 4.0f
    private const val SEGMENT_HIT = 2.0f
    private const val GROUP_HIT = 1.5f
    private const val TIME_BONUS = 1.2f
    private const val LIGHT_BONUS = 1.0f
    /** 有 pose_spec 的模板优先：只有它们能进「摆到位了」的闭环 */
    private const val SPEC_BONUS = 2.0f

    fun rank(
        predictions: List<ScenePrediction>,
        lighting: Lighting,
        pool: List<PoseTemplate> = PoseLibrary.ALL,
        limit: Int = 3,
        boost: Set<String> = emptySet()
    ): List<ScoredPose> {
        if (predictions.isEmpty()) {
            return pool.filter { it.group == SceneGroup.GENERAL }
                .take(limit)
                .map { ScoredPose(it, 0f, listOf("等待识别")) }
        }

        val scored = pool.map { template ->
            var score = 0f
            val reasons = mutableListOf<String>()

            var bestScene: ScenePrediction? = null
            var bestSceneScore = 0f
            for (pred in predictions) {
                val hit = when {
                    template.tags.contains(pred.label) -> TAG_HIT * pred.prob
                    segmentHit(template.tags, pred.label) -> SEGMENT_HIT * pred.prob
                    else -> 0f
                }
                if (hit > bestSceneScore) {
                    bestSceneScore = hit
                    bestScene = pred
                }
            }
            if (bestSceneScore > 0f && bestScene != null) {
                score += bestSceneScore
                reasons.add("场景匹配 · ${bestScene.zh}")
            }

            val groupScore = predictions
                .filter { it.group == template.group }
                .maxOfOrNull { GROUP_HIT * it.prob } ?: 0f
            if (groupScore > 0f) {
                score += groupScore
                if (bestSceneScore == 0f) {
                    reasons.add("场景大类 · ${template.group.zh}")
                }
            }

            if (template.times.isNotEmpty() && lighting.timeOfDay in template.times) {
                score += TIME_BONUS
                reasons.add("适合${lighting.timeOfDay.zh}")
            }
            if (template.lights.isNotEmpty() && lighting.quality in template.lights) {
                score += LIGHT_BONUS
                reasons.add("适合${lighting.quality.zh}")
            }
            if (boost.contains(template.id)) {
                score += SPEC_BONUS
                reasons.add("可校准")
            }

            ScoredPose(template, score, reasons)
        }

        val sorted = scored.sortedByDescending { it.score }

        // 同一分组最多出 2 条，避免三个结果长得一样
        val picked = mutableListOf<ScoredPose>()
        val groupCount = mutableMapOf<SceneGroup, Int>()
        for (item in sorted) {
            val g = item.template.group
            val c = groupCount[g] ?: 0
            if (c >= 2) continue
            picked.add(item)
            groupCount[g] = c + 1
            if (picked.size >= limit) break
        }

        if (picked.size < limit) {
            for (item in sorted) {
                if (picked.any { it.template.id == item.template.id }) continue
                picked.add(item)
                if (picked.size >= limit) break
            }
        }

        return picked
    }

    private fun segmentHit(tags: List<String>, label: String): Boolean {
        val segments = label.split('/')
        return tags.any { tag -> segments.any { seg -> seg == tag } }
    }
}
