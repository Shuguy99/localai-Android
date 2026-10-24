package dev.localai.agent.container

import dev.localai.agent.data.settings.ContainerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cheap safety net: before the agent touches the filesystem we snapshot /workspace
 * with the container's own tar. Snapshots are capped and pruned; they are not a
 * substitute for version control, but they make a wrong `rm` recoverable.
 */
class SnapshotStore(
    private val container: ContainerManager,
    private val snapshotsDir: File = container.snapshotsDir,
) {
    data class Snapshot(val name: String, val sizeBytes: Long, val createdAt: Long)

    fun list(): List<Snapshot> = (snapshotsDir.listFiles { f -> f.isFile && f.name.endsWith(".tgz") }
        ?: emptyArray())
        .sortedByDescending { it.lastModified() }
        .map { Snapshot(it.name, it.length(), it.lastModified()) }

    suspend fun create(settings: ContainerSettings, label: String = "turn"): Result<Snapshot> =
        withContext(Dispatchers.IO) {
            snapshotsDir.mkdirs()
            prune()
            val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
            val name = "snap-${System.currentTimeMillis()}-$safeLabel.tgz"
            val result = container.exec(
                command = "tar -czf /snapshots/$name -C /workspace . 2>/dev/null",
                settings = settings,
                workdir = "/",
                timeoutOverrideSeconds = 120,
            )
            if (!result.ok) {
                return@withContext Result.failure(
                    IllegalStateException("Snapshot failed: ${result.output.take(300)}"),
                )
            }
            val file = File(snapshotsDir, name)
            if (!file.isFile || file.length() == 0L) {
                file.delete()
                return@withContext Result.failure(IllegalStateException("Snapshot archive was empty."))
            }
            if (file.length() > MAX_SNAPSHOT_BYTES) {
                file.delete()
                return@withContext Result.failure(
                    IllegalStateException("Workspace is too large to snapshot (> ${MAX_SNAPSHOT_BYTES / 1024 / 1024} MB)."),
                )
            }
            prune()
            Result.success(Snapshot(name, file.length(), file.lastModified()))
        }

    suspend fun restore(name: String, settings: ContainerSettings): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!SAFE_NAME.matches(name)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid snapshot name"))
            }
            if (!File(snapshotsDir, name).isFile) {
                return@withContext Result.failure(IllegalArgumentException("No such snapshot: $name"))
            }
            val result = container.exec(
                command = "rm -rf /workspace/* /workspace/.[!.]* 2>/dev/null; " +
                    "tar -xzf /snapshots/$name -C /workspace",
                settings = settings,
                workdir = "/",
                timeoutOverrideSeconds = 120,
            )
            if (result.ok) Result.success(Unit)
            else Result.failure(IllegalStateException("Restore failed: ${result.output.take(300)}"))
        }

    fun delete(name: String): Boolean {
        if (!SAFE_NAME.matches(name)) return false
        return File(snapshotsDir, name).delete()
    }

    private fun prune() {
        list().drop(MAX_SNAPSHOTS).forEach { File(snapshotsDir, it.name).delete() }
    }

    companion object {
        const val MAX_SNAPSHOTS = 5
        const val MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,120}")
    }
}
