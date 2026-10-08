package com.pelico.aicamera

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pelico.aicamera.engine.AestheticScorer
import com.pelico.aicamera.engine.DeviceTierDetector
import com.pelico.aicamera.engine.Lighting
import com.pelico.aicamera.engine.LightingAnalyzer
import com.pelico.aicamera.engine.MotionTracker
import com.pelico.aicamera.engine.PoseMatcher
import com.pelico.aicamera.engine.PoseTemplate
import com.pelico.aicamera.engine.SceneClassifier
import com.pelico.aicamera.engine.ScenePrediction
import com.pelico.aicamera.engine.ScoredPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BenchmarkResult(
    val sceneMs: Float,
    val scoreMs: Float,
    val device: String
)

class SceneViewModel(app: Application) : AndroidViewModel(app) {

    private var classifier: SceneClassifier? = null
    private var scorer: AestheticScorer? = null

    /** TFLite Interpreter 不是线程安全的，模型调用统一串行化 */
    private val modelLock = Any()

    private val inFlightLock = Any()

    @Volatile
    private var inFlight = false

    val motion: MotionTracker = MotionTracker(app)

    var sceneTop by mutableStateOf<List<ScenePrediction>>(emptyList())
        private set

    var lighting by mutableStateOf<Lighting?>(null)
        private set

    var poses by mutableStateOf<List<ScoredPose>>(emptyList())
        private set

    var selected by mutableIntStateOf(0)
        private set

    var latencyMs by mutableFloatStateOf(0f)
        private set

    var aesthetic by mutableFloatStateOf(0f)
        private set

    var enginesReady by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val currentPose: PoseTemplate?
        get() = poses.getOrNull(selected)?.template

    val currentScene: ScenePrediction?
        get() = sceneTop.firstOrNull()

    fun ensureEngines() {
        if (classifier != null && scorer != null) return
        try {
            val app = getApplication<Application>()
            classifier = SceneClassifier(app)
            scorer = AestheticScorer(app)
            enginesReady = true
        } catch (e: Exception) {
            error = "模型加载失败：${e.message}"
        }
    }

    /**
     * 分析一帧：场景分类 + 光线判断 + 姿势匹配。
     * 可从相机分析线程直接调用，内部会切线程。传入的 bitmap 用完后会被回收。
     */
    fun analyze(bitmap: Bitmap) {
        synchronized(inFlightLock) {
            if (inFlight) {
                bitmap.recycle()
                return
            }
            inFlight = true
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val c = classifier
                if (c == null) return@launch
                val t0 = System.nanoTime()
                val preds = synchronized(modelLock) { c.classify(bitmap, topK = 5) }
                val light = LightingAnalyzer.analyze(bitmap)
                val ranked = PoseMatcher.rank(preds, light, limit = 3)
                val dt = (System.nanoTime() - t0) / 1_000_000f
                withContext(Dispatchers.Main) {
                    sceneTop = preds
                    lighting = light
                    poses = ranked
                    selected = 0
                    latencyMs = dt
                }
            } finally {
                bitmap.recycle()
                synchronized(inFlightLock) { inFlight = false }
            }
        }
    }

    /** 给一张已有照片打分并推荐"下次该怎么拍" */
    fun analyzePhoto(bitmap: Bitmap, withScore: Boolean = true) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val c = classifier
                if (c == null) return@launch
                val preds = synchronized(modelLock) { c.classify(bitmap, topK = 5) }
                val light = LightingAnalyzer.analyze(bitmap)
                val ranked = PoseMatcher.rank(preds, light, limit = 3)
                val score = if (withScore) {
                    synchronized(modelLock) { scorer?.score(bitmap) ?: 0f }
                } else 0f
                withContext(Dispatchers.Main) {
                    sceneTop = preds
                    lighting = light
                    poses = ranked
                    selected = 0
                    aesthetic = score
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message }
            }
        }
    }

    /** 从姿势库里手动选一条，切换回取景页就能照着摆 */
    fun previewPose(template: PoseTemplate) {
        poses = listOf(ScoredPose(template, 0f, listOf("手动选择")))
        selected = 0
    }

    fun select(index: Int) {
        if (index in poses.indices) selected = index
    }

    fun cycle(delta: Int) {
        if (poses.isEmpty()) return
        selected = (selected + delta + poses.size) % poses.size
    }

    suspend fun runBenchmark(): BenchmarkResult? {
        val c = classifier ?: return null
        val s = scorer ?: return null
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
                synchronized(modelLock) {
                    c.classify(probe)
                    s.score(probe)
                }
                val t0 = System.nanoTime()
                synchronized(modelLock) {
                    repeat(sceneRuns) { c.classify(probe) }
                }
                val sceneMs = (System.nanoTime() - t0) / 1_000_000f / sceneRuns

                val t1 = System.nanoTime()
                synchronized(modelLock) {
                    repeat(scoreRuns) { s.score(probe) }
                }
                val scoreMs = (System.nanoTime() - t1) / 1_000_000f / scoreRuns

                BenchmarkResult(sceneMs, scoreMs, DeviceTierDetector.describe())
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
    }
}
