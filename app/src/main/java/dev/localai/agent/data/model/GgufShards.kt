package dev.localai.agent.data.model

/**
 * Pure helpers for sharded GGUF models (`model-00001-of-00003.gguf`).
 *
 * llama.cpp loads a split model by pointing it at the FIRST shard; the other
 * parts must sit in the same directory and match the `-NNNNN-of-NNNNN` pattern.
 * No Android imports here so the grouping logic stays unit-testable.
 */
object GgufShards {

    private val SPLIT = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

    data class Part(val base: String, val number: Int, val total: Int)

    /** Parses a shard file name, or returns null for a regular single-file model. */
    fun parse(fileName: String): Part? {
        val m = SPLIT.find(fileName.substringAfterLast('/')) ?: return null
        val (base, n, total) = m.destructured
        val number = n.toIntOrNull() ?: return null
        val totalInt = total.toIntOrNull() ?: return null
        if (number < 1 || totalInt < 1 || number > totalInt) return null
        return Part(base, number, totalInt)
    }

    fun isShard(fileName: String): Boolean = parse(fileName) != null

    /** The file llama.cpp must be pointed at: the `-00001-` part. */
    fun isFirstShard(fileName: String): Boolean = parse(fileName)?.number == 1

    /**
     * From a set of file names, returns the members of a complete shard group
     * (every part from 1..N present exactly once), sorted by part number —
     * or null when [firstShardName] is not a shard or the group is incomplete.
     */
    fun completeGroup(firstShardName: String, allNames: Collection<String>): List<String>? {
        val part = parse(firstShardName) ?: return null
        val group = allNames.mapNotNull { n -> parse(n)?.takeIf { it.base == part.base } }
        if (group.size != part.total) return null
        if (group.map { it.number }.distinct().size != part.total) return null
        return group.sortedBy { it.number }.map { it.number.toString().padStart(5, '0') }
            .map { "${part.base}-${it}-of-${part.total.toString().padStart(5, '0')}.gguf" }
    }

    /** Groups remote sibling file names into complete shard groups keyed by base name. */
    fun groupRemote(names: Collection<String>): Map<String, List<String>> {
        val byBase = names.mapNotNull { parse(it) }.groupBy { it.base }
        return byBase.mapNotNull { (base, parts) ->
            val total = parts.first().total
            val distinct = parts.map { it.number }.distinct()
            if (parts.size != total || distinct.size != total) return@mapNotNull null
            base to parts.sortedBy { it.number }.map { p ->
                "${p.base}-${p.number.toString().padStart(5, '0')}-of-${total.toString().padStart(5, '0')}.gguf"
            }
        }.toMap()
    }
}
