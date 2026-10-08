package com.pelico.aicamera.engine

import java.io.RandomAccessFile

/** 设备性能档位，决定实时取景的抽帧间隔 */
enum class DeviceTier(val label: String, val frameIntervalMs: Long) {
    HIGH("高档机 · 约 10 fps", 100L),
    MID("中档机 · 约 5 fps", 200L),
    LOW("入门机 · 约 3 fps", 333L)
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
