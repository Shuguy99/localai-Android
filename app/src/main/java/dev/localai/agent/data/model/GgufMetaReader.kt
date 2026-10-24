package dev.localai.agent.data.model

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Small, dependency-free GGUF metadata reader.
 *
 * We only need four numbers to estimate the KV-cache before the model is loaded:
 * block_count, embedding_length, attention.head_count and head_count_kv.
 * Everything else (tensor data, tokenizer arrays) is skipped without allocation.
 *
 * Format reference: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md
 */
class GgufMetaReader {

    data class Meta(
        val architecture: String? = null,
        val contextLength: Int? = null,
        val blockCount: Int? = null,
        val headCount: Int? = null,
        val headCountKv: Int? = null,
        val embeddingLength: Int? = null,
    ) {
        val kvHeads: Int? get() = headCountKv ?: headCount
    }

    fun read(file: File): Meta? {
        if (!file.isFile || file.length() < 32) return null
        return runCatching {
            FileInputStream(file).use { fis ->
                val reader = LeReader(BufferedInputStream(fis, 64 * 1024))
                val magic = reader.readBytes(4)
                if (String(magic, Charsets.US_ASCII) != "GGUF") return null
                val version = reader.u32()
                if (version < 2 || version > 3) return null
                reader.u64() // tensor count
                val kvCount = reader.u64()
                if (kvCount <= 0 || kvCount > MAX_KEYS) return null

                val scalars = HashMap<String, Long>(64)
                var architectureName: String? = null

                var i = 0L
                while (i < kvCount && reader.position < MAX_METADATA_BYTES) {
                    val key = reader.string(MAX_STRING_BYTES) ?: return null
                    val type = reader.u32().toInt()
                    when {
                        type == TYPE_STRING -> {
                            val v = reader.string(MAX_STREAM_BYTES)
                                ?: return null
                            if (key == "general.architecture") architectureName = v
                        }
                        type == TYPE_ARRAY -> {
                            if (!reader.skipArray()) return null
                        }
                        type == TYPE_BOOL -> scalars[key] = reader.u8()
                        type in SCALAR_TYPES -> scalars[key] = reader.scalar(type)
                        else -> return null
                    }
                    i++
                }

                val arch = architectureName ?: scalars.keys
                    .firstOrNull { it.endsWith(".block_count") }
                    ?.substringBefore('.')
                if (arch == null) return Meta()

                Meta(
                    architecture = arch,
                    contextLength = longToInt(scalars["$arch.context_length"]),
                    blockCount = longToInt(scalars["$arch.block_count"]),
                    headCount = longToInt(scalars["$arch.attention.head_count"]),
                    headCountKv = longToInt(scalars["$arch.attention.head_count_kv"]),
                    embeddingLength = longToInt(scalars["$arch.embedding_length"]),
                )
            }
        }.getOrNull()
    }

    private class LeReader(private val input: InputStream) {
        var position: Long = 0
            private set

        fun u8(): Long {
            val v = input.read()
            if (v < 0) throw java.io.EOFException()
            position++
            return v.toLong()
        }

        fun u16(): Long = u8() or (u8() shl 8)
        fun u32(): Long = u16() or (u16() shl 16)
        fun u64(): Long {
            var value = 0L
            for (shift in 0 until 8) value = value or (u8() shl (shift * 8))
            return value
        }

        fun i32(): Int = u32().toInt()
        fun f32(): Float = Float.fromBits(i32())
        fun f64(): Double = Double.fromBits(u64())

        fun readBytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val read = input.read(out, off, n - off)
                if (read < 0) throw java.io.EOFException()
                off += read
            }
            position += n
            return out
        }

        fun skipFully(n: Long) {
            var left = n
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read < 0) throw java.io.EOFException()
                left -= read
                position += read
            }
        }

        fun string(max: Long): String? {
            val length = u64()
            if (length < 0 || length > max) return null
            return String(readBytes(length.toInt()), Charsets.UTF_8)
        }

        fun scalar(type: Int): Long = when (type) {
            TYPE_UINT8 -> u8()
            TYPE_INT8 -> u8().toByte().toLong()
            TYPE_UINT16 -> u16()
            TYPE_INT16 -> u16().toShort().toLong()
            TYPE_UINT32 -> u32()
            TYPE_INT32 -> i32().toLong()
            TYPE_FLOAT32 -> f32().toLong() // only used for integer metadata; never queried
            TYPE_UINT64, TYPE_INT64 -> u64()
            TYPE_FLOAT64 -> f64().toLong()
            else -> 0L
        }

        fun skipArray(): Boolean {
            val elementType = u32().toInt()
            val count = u64()
            if (count < 0 || count > MAX_ARRAY_ELEMENTS) return false
            var i = 0L
            while (i < count) {
                when (elementType) {
                    TYPE_STRING -> {
                        val len = u64()
                        if (len < 0 || len > MAX_STREAM_BYTES) return false
                        skipFully(len)
                    }
                    TYPE_ARRAY -> if (!skipArray()) return false
                    TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skipFully(1)
                    TYPE_UINT16, TYPE_INT16 -> skipFully(2)
                    TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skipFully(4)
                    TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skipFully(8)
                    else -> return false
                }
                i++
            }
            return true
        }
    }

    companion object {
        /** GGUF integers are unsigned; silently drop values that do not fit an Int. */
        private fun longToInt(value: Long?): Int? =
            value?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

        private const val MAX_KEYS = 200_000L
        private const val MAX_ARRAY_ELEMENTS = 100_000_000L
        private const val MAX_STRING_BYTES = 1L shl 20
        private const val MAX_STREAM_BYTES = 64L shl 20
        private const val MAX_METADATA_BYTES = 64L shl 20

        private const val TYPE_UINT8 = 0
        private const val TYPE_INT8 = 1
        private const val TYPE_UINT16 = 2
        private const val TYPE_INT16 = 3
        private const val TYPE_UINT32 = 4
        private const val TYPE_INT32 = 5
        private const val TYPE_FLOAT32 = 6
        private const val TYPE_BOOL = 7
        private const val TYPE_STRING = 8
        private const val TYPE_ARRAY = 9
        private const val TYPE_UINT64 = 10
        private const val TYPE_INT64 = 11
        private const val TYPE_FLOAT64 = 12

        private val SCALAR_TYPES = setOf(
            TYPE_UINT8, TYPE_INT8, TYPE_UINT16, TYPE_INT16,
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32, TYPE_UINT64,
            TYPE_INT64, TYPE_FLOAT64,
        )
    }
}
