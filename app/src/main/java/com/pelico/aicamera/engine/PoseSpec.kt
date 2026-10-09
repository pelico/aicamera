package com.pelico.aicamera.engine

import android.content.Context
import com.pelico.aicamera.contract.PersonResult
import com.pelico.aicamera.contract.ShotSize
import org.json.JSONObject
import kotlin.math.abs

/**
 * 模板的**可验证部分**：任务书附录 A 的落地。
 *
 * 与 `PoseLibrary` 里的 45 条模板是两件事：
 * - PoseTemplate 描述「人应该怎么摆」，给人看，靠 Kanban 手工写文案
 * - PoseSpec 描述「摆对了之后关节角会是多少」，给机器比，**必须有数值才能进闭环**
 *
 * 目前只有 10 条有 spec，且全部 `verified = false`：这些角度是从几何关系推出来的初值，
 * 不是采样统计出来的。App 内置「复制当前角度」的入口（性能实测页），
 * 用真机把每个姿势各摆几次、把 JSON 贴回 `pose_specs.json`，才能把 verified 翻成 true。
 * 在验证之前，这 10 条只作为「可以试」而不是「已经准」对外。
 */
data class PoseSpec(
    val templateId: String,
    val targetCx: Float,
    val targetFootY: Float,
    val bboxHRatio: Float,
    val shotSize: ShotSize,
    val facing: BodyFacing,
    val angles: Map<AngleKey, Float>,
    val tol: Map<AngleKey, Float>,
    val weights: Map<AngleKey, Float>,
    /** 左右互换算不算对（大多数姿势左右都行） */
    val mirrored: Boolean,
    /** 角度值是否经过真人采样验证 */
    val verified: Boolean
) {
    fun tolOf(key: AngleKey): Float = tol[key] ?: DEFAULT_TOL
    fun weightOf(key: AngleKey): Float = weights[key] ?: 1f
}

object PoseSpecMatcher {

    /** 某一个关节的偏差情况。exceeded = 超出容差的部分（未超出为 0），越大约严重 */
    data class Diff(
        val key: AngleKey,
        val target: Float,
        val actual: Float,
        val delta: Float,
        val exceeded: Float,
        val ok: Boolean
    )

    fun diffs(person: PersonResult, spec: PoseSpec): List<Diff> {
        val actual = personAngles(person)
        val direct = rawDiffs(actual, spec, mirror = false)
        val flipped = if (spec.mirrored) rawDiffs(actual, spec, mirror = true) else null
        val best = listOfNotNull(direct, flipped)
            .minByOrNull { it.sumOf { d -> d.exceeded.toDouble() } } ?: emptyList()
        return best
    }

    /** 当前最该改的那一个关节 */
    fun worst(person: PersonResult, spec: PoseSpec): Diff? =
        diffs(person, spec).maxByOrNull { it.exceeded }?.takeIf { !it.ok }

    /** 0..1，给 UI 做「变绿」进度；只看参与匹配的项目 */
    fun score(person: PersonResult, spec: PoseSpec): Float {
        val list = diffs(person, spec)
        if (list.isEmpty()) return 0f
        val total = list.sumOf { it.exceeded.toDouble() }.toFloat()
        return 1f / (1f + total / SCORE_SCALE)
    }

    private fun personAngles(person: PersonResult): Map<AngleKey, Float> =
        PoseAngles.compute(person.landmarks)

    private fun rawDiffs(
        actual: Map<AngleKey, Float>,
        spec: PoseSpec,
        mirror: Boolean
    ): List<Diff> {
        val out = ArrayList<Diff>(spec.angles.size)
        for ((key, target) in spec.angles) {
            val liveKey = if (mirror) key.mirrored() else key
            val v = actual[liveKey] ?: continue
            val signed = if (mirror && key.flipsOnMirror) -v else v
            val delta = signed - target
            val gap = abs(delta) - spec.tolOf(key)
            out += Diff(
                key = key,
                target = target,
                actual = v,
                delta = delta,
                exceeded = if (gap > 0f) gap * spec.weightOf(key) else 0f,
                ok = gap <= 0f
            )
        }
        return out
    }
}

object PoseSpecStore {

    private val lock = Any()

    @Volatile
    private var specs: List<PoseSpec> = emptyList()

    @Volatile
    var loadError: String? = null
        private set

    fun all(): List<PoseSpec> = specs

    fun forTemplate(id: String): PoseSpec? = specs.firstOrNull { it.templateId == id }

    fun load(context: Context): List<PoseSpec> {
        return try {
            val text = context.assets.open(FILE).bufferedReader().use { it.readText() }
            val parsed = parse(JSONObject(text))
            synchronized(lock) {
                specs = parsed
                loadError = null
            }
            parsed
        } catch (e: Exception) {
            synchronized(lock) { loadError = "pose_specs.json 解析失败：${e.message}" }
            emptyList()
        }
    }

    private fun parse(root: JSONObject): List<PoseSpec> {
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<PoseSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue

            val ratio = o.optDouble("bbox_h_ratio", 0.45).toFloat()
            val facingName = o.optString("facing", BodyFacing.FRONT.name)
            val facing = runCatching { BodyFacing.valueOf(facingName) }.getOrElse { BodyFacing.FRONT }
            // 背面在 2D 骨架上测不出来，这类条目直接不进闭环
            if (facing == BodyFacing.BACK || facing == BodyFacing.LOOK_BACK) continue

            val angles = readFloatMap(o, "angles")
            out += PoseSpec(
                templateId = id,
                targetCx = o.optDouble("target_cx", 0.5).toFloat().coerceIn(0.05f, 0.95f),
                targetFootY = o.optDouble("target_foot_y", 0.85f).toFloat().coerceIn(0.2f, 1f),
                bboxHRatio = ratio,
                // 以 ratio 为准，避免 JSON 里两者写不一致
                shotSize = ShotSize.of(ratio),
                facing = facing,
                angles = angles,
                tol = readFloatMap(o, "tol").filterKeys { angles.containsKey(it) },
                weights = readFloatMap(o, "weights").filterKeys { angles.containsKey(it) },
                mirrored = o.optBoolean("mirrored", true),
                verified = o.optBoolean("verified", false)
            )
        }
        return out
    }

    private fun readFloatMap(owner: JSONObject, field: String): Map<AngleKey, Float> {
        val obj = owner.optJSONObject(field) ?: return emptyMap()
        val out = HashMap<AngleKey, Float>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val key = AngleKey.parse(name) ?: continue
            out[key] = obj.optDouble(name, 0.0).toFloat()
        }
        return out
    }

    const val FILE = "pose_specs.json"
    const val DEFAULT_TOL = 18f
    private const val SCORE_SCALE = 40f
}
