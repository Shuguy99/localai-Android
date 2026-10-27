package dev.localai.agent.container

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Extracts the Alpine minirootfs shipped as an asset.
 *
 * The tarball is extracted into a staging directory and renamed into place only
 * when complete, so an interrupted install never leaves a half-usable rootfs.
 * Symlinks and hardlinks are preserved; execute bits come from the tar mode.
 *
 * Progress is honest: the archive is scanned once to count entries, then
 * extracted while reporting extracted/total. The scan pass costs a fraction of
 * a second for the ~3.8 MB minirootfs and replaces the old cycling bar that
 * wrapped around every 5000 files.
 */
class RootfsInstaller(private val context: Context) {

    data class Result(val rootfs: File, val entries: Int)

    fun isInstalled(dest: File): Boolean = File(dest, MARKER).isFile

    suspend fun install(
        assetName: String = DEFAULT_ASSET,
        dest: File,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        if (isInstalled(dest)) return@withContext Result(dest, 0)

        val staging = File(dest.parentFile, dest.name + ".installing")
        if (staging.exists()) staging.deleteRecursively()
        if (dest.exists()) dest.deleteRecursively()
        staging.mkdirs()

        val bundledName = resolveBundledAsset(assetName)
            ?: throw IOException(
                "Rootfs asset not bundled. Run scripts/fetch_deps.sh to place $assetName in app/src/proot/assets/rootfs/ (proot flavor only).",
            )

        // Scan first so the progress bar reflects real extraction, not a guess.
        val total = runCatching { countEntries(bundledName) }.getOrDefault(0)
        val denominator = if (total > 0) total else ESTIMATED_ENTRIES

        var count = 0
        openTar(bundledName).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.replace('\\', '/').removePrefix("./")
                if (name.isBlank()) continue
                if (name.startsWith("/") || name.split('/').contains("..")) {
                    throw IOException("Unsafe path in rootfs archive: ${entry.name}")
                }

                val out = File(staging, name)
                if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw IOException("Path traversal in rootfs archive: ${entry.name}")
                }

                when {
                    entry.isDirectory -> {
                        if (!out.exists() && !out.mkdirs()) throw IOException("mkdir failed: $out")
                    }
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        if (out.exists()) out.delete()
                        Os.symlink(entry.linkName, out.absolutePath)
                    }
                    entry.isLink -> {
                        val linkName = entry.linkName.replace('\\', '/').removePrefix("./")
                        if (linkName.startsWith("/") || linkName.split('/').contains("..")) {
                            throw IOException("Unsafe hardlink in rootfs archive: ${entry.linkName}")
                        }
                        val target = File(staging, linkName)
                        out.parentFile?.mkdirs()
                        if (out.exists()) out.delete()
                        if (target.exists()) Os.link(target.absolutePath, out.absolutePath)
                    }
                    else -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> tar.copyTo(fos, 64 * 1024) }
                        val mode = entry.mode and 0x1FF
                        if (mode and 0x49 != 0) out.setExecutable(true, false)
                    }
                }

                count++
                if (count % 100 == 0) {
                    val fraction = (count.toFloat() / denominator).coerceIn(0f, 0.99f)
                    val label = if (total > 0) "$count / $total entries" else "$count entries (est. ~$denominator)"
                    onProgress(fraction, label)
                }
            }
        }

        onProgress(0.99f, "Finalizing")
        prepareRootfs(staging)

        if (!staging.renameTo(dest)) {
            staging.deleteRecursively()
            throw IOException("Could not finalize rootfs at $dest")
        }
        onProgress(1f, "Installed $count entries")
        Result(dest, count)
    }

    /** Maps a requested asset name to whatever AAPT actually bundled. */
    private fun resolveBundledAsset(assetName: String): String? {
        val assetList = context.assets.list("rootfs").orEmpty()
        val requestedName = assetName.substringAfterLast('/')
        val bundledName = assetList.firstOrNull {
            it == requestedName ||
                it == requestedName.removeSuffix(".gz") ||
                it == requestedName + ".gz"
        } ?: return null
        return bundledName
    }

    /**
     * Opens the bundled tarball. AAPT strips the .gz suffix and stores the
     * tarball decompressed when "gz" is listed in androidResources.noCompress;
     * sniff the magic bytes so the installer works with either packaging.
     */
    private fun openTar(bundledName: String): TarArchiveInputStream {
        val raw = context.assets.open("rootfs/$bundledName")
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val decompressed = if (b0 == 0x1f && b1 == 0x8b) {
            GzipCompressorInputStream(buffered)
        } else {
            buffered
        }
        return TarArchiveInputStream(decompressed)
    }

    /** Counts entries without writing anything; used for the progress denominator. */
    private fun countEntries(bundledName: String): Int {
        openTar(bundledName).use { tar ->
            var n = 0
            while (tar.nextEntry != null) n++
            return n
        }
    }

    private fun prepareRootfs(root: File) {
        listOf("workspace", "tmp", "root", "snapshots", "shared", "proc", "sys", "dev", "etc").forEach {
            File(root, it).mkdirs()
        }
        File(root, "workspace").mkdirs()
        runCatching {
            val resolv = File(root, "etc/resolv.conf")
            resolv.delete()
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        runCatching {
            val hosts = File(root, "etc/hosts")
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
        runCatching {
            val profile = File(root, "etc/profile")
            profile.appendText(
                "\nexport PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                    "export HOME=/root\nexport LANG=C.UTF-8\nexport TERM=dumb\n",
            )
        }
        runCatching {
            // apk keeps downloaded packages in /etc/apk/cache when it exists;
            // ContainerManager additionally bind-mounts a host directory over it
            // so the cache survives container resets. The community repository
            // is enabled alongside main; a stale local cache never pins apk to
            // old versions because the index decides what to download.
            File(root, "etc/apk/cache").mkdirs()
            File(root, "var/cache/apk").mkdirs()
            File(root, "etc/apk/repositories").writeText(
                "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/main\n" +
                    "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/community\n",
            )
        }
        File(root, MARKER).writeText("alpine\n")
    }

    companion object {
        const val MARKER = ".installed"
        const val DEFAULT_ASSET = "rootfs/alpine-aarch64.tar.gz"

        /** Must match the branch of [DEFAULT_ASSET] (alpine-minirootfs-3.20.3). */
        const val ALPINE_BRANCH = "v3.20"

        /** Fallback denominator when the archive scan could not count entries. */
        const val ESTIMATED_ENTRIES = 3800
    }
}
