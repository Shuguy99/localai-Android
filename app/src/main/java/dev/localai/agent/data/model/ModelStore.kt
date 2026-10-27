package dev.localai.agent.data.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ModelSidecar(
    val repoId: String,
    val fileName: String,
    val quant: String,
    val sizeBytes: Long,
    val downloadedAt: Long,
)

data class InstalledModel(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val repoId: String? = null,
    val quant: String? = null,
) {
    val path: String get() = file.absolutePath
}

class ModelStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** App-specific external storage: no runtime permission, survives reinstall? (No, but removable). */
    val modelsDir: File
        get() {
            val external = context.getExternalFilesDir("models")
            val dir = external ?: File(context.filesDir, "models")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun destinationFor(repoId: String, fileName: String): File {
        val author = repoId.substringBefore('/').ifBlank { "model" }
        val safeAuthor = sanitize(author)
        val base = fileName.substringAfterLast('/')
        val safeBase = sanitize(base)
        return File(modelsDir, "${safeAuthor}__$safeBase")
    }

    fun sidecarFor(modelFile: File): File = File(modelFile.parentFile, modelFile.name + ".json")

    fun writeSidecar(modelFile: File, meta: ModelSidecar) {
        runCatching { sidecarFor(modelFile).writeText(json.encodeToString(ModelSidecar.serializer(), meta)) }
    }

    fun readSidecar(modelFile: File): ModelSidecar? {
        val f = sidecarFor(modelFile)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(ModelSidecar.serializer(), f.readText()) }.getOrNull()
    }

    fun installed(): List<InstalledModel> {
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) } ?: return emptyList()
        val models = mutableListOf<InstalledModel>()
        val shardFiles = mutableListOf<Pair<File, GgufShards.Part>>()
        for (f in files) {
            val part = GgufShards.parse(f.name)
            if (part == null) {
                models += installedFor(f)
            } else {
                shardFiles += f to part
            }
        }
        // Sharded model: one entry keyed by the -00001- part, size = sum of parts.
        for ((base, members) in shardFiles.groupBy { it.second.base }) {
            val sorted = members.sortedBy { it.second.number }
            if (sorted.none { it.second.number == 1 }) continue
            val first = sorted.first { it.second.number == 1 }.first
            models += installedFor(first).copy(sizeBytes = members.sumOf { it.first.length() })
        }
        return models.sortedByDescending { it.file.lastModified() }
    }

    private fun installedFor(f: File): InstalledModel {
        val meta = readSidecar(f)
        return InstalledModel(
            file = f,
            displayName = meta?.fileName ?: f.name,
            sizeBytes = f.length(),
            repoId = meta?.repoId,
            quant = meta?.quant,
        )
    }

    fun delete(modelFile: File): Boolean {
        // A sharded model is stored as several files; remove them together.
        val part = GgufShards.parse(modelFile.name)
        if (part != null) {
            val siblings = modelsDir.listFiles { f ->
                f.isFile && GgufShards.parse(f.name)?.base == part.base
            }.orEmpty()
            var ok = true
            for (f in siblings) {
                sidecarFor(f).delete()
                ok = f.delete() && ok
            }
            return ok
        }
        sidecarFor(modelFile).delete()
        return modelFile.delete()
    }

    fun hasRoomFor(bytes: Long): Boolean {
        val needed = bytes + (bytes / 10) + 256L * 1024 * 1024
        return modelsDir.usableSpace > needed
    }

    fun deletePartialFor(destination: File) {
        File(destination.parentFile, destination.name + ".part").delete()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
}
