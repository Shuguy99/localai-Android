package dev.localai.agent.data.hf

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class HfSibling(
    val rfilename: String = "",
    val size: Long? = null,
    val lfs: HfLfs? = null,
) {
    val effectiveSize: Long?
        get() = size ?: lfs?.size
}

@Serializable
data class HfLfs(val size: Long? = null)

@Serializable
data class HfModel(
    val id: String = "",
    @SerialName("modelId") val modelId: String? = null,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    val gated: JsonElement? = null,
    val siblings: List<HfSibling> = emptyList(),
    val lastModified: String? = null,
    val private: Boolean = false,
) {
    val repoId: String get() = modelId ?: id

    val isGated: Boolean
        get() = when (val g = gated) {
            is JsonPrimitive -> if (g.isString) g.content.isNotBlank() && g.content != "false" else g.content == "true"
            null, JsonNull -> false
            else -> true
        }

    fun ggufFiles(): List<HfSibling> = siblings
        .filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
        // Projection files are GGUF too but cannot be loaded on their own.
        .filter { !it.rfilename.contains("mmproj", ignoreCase = true) }
        // Shards are offered separately through [ggufShardGroups].
        .filter { !SHARD_PATTERN.containsMatchIn(it.rfilename) }
        .sortedBy { it.effectiveSize ?: Long.MAX_VALUE }

    /**
     * Complete shard groups (`model-00001-of-00003.gguf` …) that can be
     * downloaded as one model: every part from 1..N present in the repo.
     * Groups are keyed/sorted by base name; the first part is what llama.cpp
     * gets pointed at — it discovers the siblings automatically.
     */
    fun ggufShardGroups(repoId: String = ""): List<HfShardGroup> {
        val shardNames = siblings
            .filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
            .filter { !it.rfilename.contains("mmproj", ignoreCase = true) }
            .map { it.rfilename }
        val grouped = dev.localai.agent.data.model.GgufShards.groupRemote(shardNames)
        val byName = siblings.associateBy { it.rfilename }
        return grouped.map { (base, names) ->
            val parts = names.mapNotNull { byName[it] }
            HfShardGroup(
                modelId = repoId,
                baseName = base,
                parts = parts,
                totalSize = parts.map { it.effectiveSize }.filterNotNull().takeIf { it.size == parts.size }?.sum(),
            )
        }.sortedBy { it.baseName }
    }

    companion object {
        private val SHARD_PATTERN = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)

        fun quantLabel(file: String): String {
            val upper = file.uppercase()
            val quants = listOf(
                "Q8_0", "Q6_K", "Q5_K_M", "Q5_K_S", "Q5_1", "Q5_0",
                "Q4_K_M", "Q4_K_S", "Q4_1", "Q4_0", "Q3_K_L", "Q3_K_M", "Q3_K_S",
                "Q2_K", "IQ4_XS", "IQ3_XXS", "IQ2_M", "BF16", "F16",
            )
            return quants.firstOrNull { upper.contains(it) } ?: "GGUF"
        }
    }
}

data class HfFile(
    val modelId: String,
    val fileName: String,
    val size: Long?,
    val quant: String,
)

/** A complete `-NNNNN-of-NNNNN` shard set from one repo, downloadable as one model. */
data class HfShardGroup(
    val modelId: String,
    val baseName: String,
    val parts: List<HfSibling>,
    val totalSize: Long?,
) {
    val partCount: Int get() = parts.size
    val firstFile: String get() = parts.first().rfilename
    val quant: String get() = HfModel.quantLabel(baseName)
}
