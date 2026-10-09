package com.pelico.aicamera.engine

import android.util.Size
import java.io.RandomAccessFile

/** 设备性能档位。上一版的 DeviceTier 是死代码，这一版由 [RuntimePolicy] 真正接管管线 */
enum class DeviceTier(val label: String) {
    HIGH("高档机"),
    MID("中档机"),
    LOW("入门机")
}

/**
 * 运行时调度策略。任务书第 3 节「低频量与高频量分离」的落地：
 *
 * - 场景变化慢 → 低频（1 fps 上下），省电
 * - 姿势要跟手 → 高频（3~10 fps），但也受档位限制
 * - 入门机直接关掉姿态层，只保留场景 + 参考线 + 站位框（确定性部分不耗 CPU）
 */
data class RuntimePolicy(
    val tier: DeviceTier,
    val sceneIntervalMs: Long,
    val poseIntervalMs: Long,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val poseEnabled: Boolean
) {
    val analysisSize: Size get() = Size(analysisWidth, analysisHeight)

    val poseFps: Float get() = if (poseEnabled && poseIntervalMs > 0) 1000f / poseIntervalMs else 0f

    fun describe(): String {
        val pose = if (poseEnabled) "姿态 %.1f fps".format(poseFps) else "姿态层已关闭"
        return "${tier.label} · 场景 %.1f fps · $pose · 分析帧 ${analysisWidth}×${analysisHeight}"
            .format(1000f / sceneIntervalMs)
    }
}

object DeviceTierDetector {

    fun detect(): DeviceTier {
        val cores = Runtime.getRuntime().availableProcessors()
        val ramGb = totalRamGb()
        return when {
            cores >= 8 && ramGb >= 6f -> DeviceTier.HIGH
            cores >= 6 && ramGb >= 3f -> DeviceTier.MID
            cores >= 4 && ramGb >= 2f -> DeviceTier.MID
            else -> DeviceTier.LOW
        }
    }

    fun policy(): RuntimePolicy = when (detect()) {
        DeviceTier.HIGH -> RuntimePolicy(
            tier = DeviceTier.HIGH,
            sceneIntervalMs = 1200L,
            poseIntervalMs = 100L,
            analysisWidth = 640,
            analysisHeight = 480,
            poseEnabled = true
        )

        DeviceTier.MID -> RuntimePolicy(
            tier = DeviceTier.MID,
            sceneIntervalMs = 1500L,
            poseIntervalMs = 200L,
            analysisWidth = 480,
            analysisHeight = 360,
            poseEnabled = true
        )

        DeviceTier.LOW -> RuntimePolicy(
            tier = DeviceTier.LOW,
            sceneIntervalMs = 2000L,
            poseIntervalMs = 0L,
            analysisWidth = 320,
            analysisHeight = 240,
            poseEnabled = false
        )
    }

    fun describe(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        return "${cores} 核 / %.1f GB".format(totalRamGb())
    }

    private fun totalRamGb(): Float {
        return try {
            RandomAccessFile("/proc/meminfo", "r").use { file ->
                val line = file.readLine() ?: return 4f
                val kb = line.filter { it.isDigit() }.toLongOrNull() ?: return 4f
                kb / 1024f / 1024f
            }
        } catch (e: Exception) {
            4f
        }
    }
}
