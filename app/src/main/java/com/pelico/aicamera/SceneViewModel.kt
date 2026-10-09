package com.pelico.aicamera

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pelico.aicamera.contract.Guidance
import com.pelico.aicamera.contract.PersonResult
import com.pelico.aicamera.contract.SceneAttrs
import com.pelico.aicamera.contract.SceneResult
import com.pelico.aicamera.engine.AestheticScorer
import com.pelico.aicamera.engine.DeviceTierDetector
import com.pelico.aicamera.engine.GuidanceArbiter
import com.pelico.aicamera.engine.Lighting
import com.pelico.aicamera.engine.LightingAnalyzer
import com.pelico.aicamera.engine.MotionTracker
import com.pelico.aicamera.engine.PoseAngles
import com.pelico.aicamera.engine.PoseEngine
import com.pelico.aicamera.engine.MatchContext
import com.pelico.aicamera.engine.PoseMatcher
import com.pelico.aicamera.engine.PoseSpec
import com.pelico.aicamera.engine.PoseSpecStore
import com.pelico.aicamera.engine.PoseTemplate
import com.pelico.aicamera.engine.RuntimePolicy
import com.pelico.aicamera.engine.SceneAffinity
import com.pelico.aicamera.engine.SceneClassifier
import com.pelico.aicamera.engine.ScenePrediction
import com.pelico.aicamera.engine.SceneUsability
import com.pelico.aicamera.engine.ScoredPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class BenchmarkResult(
    val sceneMs: Float,
    val scoreMs: Float,
    val poseMs: Float,
    val device: String
)

class SceneViewModel(app: Application) : AndroidViewModel(app) {

    private var classifier: SceneClassifier? = null
    private var scorer: AestheticScorer? = null
    private var poseEngine: PoseEngine? = null

    /** TFLite Interpreter 不是线程安全的，场景/美学模型的调用统一串行化 */
    private val modelLock = Any()

    /** MediaPipe 走单线程，避免和场景模型抢核导致的抖动 */
    private val poseDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val busy = AtomicBoolean(false)

    /** 设备档位真正接进管线：由它决定两条节奏与抽帧分辨率 */
    val policy: RuntimePolicy = DeviceTierDetector.policy()

    val motion: MotionTracker = MotionTracker(app)

    // ---------------- UI 状态 ----------------

    var sceneTop by mutableStateOf<List<ScenePrediction>>(emptyList())
        private set

    var scene by mutableStateOf<SceneResult?>(null)
        private set

    var person by mutableStateOf<PersonResult?>(null)
        private set

    var guidance by mutableStateOf<Guidance?>(null)
        private set

    var lighting by mutableStateOf<Lighting?>(null)
        private set

    var poses by mutableStateOf<List<ScoredPose>>(emptyList())
        private set

    /** 当前场景适不适合拍人像。POOR 时不推模板，直说原因 */
    var usability by mutableStateOf(SceneUsability.OK)
        private set

    var selected by mutableIntStateOf(0)
        private set

    var latencyMs by mutableFloatStateOf(0f)
        private set

    var poseLatencyMs by mutableFloatStateOf(0f)
        private set

    var aesthetic by mutableFloatStateOf(0f)
        private set

    var enginesReady by mutableStateOf(false)
        private set

    var poseAvailable by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var specIds: Set<String> = emptySet()

    private var missCount = 0
    private var pendingKey: String? = null
    private var pendingSince = 0L

    /** 轮换去重：同一场景连续出现时换一条推荐，别十秒过去还是同一条 */
    private var lastLabel: String? = null
    private var labelRun = 0
    private val rotated = LinkedHashSet<String>()

    val currentPose: PoseTemplate?
        get() = poses.getOrNull(selected)?.template

    val currentScene: ScenePrediction?
        get() = sceneTop.firstOrNull()

    val currentSpec: PoseSpec?
        get() = currentPose?.id?.let { PoseSpecStore.forTemplate(it) }

    val analysisSize: Size get() = policy.analysisSize

    fun ensureEngines() {
        if (classifier != null && scorer != null) return
        try {
            val ctx = getApplication<Application>()
            classifier = SceneClassifier(ctx)
            scorer = AestheticScorer(ctx)
            SceneAffinity.load(ctx)
            val specs = PoseSpecStore.load(ctx)
            specIds = specs.map { it.templateId }.toSet()
            if (policy.poseEnabled) {
                val engine = PoseEngine(ctx)
                poseEngine = engine
                poseAvailable = engine.available
            }
            PoseSpecStore.loadError?.let { error = it }
            // 亲和表缺失不会崩（会回落到 tag/分组打分），但要让用户知道推荐质量下降
            SceneAffinity.loadError?.let { error = it }
            enginesReady = true
        } catch (e: Exception) {
            error = "模型加载失败：${e.message}"
        }
    }

