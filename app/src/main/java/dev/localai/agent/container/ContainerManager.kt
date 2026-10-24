package dev.localai.agent.container

import android.content.Context
import android.os.Build
import dev.localai.agent.BuildConfig
import dev.localai.agent.data.settings.ContainerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface ContainerState {
    data object NotInstalled : ContainerState
    data class Installing(val progress: Float, val message: String) : ContainerState
    data object Ready : ContainerState
    data class Error(val message: String) : ContainerState
}

data class ExecResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean,
    val timedOut: Boolean,
    val durationMs: Long,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * PRoot container. This is simulated root: PRoot rewrites syscalls with ptrace,
 * nothing escalates to the kernel or touches /system. It works on an unrooted
 * Android 10+ device, but the app must target SDK 28 to be allowed to exec the
 * guest binaries (see the flavor comment in app/build.gradle.kts).
 */
class ContainerManager(private val context: Context) {

    private val _state = MutableStateFlow<ContainerState>(ContainerState.NotInstalled)
    val state: StateFlow<ContainerState> = _state.asStateFlow()

    val rootfsBase: File = File(context.filesDir, "rootfs")
    val alpineRootfs: File = File(rootfsBase, "alpine")
    val snapshotsDir: File = File(context.filesDir, "snapshots").apply { mkdirs() }
    val sharedDir: File = File(context.filesDir, "shared").apply { mkdirs() }
    val prootTmpDir: File = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    private val installer = RootfsInstaller(context)

    /** Processes currently owned by exec(); Stop in the chat screen kills these. */
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()

    fun cancelActive() {
        activeProcesses.forEach { runCatching { it.destroyForcibly() } }
    }

    fun refresh() {
        _state.value = when {
            !BuildConfig.PROOT_EXEC_ALLOWED -> ContainerState.Error(
                "This build targets SDK 35, so Android blocks execution of container binaries. " +
                    "Install the -proot flavor (targetSdk 28) to use the container.",
            )
            !installer.isInstalled(alpineRootfs) -> ContainerState.NotInstalled
            !prootAvailable() -> ContainerState.Error(
                "Rootfs is installed but the PRoot binary is missing. Run scripts/fetch_deps.sh and rebuild.",
            )
            else -> ContainerState.Ready
        }
    }

    suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.PROOT_EXEC_ALLOWED) {
            val msg = "The standard flavor cannot execute the container. Use the proot flavor."
            _state.value = ContainerState.Error(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        _state.value = ContainerState.Installing(0f, "Extracting Alpine minirootfs")
        runCatching {
            installer.install(
                dest = alpineRootfs,
                onProgress = { p, m -> _state.value = ContainerState.Installing(p, m) },
            )
            refresh()
        }.onFailure {
            _state.value = ContainerState.Error(it.message ?: "Rootfs installation failed")
        }.map { }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        alpineRootfs.deleteRecursively()
        snapshotsDir.deleteRecursively()
        snapshotsDir.mkdirs()
        sharedDir.deleteRecursively()
        sharedDir.mkdirs()
        refresh()
    }

    fun preflight(settings: ContainerSettings): String? = when {
        !BuildConfig.PROOT_EXEC_ALLOWED ->
            "Container disabled: build the -proot flavor (targetSdk 28)."
        !installer.isInstalled(alpineRootfs) ->
            "Container not installed yet. Open the Container tab and install the rootfs."
        !prootAvailable() ->
            "PRoot binary missing. Run scripts/fetch_deps.sh and rebuild the APK."
        else -> null
    }

    suspend fun exec(
        command: String,
        settings: ContainerSettings,
        workdir: String = settings.workingDir,
        timeoutOverrideSeconds: Int? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        preflight(settings)?.let {
            return@withContext ExecResult(-2, it, false, false, 0)
        }
        val proot = prootBinary() ?: return@withContext ExecResult(
            -2, "PRoot binary missing.", false, false, 0,
        )

        val timeout = (timeoutOverrideSeconds ?: settings.timeoutSeconds).coerceIn(2, 600)
        val maxOutput = settings.maxOutputBytes.coerceIn(1024, 1024 * 1024)
        val script = buildString {
            if (settings.memoryLimitMb > 0) {
                append("ulimit -v ").append(settings.memoryLimitMb * 1024L).append(" 2>/dev/null; ")
            }
            append(command)
        }

        val args = mutableListOf<String>()
        args += proot.absolutePath
        args += "--kill-on-exit"
        args += "--link2symlink"
        if (settings.simulatedRoot) args += "-0"
        args += listOf("-r", alpineRootfs.absolutePath)
        args += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
        args += listOf("-b", "${snapshotsDir.absolutePath}:/snapshots")
        args += listOf("-b", "${sharedDir.absolutePath}:/shared")
        if (settings.exposeSharedStorage) {
            args += listOf("-b", "/storage/emulated/0:/mnt/host")
        }
        args += listOf("-w", workdir)
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=dumb",
            "LANG=C.UTF-8",
            "/bin/sh", "-lc", script,
        )

        val pb = ProcessBuilder(args).redirectErrorStream(true)
        fun configureEnvironment(builder: ProcessBuilder): ProcessBuilder {
            val env = builder.environment()
            env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
            env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            if (settings.noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
            env["HOME"] = context.filesDir.absolutePath
            return builder
        }
        configureEnvironment(pb)

        val process = try {
            pb.start()
        } catch (firstFailure: IOException) {
            // Some OEMs mount nativeLibraryDir noexec; canExecute() may still report
            // true because access(2) does not always consult mount flags. Retry once
            // from app-private storage, where the proot flavor (targetSdk 28) may exec.
            val fallback = copyProotToFiles(proot)
            if (fallback == null || fallback.absolutePath == proot.absolutePath) {
                return@withContext ExecResult(
                    -2, "Failed to start PRoot: ${firstFailure.message}", false, false,
                    System.currentTimeMillis() - start,
                )
            }
            args[0] = fallback.absolutePath
            try {
                val retry = ProcessBuilder(args).redirectErrorStream(true)
                configureEnvironment(retry)
                retry.start()
            } catch (secondFailure: IOException) {
                return@withContext ExecResult(
                    -2,
                    "Failed to start PRoot: ${secondFailure.message}",
                    false, false,
                    System.currentTimeMillis() - start,
                )
            }
        }

        activeProcesses.add(process)
        try {
            val output = StringBuilder()
            var truncated = false
            val reader = Thread {
                try {
                    InputStreamReader(process.inputStream, Charsets.UTF_8).use { isr ->
                        val buffer = CharArray(8192)
                        while (true) {
                            val n = isr.read(buffer)
                            if (n < 0) break
                            if (!truncated) {
                                val room = maxOutput - output.length
                                if (room <= 0) {
                                    truncated = true
                                } else if (n <= room) {
                                    output.append(buffer, 0, n)
                                } else {
                                    output.append(buffer, 0, room)
                                    truncated = true
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Process was killed; nothing else to read.
                }
            }.apply {
                name = "proot-output"
                isDaemon = true
                start()
            }

            val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            val timedOut = !finished
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            reader.join(1500)

            val exit = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-2)
            ExecResult(
                exitCode = exit,
                output = output.toString(),
                truncated = truncated,
                timedOut = timedOut,
                durationMs = System.currentTimeMillis() - start,
            )
        } finally {
            activeProcesses.remove(process)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Cheap existence check safe to call on the main thread from refresh(). */
    private fun prootAvailable(): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").isFile ||
            File(context.filesDir, "toolchain/bin/proot").isFile

    private fun prootBinary(): File? {
        val extracted = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val bundled = File(context.filesDir, "toolchain/bin/proot")

        // Refresh stale copies after an app update, or create the first copy.
        if (extracted.isFile && (!bundled.isFile || bundled.length() != extracted.length())) {
            copyProotToFiles(extracted)?.let { return it }
        }
        if (bundled.isFile && bundled.canExecute()) return bundled
        // Some OEMs mount the native library directory noexec. The proot flavor
        // targets SDK 28, so executing a copy from filesDir is permitted.
        if (extracted.isFile && extracted.canExecute()) return extracted
        if (extracted.isFile) return copyProotToFiles(extracted)
        return null
    }

    /** Copies PRoot out of nativeLibraryDir; returns null if the copy cannot run. */
    private fun copyProotToFiles(source: File): File? {
        val bundled = File(context.filesDir, "toolchain/bin/proot")
        if (runCatching { source.canonicalPath == bundled.canonicalPath }.getOrDefault(false)) {
            return null
        }
        return runCatching {
            bundled.parentFile?.mkdirs()
            source.inputStream().use { input ->
                bundled.outputStream().use { output -> input.copyTo(output) }
            }
            bundled.setExecutable(true, false)
            bundled.takeIf { it.isFile && it.canExecute() }
        }.getOrNull()
    }

    fun hostArchitectureNote(): String =
        "ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} | " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
