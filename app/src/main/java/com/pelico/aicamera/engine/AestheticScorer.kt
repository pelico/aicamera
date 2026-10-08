package com.pelico.aicamera.engine

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NIMA 美学评分（MobileNet，AVA 数据集，Apache-2.0）。
 *
 * 输入 [1,224,224,3] float32，预处理 x = v / 127.5 - 1（值域 [-1,1]）。
 * 输出 [1,10] 分布，得分 = Σ(i+1)·pᵢ，范围 1..10。
 */
class AestheticScorer(context: Context) {

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer

    init {
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

        val options = Interpreter.Options().apply {
            setNumThreads(INFER_THREADS)
        }
        interpreter = Interpreter(modelBuffer, options)
        inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())
    }

    /** @return 1..10 的美学评分 */
    fun score(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 127.5f - 1f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 127.5f - 1f)
            inputBuffer.putFloat((p and 0xFF) / 127.5f - 1f)
        }

        val output = Array(1) { FloatArray(10) }
        interpreter.run(inputBuffer, output)

        var score = 0f
        for (i in 0 until 10) {
            score += (i + 1) * output[0][i]
        }
        return score
    }

    fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        const val MODEL_FILE = "nima_aesthetic_fp16.tflite"
        const val INPUT_SIZE = 224
        private const val INFER_THREADS = 2
    }
}
