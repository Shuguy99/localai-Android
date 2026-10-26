package dev.localai.agent.inference

import java.io.File

/**
 * Big-core detection for big.LITTLE / tri-cluster SoCs.
 *
 * `Runtime.availableProcessors()` counts every core, and spawning one llama.cpp
 * thread per core drags decode onto the little cluster where each sync step
 * waits for the slowest participant. Counting only cores whose maximum frequency
 * is within 80% of the fastest cluster returns the number of "prime/gold" class
 * cores, which is the practical optimum for both prefill and decode. Examples:
 *   - Snapdragon 865 (1+3×2.42 + 4×1.8 GHz): big = 4, not 8.
 *   - Helio G99 (2×2.2 + 6×2.0 GHz): both clusters count, big = 8.
 *   - Homogeneous SoCs: all cores count.
 *
 * [bigCoreCount] is pure and unit-tested; [detectBigCoreCount] wraps the sysfs
 * read and falls back to the historical cores/2 heuristic when the files are
 * unavailable (emulators, exotic kernels).
 */
object CpuTopology {

    /** Cores running at 80%+ of the fastest core's max frequency. */
    fun bigCoreCount(cpuMaxFreqKHz: Map<Int, Int>): Int {
        val total = cpuMaxFreqKHz.size
        if (total == 0) return 0
        val fastest = cpuMaxFreqKHz.values.max()
        val threshold = fastest.toLong() * 80L / 100L
        return cpuMaxFreqKHz.values.count { it.toLong() >= threshold }
    }

    /** Reads /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq for all cores. */
    fun readCpuMaxFreqs(cores: Int): Map<Int, Int> = buildMap {
        for (i in 0 until cores) {
            val freq = runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toIntOrNull()
            }.getOrNull()
            if (freq != null && freq > 0) put(i, freq)
        }
    }

    /** Big-core count, or the classic cores/2 fallback if sysfs gives nothing. */
    fun detectBigCoreCount(availableProcessors: Int): Int {
        if (availableProcessors <= 0) return 2
        val big = bigCoreCount(readCpuMaxFreqs(availableProcessors))
        if (big > 0) return big.coerceIn(1, availableProcessors)
        return (availableProcessors / 2).coerceIn(2, 6)
    }
}
