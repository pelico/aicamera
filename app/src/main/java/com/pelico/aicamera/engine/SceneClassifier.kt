package com.pelico.aicamera.engine

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

data class ScenePrediction(
    val label: String,
    val prob: Float,
    val zh: String,
    val group: SceneGroup
)

/**
 * Places365-ResNet18 场景分类（LiteRT fp16，365 类，21.7 MB）。
 *
 * 预处理必须与模型卡一致：中心裁剪正方形 → 224×224 → /255 → ImageNet 归一化 → NCHW。
 * 注意这里**要**做归一化，和 Adacrop 那个只做 /255 的模型不同，别混用。
 *
 * 场景变化很慢，实际使用只需 1 fps 左右，所以延迟完全不是瓶颈。
 */
class SceneClassifier(context: Context) {

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val output: Array<FloatArray>

    val labels: List<String>

    init {
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

        interpreter = Interpreter(
            modelBuffer,
            Interpreter.Options().apply { setNumThreads(INFER_THREADS) }
        )

        val outShape = interpreter.getOutputTensor(0).shape()
        output = Array(1) { FloatArray(outShape[1]) }
        inputBuffer = ByteBuffer.allocateDirect(4 * 3 * SIZE * SIZE)
            .order(ByteOrder.nativeOrder())

        labels = SceneLabels.parse(
            context.assets.open(LABEL_FILE).bufferedReader().use { it.readText() }
        )
    }

    /** @return 概率从高到低的 top-k 场景预测 */
    fun classify(bitmap: Bitmap, topK: Int = 5): List<ScenePrediction> {
        val square = centerCrop(bitmap)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== square) scaled.recycle()
        if (square !== bitmap) square.recycle()

        inputBuffer.rewind()
        for (c in 0..2) {
            val shift = 16 - 8 * c
            val mean = MEAN[c]
            val std = STD[c]
            for (p in pixels) {
                val v = ((p shr shift) and 0xFF) / 255f
                inputBuffer.putFloat((v - mean) / std)
            }
        }

        interpreter.run(inputBuffer, output)

        val logits = output[0]
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0f
        val probs = FloatArray(logits.size)
        for (i in logits.indices) {
            val e = exp((logits[i] - max).toDouble()).toFloat()
            probs[i] = e
            sum += e
        }
        for (i in probs.indices) probs[i] /= sum

        val n = labels.size.coerceAtMost(probs.size)
        return probs.indices
            .filter { it < n }
            .sortedByDescending { probs[it] }
            .take(topK)
            .map { i ->
                val label = labels[i]
                ScenePrediction(
                    label = label,
                    prob = probs[i],
                    zh = SceneLabels.zh(label),
                    group = SceneLabels.groupOf(label)
                )
            }
    }

    /** 中心裁剪成正方形，避免直接拉伸破坏比例影响分类准确率 */
    private fun centerCrop(source: Bitmap): Bitmap {
        val s = minOf(source.width, source.height)
        if (s == source.width && s == source.height) return source
        val left = (source.width - s) / 2
        val top = (source.height - s) / 2
        return Bitmap.createBitmap(source, left, top, s, s)
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        const val MODEL_FILE = "places_fp16.tflite"
        const val LABEL_FILE = "categories_places365.txt"
        const val SIZE = 224
        private const val INFER_THREADS = 4

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
