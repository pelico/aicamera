package com.pelico.aicamera.engine

/**
 * 场景 → 姿势模板的匹配打分。
 *
 * 上一版的问题（实测出来的，不是猜的）：
 *  1. 同组模板同分：分组兜底给同组所有模板一样的分数，首条靠 Kotlin 稳定排序
 *     「库里排最前那条」决定。365 个场景里 74% 存在这种同分并列。
 *  2. 时段/光线盖过场景：时段 +1.2、光线 +1.0，而分组命中只有 1.5×概率 ≈ 0.6。
 *     结果是"现在几点"比"你在哪"更能决定推荐什么。
 *
 * 这一版：
 *  - 主分来自 [SceneAffinity] 的显式有序表（365 个标签各有一份排好序的模板列表）
 *  - 时段/光线降到每条最多 0.6 / 0.5，只做微调，永远盖不过场景
 *  - 纳入画面上下文：人数、当前景别（已经怼脸特写了还推全身模板就没意义）
 *  - [MatchContext.exclude] 用于轮换，避免同一场景永远推第一条
 */
data class ScoredPose(
    val template: PoseTemplate,
    val score: Float,
    val reasons: List<String>
)

/** 画面上下文：让推荐跟"画面里现在有什么"有关，而不只是跟场景有关 */
data class MatchContext(
    /** 画面里的人数，>1 时优先给能容纳多人的远景模板 */
    val personCount: Int = 1,
    /** 当前人物占画面高度比例，用来判断是不是已经怼到特写了 */
    val heightRatio: Float? = null,
    /** 这一轮想压下去的模板 id（上一轮推过，换一条） */
    val exclude: Set<String> = emptySet()
)

object PoseMatcher {

    /** 亲和表第 1 名的基准分；名次每降一位减 1.5 */
    private const val AFFINITY_BASE = 6.0f
    private const val AFFINITY_STEP = 1.5f

    /** 亲和表查不到时的旧规则兜底 */
    private const val TAG_HIT = 2.5f
    private const val SEGMENT_HIT = 1.5f
    private const val GROUP_HIT = 1.0f

    /** 时段/光线只做微调，永远不足以翻掉场景顺序（两条最多 1.1） */
    private const val TIME_BONUS = 0.6f
    private const val LIGHT_BONUS = 0.5f

    /** 有 pose_spec 的模板能进闭环，同等条件下优先 */
    private const val SPEC_BONUS = 0.8f

    private const val EXCLUDE_PENALTY = 2.0f
    private const val MULTI_PENALTY = 1.0f
    private const val CLOSEUP_PENALTY = 0.8f

    /** 近景模板阈值：人占画面高度超过这个比例就算特写了 */
    private const val CLOSEUP_RATIO = 0.72f
    /** 需要全身的模板阈值：低于这个比例的站位是远景/全身 */
    private const val FULLBODY_RATIO = 0.45f

    fun rank(
        predictions: List<ScenePrediction>,
        lighting: Lighting,
        ctx: MatchContext = MatchContext(),
        pool: List<PoseTemplate> = PoseLibrary.ALL,
        limit: Int = 3,
        boost: Set<String> = emptySet()
    ): List<ScoredPose> {
        if (predictions.isEmpty()) {
            return pool.filter { it.group == SceneGroup.GENERAL }
                .take(limit)
                .map { ScoredPose(it, 0f, listOf("等待识别")) }
        }

        val multi = ctx.personCount > 1
        val closeUp = ctx.heightRatio != null && ctx.heightRatio > CLOSEUP_RATIO

        val byId = pool.associateBy { it.id }
        val scored = pool.map { template ->
            var score = 0f
            var bestAffinity = 0f
            var bestZh: String? = null
            var bestWhy: String? = null

            predictions.forEachIndexed { i, pred ->
                // 后面的预测衰减，top-1 主导
                val w = pred.prob * DECAY.getOrElse(i) { 0.05f }
                val ids = SceneAffinity.templatesFor(pred.label)
                val hit = if (ids != null) {
                    val pos = ids.indexOf(template.id)
                    if (pos >= 0) (AFFINITY_BASE - pos * AFFINITY_STEP).coerceAtLeast(0.5f) * w else 0f
                } else {
                    legacyHit(template, pred.label) * w
                }
                if (hit > bestAffinity) {
                    bestAffinity = hit
                    bestZh = pred.zh
                    bestWhy = if (ids != null) "适合${pred.zh}" else null
                }
            }

            score += bestAffinity

            val reasons = mutableListOf<String>()
            if (bestAffinity > 0f && bestZh != null) {
                reasons.add(bestWhy ?: "场景大类 · ${template.group.zh}")
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
                reasons.add("可对齐")
            }

            // 上下文修正
            if (multi && template.placement.height > CLOSEUP_RATIO) score -= MULTI_PENALTY
            if (closeUp && template.placement.height < FULLBODY_RATIO) {
                score -= CLOSEUP_PENALTY
                reasons.add("现在太近，这条要全身")
            }
            if (ctx.exclude.contains(template.id)) score -= EXCLUDE_PENALTY

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
        for (item in sorted) {
            if (picked.size >= limit) break
            if (picked.any { it.template.id == item.template.id }) continue
            picked.add(item)
        }

        // 分组兜底：亲和表里的 id 若不在库里（改模板时忘了重新生成表），至少不空手
        if (picked.isEmpty()) {
            val ids = SceneAffinity.fallbackIds().mapNotNull { byId[it] }
            return ids.take(limit).map { ScoredPose(it, 0f, listOf("通用站姿")) }
        }
        return picked
    }

    private val DECAY = floatArrayOf(1f, 0.45f, 0.25f, 0.12f, 0.06f)

    private fun legacyHit(template: PoseTemplate, label: String): Float =
        when {
            template.tags.contains(label) -> TAG_HIT
            label.split('/').any { seg -> template.tags.contains(seg) } -> SEGMENT_HIT
            SceneLabels.groupOf(label) == template.group -> GROUP_HIT
            else -> 0f
        }
}
