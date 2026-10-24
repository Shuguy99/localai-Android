package dev.localai.agent.data.model

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Pre-load memory estimate.
 *
 * weights  ~= file size (mmap still consumes page cache / resident memory while in use)
 * KV cache = 2 * layers * n_ctx * kv_heads * (embedding / heads) * 2 bytes (f16)
 *
 * We compare the total against *available* RAM (not total), because the model has to
 * coexist with Android, the container and the UI.
 */
class MemoryGuard(context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val metaReader = GgufMetaReader()

    data class Estimate(
        val weightsBytes: Long,
        val kvCacheBytes: Long,
        val totalBytes: Long,
        val availableBytes: Long,
        val totalRamBytes: Long,
        val ratio: Double,
        val meta: GgufMetaReader.Meta?,
    ) {
        enum class Level { OK, WARN, DANGER }

        val level: Level
            get() = when {
                ratio >= 0.70 -> Level.DANGER
                ratio >= 0.50 -> Level.WARN
                else -> Level.OK
            }

        val kvCacheSource: String
            get() = if (meta?.blockCount != null && meta.kvHeads != null) {
                "GGUF metadata"
            } else {
                "heuristic (metadata unavailable)"
            }
    }

    fun estimate(modelFile: File, contextSize: Int): Estimate {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val available = info.availMem.coerceAtLeast(1L)
        val totalRam = info.totalMem.coerceAtLeast(1L)

        val meta = metaReader.read(modelFile)
        val weights = (modelFile.length() * 1.02).toLong() + RUNTIME_OVERHEAD_BYTES

        // Local copies: Meta.kvHeads has a custom getter, which blocks smart casts.
        val blockCount = meta?.blockCount
        val kvHeads = meta?.kvHeads
        val headCount = meta?.headCount
        val embeddingLength = meta?.embeddingLength
        val kv = if (blockCount != null && blockCount > 0 &&
            kvHeads != null && kvHeads > 0 &&
            headCount != null && headCount > 0 &&
            embeddingLength != null && embeddingLength > 0
        ) {
            val headDim = embeddingLength / headCount
            val elements = 2L * blockCount * contextSize * kvHeads * headDim
            elements * 2L // f16 K and V
        } else {
            // Conservative fallback: ~64 KiB per context token for a 2B-4B class model.
            contextSize.toLong() * 64L * 1024L
        }

        val total = weights + kv
        return Estimate(
            weightsBytes = weights,
            kvCacheBytes = kv,
            totalBytes = total,
            availableBytes = available,
            totalRamBytes = totalRam,
            ratio = total.toDouble() / available.toDouble(),
            meta = meta,
        )
    }

    private companion object {
        const val RUNTIME_OVERHEAD_BYTES = 128L * 1024 * 1024
    }
}
