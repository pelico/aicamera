package com.pelico.aicamera.util

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * 把 CameraX 的 YUV_420_888 帧转成已旋转到屏幕方向的 ARGB Bitmap。
 *
 * 旋转必须在这里做：后面的光线判断要用到画面的上下左右关系（天空在上、光从哪侧来），
 * 不旋转的话竖屏时全反。
 */
fun ImageProxy.toRotatedBitmap(): Bitmap {
    val w = width
    val h = height
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixStride = yPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixStride = vPlane.pixelStride

    val pixels = IntArray(w * h)
    var idx = 0
    for (row in 0 until h) {
        val yRowBase = row * yRowStride
        val uvRowBase = (row shr 1)
        val uRowBase = uvRowBase * uRowStride
        val vRowBase = uvRowBase * vRowStride
        for (col in 0 until w) {
            val y = yBuf[yRowBase + col * yPixStride].toInt() and 0xFF
            val u = uBuf[uRowBase + (col shr 1) * uPixStride].toInt() and 0xFF
            val v = vBuf[vRowBase + (col shr 1) * vPixStride].toInt() and 0xFF
            val r = (y + 1.402f * (v - 128)).toInt().coerceIn(0, 255)
            val g = (y - 0.344136f * (u - 128) - 0.714136f * (v - 128)).toInt().coerceIn(0, 255)
            val b = (y + 1.772f * (u - 128)).toInt().coerceIn(0, 255)
            pixels[idx++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }

    val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    frame.setPixels(pixels, 0, w, 0, 0, w, h)

    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return frame

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(frame, 0, 0, w, h, matrix, true)
    frame.recycle()
    return rotated
}
