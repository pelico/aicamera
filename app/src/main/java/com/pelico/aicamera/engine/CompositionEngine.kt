package com.pelico.aicamera.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.util.Collections

/**
 * Adacrop 构图裁剪模型（MobileNetV3-Small 蒸馏版，来自 LiveCompose，MIT）。
 *
 * 输入 [1,3,224,224] float32，输出 [1,4] 归一化 (cx, cy, w, h)。
 *
 * 预处理必须和训练期一致：只做 /255，**没有 ImageNet 归一化**。
 * 如果按 MobileNet 惯例减均值除方差，输出的框会全错。
 */
class CompositionEngine(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open(MODEL_FILE).readBytes()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(INTRA_OP_THREADS)
        }
        session = env.createSession(bytes, opts)
    }

    /** @return 归一化 cxcywh，值域 0..1 */
    fun predict(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, ((p shr 16) and 0xFF) / 255f)
            buffer.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            buffer.put(2 * plane + i, (p and 0xFF) / 255f)
        }
        buffer.rewind()

        val tensor = OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        return try {
            val inputs: Map<String, OnnxTensor> = Collections.singletonMap(INPUT_NAME, tensor)
            val result = session.run(inputs)
            try {
                extract(result)
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
    }

    /**
     * [1,4] 的 float 张量在 ORT Java 里是 float[][]，个别版本可能直接给 float[]，两种都兼容。
     */
    private fun extract(result: OrtSession.Result): FloatArray {
        val value = result[0].value
        return when {
            value is Array<*> && value.isNotEmpty() && value[0] is FloatArray -> value[0] as FloatArray
            value is FloatArray -> value
            else -> floatArrayOf(0.5f, 0.5f, 1f, 1f)
        }
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val MODEL_FILE = "adacrop_mnv3.onnx"
        const val INPUT_SIZE = 224
        private const val INTRA_OP_THREADS = 2
        private const val INPUT_NAME = "image"
        private const val OUTPUT_NAME = "box_cxcywh"
    }
}
