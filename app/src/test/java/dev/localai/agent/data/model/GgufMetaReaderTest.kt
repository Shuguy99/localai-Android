package dev.localai.agent.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class GgufMetaReaderTest {

    private val reader = GgufMetaReader()

    @Test
    fun readsArchitectureAndShapeFromV3Header() {
        val file = writeGguf(
            version = 3,
            entries = listOf(
                Str("general.architecture", "llama"),
                U32("llama.context_length", 8192),
                U32("llama.block_count", 28),
                U32("llama.attention.head_count", 24),
                U32("llama.attention.head_count_kv", 8),
                U32("llama.embedding_length", 3072),
                Bool("llama.use_parallel_residual", true),
                Arr("tokenizer.ggml.tokens", listOf("a", "b", "c")),
            ),
        )

        val meta = requireNotNull(reader.read(file))
        assertEquals("llama", meta.architecture)
        assertEquals(8192, meta.contextLength)
        assertEquals(28, meta.blockCount)
        assertEquals(24, meta.headCount)
        assertEquals(8, meta.headCountKv)
        assertEquals(3072, meta.embeddingLength)
        assertEquals(8, meta.kvHeads)
    }

    @Test
    fun kvHeadsFallsBackToHeadCount() {
        val file = writeGguf(
            entries = listOf(
                Str("general.architecture", "qwen2"),
                U32("qwen2.block_count", 32),
                U32("qwen2.attention.head_count", 16),
            ),
        )

        val meta = requireNotNull(reader.read(file))
        assertEquals(16, meta.headCount)
        assertNull(meta.headCountKv)
        assertEquals(16, meta.kvHeads)
    }

    @Test
    fun infersArchitectureFromBlockCountWhenGeneralKeyIsMissing() {
        val file = writeGguf(
            entries = listOf(U32("gemma2.block_count", 42)),
        )

        val meta = requireNotNull(reader.read(file))
        assertEquals("gemma2", meta.architecture)
        assertEquals(42, meta.blockCount)
    }

    @Test
    fun skipsNestedArraysWithoutLosingSync() {
        val file = writeGguf(
            entries = listOf(
                Str("general.architecture", "llama"),
                NestedArray("tokenizer.ggml.merges", listOf(listOf("a", "b"), listOf("c", "d"))),
                U32("llama.block_count", 12),
            ),
        )

        val meta = requireNotNull(reader.read(file))
        assertEquals(12, meta.blockCount)
    }

    @Test
    fun rejectsNonGgufAndTruncatedFiles() {
        val plain = File.createTempFile("not-gguf", ".bin").apply {
            writeBytes(ByteArray(64) { 0x41 })
            deleteOnExit()
        }
        assertNull(reader.read(plain))

        val empty = File.createTempFile("empty", ".bin").apply {
            writeBytes(ByteArray(0))
            deleteOnExit()
        }
        assertNull(reader.read(empty))
    }

    @Test
    fun rejectsUnsupportedVersions() {
        val file = writeGguf(version = 1, entries = listOf(Str("general.architecture", "llama")))
        assertNull(reader.read(file))
    }

    @Test
    fun rejectsImpossibleKeyCount() {
        val header = ByteArrayOutputStream().apply {
            write("GGUF".toByteArray())
            u32(3)
            u64(0)
            u64(IMPOSSIBLE_KEYS)
        }.toByteArray()
        val file = File.createTempFile("bad-count", ".gguf").apply {
            writeBytes(header)
            deleteOnExit()
        }
        assertNull(reader.read(file))
    }

    // ------------------------------------------------------------ synthetic GGUF

    private sealed interface Entry

    private data class Str(val key: String, val value: String) : Entry
    private data class U32(val key: String, val value: Int) : Entry
    private data class Bool(val key: String, val value: Boolean) : Entry
    private data class Arr(val key: String, val values: List<String>) : Entry
    private data class NestedArray(val key: String, val values: List<List<String>>) : Entry

    private fun writeGguf(version: Int = 3, entries: List<Entry>): File {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray())
        out.u32(version)
        out.u64(0) // tensor count
        out.u64(entries.size.toLong())
        for (entry in entries) {
            when (entry) {
                is Str -> {
                    out.str(entry.key)
                    out.u32(TYPE_STRING)
                    out.str(entry.value)
                }
                is U32 -> {
                    out.str(entry.key)
                    out.u32(TYPE_UINT32)
                    out.u32(entry.value)
                }
                is Bool -> {
                    out.str(entry.key)
                    out.u32(TYPE_BOOL)
                    out.write(if (entry.value) 1 else 0)
                }
                is Arr -> {
                    out.str(entry.key)
                    out.u32(TYPE_ARRAY)
                    out.u32(TYPE_STRING)
                    out.u64(entry.values.size.toLong())
                    entry.values.forEach { out.str(it) }
                }
                is NestedArray -> {
                    out.str(entry.key)
                    out.u32(TYPE_ARRAY)
                    out.u32(TYPE_ARRAY)
                    out.u64(entry.values.size.toLong())
                    for (inner in entry.values) {
                        out.u32(TYPE_STRING)
                        out.u64(inner.size.toLong())
                        inner.forEach { out.str(it) }
                    }
                }
            }
        }
        return File.createTempFile("synthetic", ".gguf").apply {
            writeBytes(out.toByteArray())
            deleteOnExit()
        }
    }

    private fun ByteArrayOutputStream.str(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        u64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.u64(value: Long) {
        for (shift in 0 until 8) write(((value ushr (shift * 8)) and 0xFF).toInt())
    }

    private companion object {
        const val IMPOSSIBLE_KEYS = 200_001L
        const val TYPE_UINT32 = 4
        const val TYPE_BOOL = 7
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
    }
}
