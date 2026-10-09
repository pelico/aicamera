package com.pelico.aicamera.engine

import android.content.Context
import org.json.JSONObject

/**
 * 场景适宜度：这个场景值不值得拍人像。
 *
 * 之前的逻辑对任何场景都硬凑一条模板，在浴室、更衣室、机房、垃圾场里
 * 也会一本正经地推荐「三分站姿」。 Places365 的 365 类里有相当一部分
 * 本来就不是拍照场景，硬给建议比不给更糟。
 */
enum class SceneUsability(val zh: String, val advice: String) {
    GREAT("适合拍", ""),
    OK("可以拍", ""),
    WEAK("能拍，但要挑角度", "背景杂乱，换背景干净的角度或用大光圈压掉"),
    POOR("不建议在这里拍人像", "换个地方；这类场景出片率低，AI 给姿势也没用")
}

/**
 * Places365 标签 → 模板 id 的**有序**亲和表。
 *
 * 为什么要有这张表：原来的匹配是「tag 命中 4.0 / 片段命中 2.0 / 分组兜底 1.5」，
 * 同一个分组里的模板拿到的分数完全相同，最终返回哪条取决于 Kotlin 稳定排序下
 * 模板在库里的位置。实测 365 个场景里 74% 的首条推荐存在同分并列 ——
 * 也就是说推荐基本不是匹配出来的。这张表把「这个场景该推哪几条」显式写死。
 *
 * 表由 tools/gen_affinity.py 生成，改完 PoseTemplate.kt 后重新生成一次。
 * 表缺失时静默降级到旧的 tag / 分组打分，不会崩。
 */
object SceneAffinity {

    private const val FILE = "scene_affinity.json"

    private var loaded = false
    private var map: Map<String, List<String>> = emptyMap()
    private var fallback: List<String> = emptyList()
    private var poor: Set<String> = emptySet()
    private var weak: Set<String> = emptySet()

    /** 加载失败时给出原因，UI 可以显示；正常情况为 null */
    var loadError: String? = null
        private set

    val isReady: Boolean get() = loaded && map.isNotEmpty()

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val text = context.assets.open(FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            fallback = root.optJSONArray("fallback")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            poor = root.optJSONArray("poor")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
            } ?: emptySet()

            weak = root.optJSONArray("weak")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
            } ?: emptySet()

            val m = LinkedHashMap<String, List<String>>()
            root.optJSONObject("map")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    val arr = obj.optJSONArray(key) ?: continue
                    val ids = (0 until arr.length()).map { i -> arr.getString(i) }
                    if (ids.isNotEmpty()) m[key.lowercase()] = ids
                }
            }
            map = m
        } catch (e: Exception) {
            loadError = "亲和表加载失败：${e.message}"
        }
    }

    /**
     * 该场景推荐的模板 id（按优先级排好）。
     * 查不到时返回 null，调用方回落到 tag / 分组打分。
     */
    fun templatesFor(label: String): List<String>? = map[label.lowercase()]

    fun fallbackIds(): List<String> = fallback

    fun usabilityOf(label: String): SceneUsability {
        val l = label.lowercase()
        if (poor.any { l.contains(it) }) return SceneUsability.POOR
        if (weak.any { l.contains(it) }) return SceneUsability.WEAK
        return SceneUsability.OK
    }
}
