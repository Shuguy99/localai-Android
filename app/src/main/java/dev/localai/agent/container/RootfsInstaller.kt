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

        val assetList = context.assets.list("rootfs").orEmpty()
        val requestedName = assetName.substringAfterLast('/')
        val bundledName = assetList.firstOrNull {
            it == requestedName ||
                it == requestedName.removeSuffix(".gz") ||
                it == requestedName + ".gz"
        }
        if (bundledName == null) {
            throw IOException(
                "Rootfs asset not bundled. Run scripts/fetch_deps.sh to place $assetName in app/src/proot/assets/rootfs/ (proot flavor only).",
            )
        }

        var count = 0
        context.assets.open("rootfs/$bundledName").use { raw ->
            // AAPT strips the .gz suffix and stores the tarball decompressed when
            // "gz" is listed in androidResources.noCompress. Sniff the magic bytes
            // so the installer works with either packaging behaviour.
            val buffered = BufferedInputStream(raw, 64 * 1024)
            buffered.mark(2)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            val tarStream = if (b0 == 0x1f && b1 == 0x8b) {
                GzipCompressorInputStream(buffered)
            } else {
                buffered
            }
            tarStream.use { stream ->
                TarArchiveInputStream(stream).use { tar ->
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
                            onProgress((count % 5000) / 5000f, "$count entries")
                        }
                    }
                }
            }
        }

        prepareRootfs(staging)

        if (!staging.renameTo(dest)) {
            staging.deleteRecursively()
            throw IOException("Could not finalize rootfs at $dest")
        }
        onProgress(1f, "Installed")
        Result(dest, count)
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
        File(root, MARKER).writeText("alpine\n")
    }

    companion object {
        const val MARKER = ".installed"
        const val DEFAULT_ASSET = "rootfs/alpine-aarch64.tar.gz"
    }
}
