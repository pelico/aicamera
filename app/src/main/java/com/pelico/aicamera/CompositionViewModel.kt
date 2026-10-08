package com.pelico.aicamera

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pelico.aicamera.engine.AestheticScorer
import com.pelico.aicamera.engine.CompositionBox
import com.pelico.aicamera.engine.CompositionEngine
import com.pelico.aicamera.engine.DeviceTier
import com.pelico.aicamera.engine.DeviceTierDetector
import com.pelico.aicamera.engine.Guidance
import com.pelico.aicamera.engine.GuidanceCalculator
import com.pelico.aicamera.engine.MotionTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LiveResult(
    val box: CompositionBox,
    val guidance: Guidance,
    val score: Float,
    val inferenceMs: Long,
    val steady: Boolean
)

data class AnalysisResult(
    val bitmap: Bitmap,
    val box: CompositionBox,
    val guidance: Guidance,
    val score: Float
)

class CompositionViewModel(app: Application) : AndroidViewModel(app) {

    val tier: DeviceTier = DeviceTierDetector.detect()

    private var composition: CompositionEngine? = null
    private var scorer: AestheticScorer? = null
    private val motion = MotionTracker(app)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _live = MutableStateFlow<LiveResult?>(null)
    val live: StateFlow<LiveResult?> = _live

    private val _analysis = MutableStateFlow<AnalysisResult?>(null)
    val analysis: StateFlow<AnalysisResult?> = _analysis

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var lastInferAt = 0L
    private var smoothedBox: CompositionBox? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                composition = CompositionEngine(app)
                scorer = AestheticScorer(app)
            }.onSuccess {
                _ready.value = true
            }.onFailure {
                Log.e(TAG, "模型加载失败", it)
                _message.value = "模型加载失败：${it.message}"
            }
        }
        motion.start()
    }

    /** 实时取景：按档位节流，推理完回收 bitmap */
    fun onFrame(source: Bitmap, rotationDegrees: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInferAt < tier.frameIntervalMs) {
            source.recycle()
            return
        }
        lastInferAt = now

        val engine = composition
        val scoreEngine = scorer
        if (engine == null || scoreEngine == null) {
            source.recycle()
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val rotated =
                if (rotationDegrees != 0) rotate(source, rotationDegrees.toFloat()) else source
            try {
                val start = SystemClock.elapsedRealtime()
                val arr = engine.predict(rotated)
                val raw = CompositionBox(arr[0], arr[1], arr[2], arr[3])
                val score = scoreEngine.score(rotated)
                val elapsed = SystemClock.elapsedRealtime() - start

                val alpha = motion.smoothingAlpha(BASE_SMOOTHING)
                val box = smoothedBox?.lerpTo(raw, alpha) ?: raw
                smoothedBox = box

                _live.value = LiveResult(
                    box = box,
                    guidance = GuidanceCalculator.compute(box),
                    score = score,
                    inferenceMs = elapsed,
                    steady = motion.isSteady
                )
            } catch (t: Throwable) {
                Log.w(TAG, "推理失败", t)
            } finally {
                if (rotated !== source) rotated.recycle()
                source.recycle()
            }
        }
    }

    /** 拍后分析：从相册选图，出建议裁剪框与美学评分 */
    fun analyzeStatic(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = loadBitmap(app, uri)
            if (bitmap == null) {
                _message.value = "读取图片失败"
                return@launch
            }
            val engine = composition
            val scoreEngine = scorer
            if (engine == null || scoreEngine == null) {
                _message.value = "模型尚未就绪"
                bitmap.recycle()
                return@launch
            }
            try {
                val arr = engine.predict(bitmap)
                val box = CompositionBox(arr[0], arr[1], arr[2], arr[3])
                val score = scoreEngine.score(bitmap)
                _analysis.value = AnalysisResult(bitmap, box, GuidanceCalculator.compute(box), score)
            } catch (t: Throwable) {
                Log.w(TAG, "分析失败", t)
                _message.value = "分析失败：${t.message}"
                bitmap.recycle()
            }
        }
    }

    fun clearAnalysis() {
        val old = _analysis.value
        _analysis.value = null
        old?.bitmap?.recycle()
    }

    /** 真机延迟实测 */
    fun runBenchmark(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val engine = composition
            val scoreEngine = scorer
            if (engine == null || scoreEngine == null) {
                onDone("模型尚未就绪，请稍候重试")
                return@launch
            }
            val probe = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            probe.eraseColor(android.graphics.Color.GRAY)
            try {
                repeat(5) {
                    engine.predict(probe)
                    scoreEngine.score(probe)
                }

                var t1 = 0L
                repeat(30) {
                    val a = System.nanoTime()
                    engine.predict(probe)
                    t1 += System.nanoTime() - a
                }
                val compositionMs = t1 / 30f / 1_000_000f

                var t2 = 0L
                repeat(15) {
                    val a = System.nanoTime()
                    scoreEngine.score(probe)
                    t2 += System.nanoTime() - a
                }
                val scoreMs = t2 / 15f / 1_000_000f

                val total = compositionMs + scoreMs
                val fps = if (total > 0f) 1000f / total else 0f

                onDone(
                    buildString {
                        appendLine("设备：${DeviceTierDetector.describe()}")
                        appendLine("档位：${tier.label}")
                        appendLine()
                        appendLine("构图模型 Adacrop (ONNX)")
                        appendLine("  %.2f ms / 帧".format(compositionMs))
                        appendLine()
                        appendLine("美学评分 NIMA (TFLite)")
                        appendLine("  %.2f ms / 帧".format(scoreMs))
                        appendLine()
                        appendLine("两者串行 %.2f ms → 理论上限 %.1f fps".format(total, fps))
                        appendLine("当前抽帧间隔：%d ms".format(tier.frameIntervalMs))
                    }
                )
            } finally {
                probe.recycle()
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        motion.stop()
        composition?.close()
        scorer?.close()
        _analysis.value?.bitmap?.recycle()
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun loadBitmap(context: Context, uri: Uri, maxSize: Int = 1280): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            var scale = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / scale > maxSize) scale *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val rotation = readRotation(context, uri)
            if (rotation != 0) {
                val rotated = rotate(bitmap, rotation.toFloat())
                if (rotated !== bitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载图片失败", e)
            null
        }
    }

    private fun readRotation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val orientation = ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private const val TAG = "CompositionViewModel"
        private const val BASE_SMOOTHING = 0.25f
    }
}
