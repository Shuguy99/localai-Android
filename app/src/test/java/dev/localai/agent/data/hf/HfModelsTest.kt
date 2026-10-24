package dev.localai.agent.data.hf

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HfModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun gatedJsonNullIsNotGated() {
        val model = json.decodeFromString<HfModel>("""{"id":"org/model","gated":null}""")
        assertFalse(model.isGated)
    }

    @Test
    fun gatedFalseAndAutoAreDistinguished() {
        val open = json.decodeFromString<HfModel>("""{"id":"a/b","gated":false}""")
        val auto = json.decodeFromString<HfModel>("""{"id":"a/b","gated":"auto"}""")
        val manual = json.decodeFromString<HfModel>("""{"id":"a/b","gated":"manual"}""")

        assertFalse(open.isGated)
        assertTrue(auto.isGated)
        assertTrue(manual.isGated)
    }

    @Test
    fun repoIdPrefersModelId() {
        val model = json.decodeFromString<HfModel>("""{"id":"org/repo","modelId":"org/model"}""")
        assertEquals("org/model", model.repoId)
    }

    @Test
    fun ggufFilesExcludeProjectionAndShards() {
        val model = json.decodeFromString<HfModel>(
            """
            {
              "id": "org/repo",
              "siblings": [
                {"rfilename": "model-Q4_K_M.gguf", "size": 2000},
                {"rfilename": "model-Q8_0.gguf", "lfs": {"size": 8000}},
                {"rfilename": "mmproj-model-f16.gguf", "size": 500},
                {"rfilename": "model-Q4_K_M-00001-of-00002.gguf", "size": 1000},
                {"rfilename": "README.md", "size": 10}
              ]
            }
            """.trimIndent(),
        )

        val files = model.ggufFiles()
        assertEquals(
            listOf("model-Q4_K_M.gguf", "model-Q8_0.gguf"),
            files.map { it.rfilename },
        )
        assertEquals(2000L, files.first().effectiveSize)
    }

    @Test
    fun lfsSizeIsUsedWhenPlainSizeIsMissing() {
        val model = json.decodeFromString<HfModel>(
            """{"id":"a/b","siblings":[{"rfilename":"m.gguf","lfs":{"size":12345}}]}""",
        )
        assertEquals(12345L, model.ggufFiles().single().effectiveSize)
    }

    @Test
    fun quantLabelRecognisesCommonQuants() {
        assertEquals("Q4_K_M", HfModel.quantLabel("Qwen2.5-3B-Instruct-Q4_K_M.gguf"))
        assertEquals("Q8_0", HfModel.quantLabel("model-q8_0.gguf"))
        assertEquals("IQ4_XS", HfModel.quantLabel("model-IQ4_XS.gguf"))
        assertEquals("F16", HfModel.quantLabel("model-f16.gguf"))
        assertEquals("BF16", HfModel.quantLabel("model-bf16.gguf"))
        assertEquals("GGUF", HfModel.quantLabel("model.gguf"))
    }
}