    /**
     * 分析一帧。整帧只转换一次 Bitmap，两条节奏由调用方决定：
     * 场景慢（policy.sceneIntervalMs）、姿态快（policy.poseIntervalMs）。
     *
     * Bitmap 由本函数负责回收。上一帧还没处理完时直接丢帧 —— 迟到的一帧没有价值。
     */
    fun analyzeFrame(bitmap: Bitmap, withScene: Boolean, withPose: Boolean) {
        if (!busy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (withScene) runScene(bitmap)
                if (withPose) runPose(bitmap)
            } finally {
                bitmap.recycle()
                busy.set(false)
            }
        }
    }

    private suspend fun runScene(bitmap: Bitmap) {
        val c = classifier ?: return
        val t0 = System.nanoTime()
        val preds = synchronized(modelLock) { c.classify(bitmap, topK = 5) }
        val light = LightingAnalyzer.analyze(bitmap)
        val top = preds.firstOrNull() ?: return
        val result = SceneResult(
            coarse = SceneResult.from(top.group),
            coarseProb = top.prob,
            top = preds,
            attrs = SceneAttrs(
                timeOfDay = light.timeOfDay,
                quality = light.quality,
                brightness = light.brightness,
                contrast = light.contrast,
                sunSide = light.sunSide
            )
        )
        val (usable, ranked) = rankFor(preds, light)
        val dt = (System.nanoTime() - t0) / 1_000_000f
        withContext(Dispatchers.Main) {
            sceneTop = preds
            scene = result
            lighting = light
            usability = usable
            poses = ranked
            selected = 0
            latencyMs = dt
            applyGuidance()
        }
    }

    /**
     * 场景 → 模板，外加两件以前没做的事：
     *  - 场景不适宜时直接不给模板（在浴室里推荐站姿是荒谬的）
     *  - 同一场景停留够久就轮换，避免十秒钟过去还是同一条
     */
    private fun rankFor(
        preds: List<ScenePrediction>,
        light: Lighting
    ): Pair<SceneUsability, List<ScoredPose>> {
        val top = preds.firstOrNull() ?: return SceneUsability.OK to emptyList()

        val usable = SceneAffinity.usabilityOf(top.label)
        if (usable == SceneUsability.POOR) {
            lastLabel = top.label
            labelRun = 0
            rotated.clear()
            return usable to emptyList()
        }
        if (top.label != lastLabel) {
            lastLabel = top.label
            labelRun = 0
            rotated.clear()
        }
        labelRun++

        val ctx = MatchContext(
            personCount = person?.personCount ?: 1,
            heightRatio = person?.heightRatio,
            exclude = rotated
        )
        var ranked = PoseMatcher.rank(preds, light, ctx, boost = specIds)
        if (labelRun >= ROTATE_AFTER && ranked.isNotEmpty()) {
            rotated.add(ranked.first().template.id)
            ranked = PoseMatcher.rank(preds, light, ctx.copy(exclude = rotated), boost = specIds)
            labelRun = 0
            if (rotated.size >= 3) rotated.clear()
        }
        return usable to ranked
    }

    private suspend fun runPose(bitmap: Bitmap) {
        val engine = poseEngine ?: return
        val t0 = System.nanoTime()
        val detected = withContext(poseDispatcher) { runCatching { engine.detect(bitmap) }.getOrNull() }
        val dt = (System.nanoTime() - t0) / 1_000_000f
        withContext(Dispatchers.Main) {
            poseLatencyMs = dt
            if (detected == null) {
                // 连续几帧都找不到才判定「人走了」，避免单帧抖动把 UI 打散
                missCount++
                if (missCount >= MISS_FRAMES) person = null
            } else {
                missCount = 0
                person = detected
            }
            applyGuidance()
        }
    }

    /** 给一张已有照片打分，并把静态图也过一遍闭环（水平用 0 度，照片本身没有倾角来源） */
    fun analyzePhoto(bitmap: Bitmap, withScore: Boolean = true) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val c = classifier ?: return@launch
                val engine = poseEngine
                val preds = synchronized(modelLock) { c.classify(bitmap, topK = 5) }
                val light = LightingAnalyzer.analyze(bitmap)
                val top = preds.firstOrNull() ?: return@launch
                val result = SceneResult(
                    coarse = SceneResult.from(top.group),
                    coarseProb = top.prob,
                    top = preds,
                    attrs = SceneAttrs(light.timeOfDay, light.quality, light.brightness, light.contrast, light.sunSide)
                )
                val detected =
                    if (engine != null) withContext(poseDispatcher) { runCatching { engine.detect(bitmap) }.getOrNull() }
                    else null
                val (usable, ranked) = rankFor(preds, light)
                val score = if (withScore) synchronized(modelLock) { scorer?.score(bitmap) ?: 0f } else 0f
                withContext(Dispatchers.Main) {
                    sceneTop = preds
                    scene = result
                    lighting = light
                    usability = usable
                    poses = ranked
                    selected = 0
                    person = detected
                    aesthetic = score
                    applyGuidance(tiltDeg = 0f)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message }
            }
        }
    }

    // ---------------- 指令去抖 ----------------

    private fun applyGuidance(tiltDeg: Float = motion.tiltDeg) {
        val candidate = GuidanceArbiter.decide(
            GuidanceArbiter.Input(
                poseEnabled = policy.poseEnabled && poseAvailable,
                person = person,
                template = currentPose,
                spec = currentSpec,
                tiltDeg = tiltDeg,
                hasTarget = poses.isNotEmpty(),
                usability = usability,
                sceneZh = sceneTop.firstOrNull()?.zh
            )
        )
        val now = SystemClock.elapsedRealtime()

        // 阻塞级问题立即显示，其余要稳定一小段时间，避免逐帧闪烁
        if (candidate.priority <= INSTANT_PRIORITY) {
            pendingKey = candidate.key
            pendingSince = now
            guidance = candidate
            return
        }
        if (candidate.key != pendingKey) {
            pendingKey = candidate.key
            pendingSince = now
            return
        }
        if (now - pendingSince >= STABLE_MS) guidance = candidate
    }

    /** 手动刷新一次指令（Picker 刚选完模板等场景） */
    fun refreshGuidance() = applyGuidance()

    // ---------------- 姿势库 ----------------

    fun previewPose(template: PoseTemplate) {
        poses = listOf(ScoredPose(template, 0f, listOf("手动选择")))
        selected = 0
        applyGuidance()
    }

    fun select(index: Int) {
        if (index in poses.indices) {
            selected = index
            applyGuidance()
        }
    }

    fun cycle(delta: Int) {
        if (poses.isEmpty()) return
        selected = (selected + delta + poses.size) % poses.size
        applyGuidance()
    }

    /** 当前 33 点算出的角度，用来给 pose_specs.json 采样校准 */
    fun anglesSnapshot(): String? {
        val p = person ?: return null
        val angles = PoseAngles.compute(p.landmarks)
        if (angles.isEmpty()) return null
        val body = angles.entries.joinToString(",\n        ") { "\"${it.key.name}\": ${it.value.toInt()}" }
        return "{\n      \"angles\": {\n        $body\n      }\n    }"
    }

    suspend fun runBenchmark(): BenchmarkResult? {
        val c = classifier ?: return null
        val s = scorer ?: return null
        val engine = poseEngine
        return withContext(Dispatchers.Default) {
            val size = 224
            val probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val px = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    px[y * size + x] = 0xFF000000.toInt() or
                        ((x * 255 / (size - 1)) shl 16) or
                        ((y * 255 / (size - 1)) shl 8) or
                        128
                }
            }
            probe.setPixels(px, 0, size, 0, 0, size, size)

            try {
                val sceneRuns = 20
                val scoreRuns = 10
                val poseRuns = 5
                synchronized(modelLock) {
                    c.classify(probe)
                    s.score(probe)
                }

                val t0 = System.nanoTime()
                synchronized(modelLock) { repeat(sceneRuns) { c.classify(probe) } }
                val sceneMs = (System.nanoTime() - t0) / 1_000_000f / sceneRuns

                val t1 = System.nanoTime()
                synchronized(modelLock) { repeat(scoreRuns) { s.score(probe) } }
                val scoreMs = (System.nanoTime() - t1) / 1_000_000f / scoreRuns

                var poseMs = 0f
                if (engine != null) {
                    runCatching { engine.detect(probe) }
                    val t2 = System.nanoTime()
                    repeat(poseRuns) { runCatching { engine.detect(probe) } }
                    poseMs = (System.nanoTime() - t2) / 1_000_000f / poseRuns
                }

                BenchmarkResult(sceneMs, scoreMs, poseMs, DeviceTierDetector.describe())
            } finally {
                probe.recycle()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        motion.stop()
        classifier?.close()
        scorer?.close()
        poseEngine?.close()
        poseDispatcher.close()
    }

    private companion object {
        const val STABLE_MS = 350L
        const val INSTANT_PRIORITY = 30
        const val MISS_FRAMES = 3
        /** 同一场景连续识别多少次后换一条推荐（场景约 1.5s 一次，这里约 12s） */
        const val ROTATE_AFTER = 8
    }
}
