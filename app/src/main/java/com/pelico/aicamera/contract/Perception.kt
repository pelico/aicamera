package com.pelico.aicamera.contract

import android.graphics.RectF
import com.pelico.aicamera.engine.BodyFacing
import com.pelico.aicamera.engine.LightQuality
import com.pelico.aicamera.engine.SceneGroup
import com.pelico.aicamera.engine.ScenePrediction
import com.pelico.aicamera.engine.TimeOfDay

/**
 * L0 感知层向上输出的统一数据契约。
 *
 * 这层存在的意义：多头模型（Places365 / MediaPipe Pose / 光线统计）各自的数据类不同，
 * L4 决策层只认这里的三个类型，换模型时不牵动上层。
 * 坐标一律使用**画面归一化坐标 0..1**，与原分辨率无关。
 */

// ---------------------------------------------------------------- 场景

/** 粗粒度场景：用来选模板家族，比 365 类细标签稳定得多 */
enum class SceneCoarse(val zh: String) {
    WATER("水边 · 海河湖"),
    NATURE("山野 · 林间 · 雪原"),
    FIELD("田野 · 花海 · 草原"),
    URBAN("城市 · 街头 · 夜景"),
    ARCH("建筑 · 古建 · 校园"),
    INTERIOR("室内 · 店场馆"),
    TRANSPORT("车内 · 站台 · 车厢"),
    GENERIC("未分类")
}

/** 画面里**能测出来**的属性。测不出来的（如相机距地高度）不进这个结构 */
data class SceneAttrs(
    val timeOfDay: TimeOfDay,
    val quality: LightQuality,
    val brightness: Float,
    val contrast: Float,
    val sunSide: Float
)

data class SceneResult(
    val coarse: SceneCoarse,
    val coarseProb: Float,
    val top: List<ScenePrediction>,
    val attrs: SceneAttrs
) {
    companion object {
        fun from(group: SceneGroup): SceneCoarse = when (group) {
            SceneGroup.WATER -> SceneCoarse.WATER
            SceneGroup.MOUNTAIN, SceneGroup.FOREST, SceneGroup.SNOW -> SceneCoarse.NATURE
            SceneGroup.FIELD -> SceneCoarse.FIELD
            SceneGroup.STREET, SceneGroup.NIGHT -> SceneCoarse.URBAN
            SceneGroup.ARCH, SceneGroup.CAMPUS -> SceneCoarse.ARCH
            SceneGroup.SHOP -> SceneCoarse.INTERIOR
            SceneGroup.RIDE -> SceneCoarse.TRANSPORT
            SceneGroup.GENERAL -> SceneCoarse.GENERIC
        }
    }
}

// ---------------------------------------------------------------- 人

/**
 * 景别。阈值按「人物包围盒高度 / 画面高度」划分，只用于判断是否偏离目标，
 * 不用于给艺术创作下的景别下结论。
 */
enum class ShotSize(val zh: String, val minRatio: Float, val maxRatio: Float) {
    LONG("远景 · 人在画面里很小", 0f, 0.35f),
    FULL("全身", 0.35f, 0.55f),
    MEDIUM("七分身 · 膝盖以上", 0.55f, 0.72f),
    CLOSE("半身 · 腰以上", 0.72f, 0.90f),
    BIG_CLOSE("特写", 0.90f, Float.MAX_VALUE);

    companion object {
        fun of(ratio: Float): ShotSize = entries.firstOrNull { ratio >= it.minRatio && ratio < it.maxRatio } ?: BIG_CLOSE
    }
}

/** MediaPipe 的 33 点之一，坐标归一化到画面 */
data class LmPoint(val x: Float, val y: Float, val visibility: Float)

/**
 * 一个人的检测结果。
 *
 * 诚实边界：MediaPipe Pose 输出的是**朝向 -Z 的骨架**，正面和背面在 2D 投影上无法区分，
 * 因此 [facing] 只可能是 FRONT / SIDE_45 / SIDE_90，永远不会有 BACK。
 * 需要背身效果的模板不参与角度闭环。
 */
data class PersonResult(
    val landmarks: List<LmPoint>,
    val bbox: RectF,
    val shotSize: ShotSize,
    val facing: BodyFacing,
    val centerX: Float,
    val centerY: Float,
    val footY: Float,
    val heightRatio: Float,
    /** 核心关键点的可见比例，低说明人被挡住或没完全进画面 */
    val visibleRatio: Float,
    /** 画面里检出的人数（>1 时只跟踪占比最大的一个） */
    val personCount: Int,
    /** 包围盒是否触碰画面边缘 —— 人可能被切掉了 */
    val cropped: Boolean
)

// ---------------------------------------------------------------- 指令

/** 指令严重程度，决定 UI 颜色与是否抢焦点 */
enum class Severity(val zh: String) {
    BLOCKER("必须处理"),
    WARN("建议处理"),
    HINT("可以微调"),
    OK("到位了")
}

/** 指令来源，用于去重与优先级排序 */
enum class GuidanceSource(val zh: String) {
    NO_PERSON("没检到人"),
    MULTI_PERSON("多人入镜"),
    OCCLUDED("人被遮挡"),
    LEVEL("水平"),
    SHOT_SIZE("景别"),
    POSITION("站位"),
    POSE_ANGLE("姿势"),
    READY("就绪"),
    FALLBACK("降级")
}

/**
 * 一条给用户的可执行指令。L4 每帧只产出**一条**，
 * UI 一次只显示一条 —— 三条并列的结果是用户一条都不看。
 */
data class Guidance(
    val source: GuidanceSource,
    val severity: Severity,
    /** 数字越小越优先 */
    val priority: Int,
    val text: String,
    val detail: String?,
    /** 0..1，越接近 1 越好，用于「变绿」 */
    val progress: Float,
    /** 去抖用的稳定身份 */
    val key: String
)
