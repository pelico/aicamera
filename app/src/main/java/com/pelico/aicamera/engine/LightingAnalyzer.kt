package com.pelico.aicamera.engine

import android.graphics.Bitmap
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 时段与光线的轻量判断。
 *
 * 全部用画面统计量算，不引入额外模型：
 * - 亮度均值、标准差 → 判断整体曝光与光比
 * - 上/下三分之一亮度差 → 天空与地面的关系
 * - 左/右亮度差 → 光从哪一侧来
 * - 高光占比 → 是否逆光或硬光
 * 时段用系统时间近似，不做日出日落精确计算（那需要经纬度权限，不值得）。
 */

enum class TimeOfDay(val zh: String) {
    DAWN("清晨"),
    MORNING("上午"),
    NOON("正午"),
    AFTERNOON("下午"),
    GOLDEN("黄金时刻"),
    DUSK("黄昏"),
    NIGHT("夜晚")
}

enum class LightQuality(val zh: String) {
    BACKLIT("逆光"),
    SIDE_LIT("侧光"),
    FRONT_LIT("顺光"),
    FLAT("阴天柔光"),
    LOW_LIGHT("光线不足"),
    HARSH("强光硬影")
}

data class Lighting(
    val timeOfDay: TimeOfDay,
    val quality: LightQuality,
    val brightness: Float,
    val contrast: Float,
    val sunSide: Float
) {
    /** 一句话描述，用于界面展示 */
    fun describe(): String {
        val side = when {
            sunSide > 0.1f -> "光从右侧来"
            sunSide < -0.1f -> "光从左侧来"
            else -> "光线均匀"
        }
        return "${timeOfDay.zh} · ${quality.zh} · $side"
    }
}

object LightingAnalyzer {

    private const val SAMPLE = 64

    fun analyze(bitmap: Bitmap): Lighting {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        val n = SAMPLE * SAMPLE
        val px = IntArray(n)
        small.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        if (small !== bitmap) small.recycle()

        val lum = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            lum[i] = l
            sum += l
        }
        val mean = sum / n

        var varSum = 0f
        var highlight = 0
        for (l in lum) {
            val d = l - mean
            varSum += d * d
            if (l > 235f) highlight++
        }
        val std = sqrt(varSum / n)
        val highlightRatio = highlight.toFloat() / n

        val third = SAMPLE / 3
        var topSum = 0f
        var bottomSum = 0f
        for (y in 0 until third) {
            for (x in 0 until SAMPLE) {
                topSum += lum[y * SAMPLE + x]
                bottomSum += lum[(SAMPLE - 1 - y) * SAMPLE + x]
            }
        }
        val topMean = topSum / (third * SAMPLE)
        val bottomMean = bottomSum / (third * SAMPLE)

        var leftSum = 0f
        var rightSum = 0f
        for (y in 0 until SAMPLE) {
            for (x in 0 until SAMPLE) {
                val l = lum[y * SAMPLE + x]
                if (x < SAMPLE / 2) leftSum += l else rightSum += l
            }
        }
        val half = SAMPLE * (SAMPLE / 2)
        val sunSide = ((rightSum - leftSum) / half) / 128f

        val skyBias = (topMean - bottomMean) / 128f

        val quality = when {
            mean < 48f -> LightQuality.LOW_LIGHT
            highlightRatio > 0.22f && skyBias > 0.1f -> LightQuality.BACKLIT
            highlightRatio > 0.18f -> LightQuality.HARSH
            std < 26f -> LightQuality.FLAT
            abs(sunSide) > 0.12f -> LightQuality.SIDE_LIT
            else -> LightQuality.FRONT_LIT
        }

        return Lighting(
            timeOfDay = timeOfDayNow(),
            quality = quality,
            brightness = mean,
            contrast = std,
            sunSide = sunSide
        )
    }

    fun timeOfDayNow(): TimeOfDay {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..6 -> TimeOfDay.DAWN
            in 7..10 -> TimeOfDay.MORNING
            in 11..13 -> TimeOfDay.NOON
            in 14..15 -> TimeOfDay.AFTERNOON
            in 16..17 -> TimeOfDay.GOLDEN
            in 18..19 -> TimeOfDay.DUSK
            else -> TimeOfDay.NIGHT
        }
    }
}
