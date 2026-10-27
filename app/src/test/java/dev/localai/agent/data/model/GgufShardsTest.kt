package dev.localai.agent.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufShardsTest {

    @Test
    fun `parses shard names case-insensitively`() {
        val p = GgufShards.parse("Qwen3-4B-Q4_K_M-00001-of-00002.gguf")!!
        assertEquals("Qwen3-4B-Q4_K_M", p.base)
        assertEquals(1, p.number)
        assertEquals(2, p.total)
        assertTrue(GgufShards.isShard("model-00002-of-00002.GGUF"))
    }

    @Test
    fun `single files are not shards`() {
        assertNull(GgufShards.parse("qwen2.5-3b-instruct-q4_k_m.gguf"))
        assertNull(GgufShards.parse("model-00001-of-00002.bin"))
        assertNull(GgufShards.parse("mmproj-00001-of-00002.gguf".replace("-00001-of-00002", "")))
    }

    @Test
    fun `invalid part numbering is rejected`() {
        assertNull(GgufShards.parse("m-00000-of-00002.gguf"))
        assertNull(GgufShards.parse("m-00003-of-00002.gguf"))
    }

    @Test
    fun `first shard detection`() {
        assertTrue(GgufShards.isFirstShard("m-00001-of-00003.gguf"))
        assertFalse(GgufShards.isFirstShard("m-00002-of-00003.gguf"))
    }

    @Test
    fun `complete group returns sorted part names`() {
        val names = listOf(
            "m-00003-of-00003.gguf", "m-00001-of-00003.gguf", "m-00002-of-00003.gguf", "other.gguf",
        )
        assertEquals(
            listOf("m-00001-of-00003.gguf", "m-00002-of-00003.gguf", "m-00003-of-00003.gguf"),
            GgufShards.completeGroup("m-00001-of-00003.gguf", names),
        )
    }

    @Test
    fun `incomplete group is null`() {
        val names = listOf("m-00001-of-00003.gguf", "m-00002-of-00003.gguf")
        assertNull(GgufShards.completeGroup("m-00001-of-00003.gguf", names))
        assertNull(GgufShards.completeGroup("plain.gguf", names))
    }

    @Test
    fun `remote grouping keeps only complete groups`() {
        val names = listOf(
            "a-00001-of-00002.gguf", "a-00002-of-00002.gguf",
            "b-00001-of-00003.gguf", "b-00002-of-00003.gguf", // b incomplete
        )
        val groups = GgufShards.groupRemote(names)
        assertEquals(setOf("a"), groups.keys)
        assertEquals(
            listOf("a-00001-of-00002.gguf", "a-00002-of-00002.gguf"),
            groups["a"],
        )
    }
}
