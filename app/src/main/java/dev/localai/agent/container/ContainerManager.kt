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
 *
 * Command execution uses a persistent shell session (see [ShellSession]): one
 * PRoot process stays alive across calls, so shell state (cwd, env) survives
 * between commands and every command after the first skips the 150-300 ms
 * ptrace startup. The legacy one-shot path is kept as a fallback for hosts
 * where the session protocol cannot start (e.g. no busybox base64).
 */
class ContainerManager(private val context: Context) {

    private val _state = MutableStateFlow<ContainerState>(ContainerState.NotInstalled)
    val state: StateFlow<ContainerState> = _state.asStateFlow()

    val rootfsBase: File = File(context.filesDir, "rootfs")
    val alpineRootfs: File = File(rootfsBase, "alpine")
    val snapshotsDir: File = File(context.filesDir, "snapshots").apply { mkdirs() }
    val sharedDir: File = File(context.filesDir, "shared").apply { mkdirs() }
    val prootTmpDir: File = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    /**
     * Host-side apk package cache, bind-mounted over /etc/apk/cache. Downloaded
     * .apk files survive container resets, so reinstalls after a reset happen
     * from local storage instead of the network. apk caches automatically when
     * that directory exists.
     */
    val apkCacheDir: File = File(context.filesDir, "apk-cache").apply { mkdirs() }

    private val installer = RootfsInstaller(context)

    /** Processes currently owned by exec(); Stop in the chat screen kills these. */
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()

    // --- persistent session bookkeeping -------------------------------------
    private val sessionLock = Any()
    private var session: ShellSession? = null
    private var sessionSignature: String? = null
    private var lastSessionUseMs: Long = 0L

    /** Sticky flag: the guest lacks busybox base64, sessions can never work here. */
    @Volatile private var sessionUnsupported = false

    fun cancelActive() {
        activeProcesses.forEach { runCatching { it.destroyForcibly() } }
        synchronized(sessionLock) {
            session?.kill()
            session = null
            sessionSignature = null
        }
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
        destroySession()
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
        destroySession()
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

        if (!sessionUnsupported) {
            val reused = obtainSession(proot, settings, workdir)
            if (reused != null) {
                try {
                    val script = ShellProtocol.withMemoryLimit(command, settings.memoryLimitMb)
                    val result = reused.run(script, timeout, maxOutput)
                    if (result.timedOut || !reused.isAlive) {
                        destroySession()
                        if (!result.timedOut && result.exitCode == -2) {
                            return@withContext result.copy(
                                output = result.output +
                                    "\n[shell session died; state lost — next command starts fresh]",
                            )
                        }
                    }
                    return@withContext result
                } catch (_: IllegalStateException) {
                    // Session died between the liveness check and run(); fall
                    // through to the one-shot path so the command still runs.
                    destroySession()
                }
            }
        }
        execOneShot(command, settings, workdir, timeout, maxOutput, proot, start)
    }

    /** Legacy path: one PRoot process per command. Kept as the session fallback. */
    private suspend fun execOneShot(
        command: String,
        settings: ContainerSettings,
        workdir: String,
        timeout: Int,
        maxOutput: Int,
        proot: File,
        start: Long,
    ): ExecResult = withContext(Dispatchers.IO) {
        val script = buildString {
            if (settings.memoryLimitMb > 0) {
                append("ulimit -v ").append(settings.memoryLimitMb * 1024L).append(" 2>/dev/null; ")
            }
            append(command)
        }
        val args = buildProotArgs(proot, settings, workdir, script)
        val (process, startError) = startProot(args, settings)
        if (process == null) {
            return@withContext ExecResult(
                -2, startError ?: "Failed to start PRoot.", false, false,
                System.currentTimeMillis() - start,
            )
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

    // --- session management --------------------------------------------------

    /**
     * Returns the live session, recreating it when the settings signature
     * changed, after an idle timeout, or when it died. Workdir is applied only
     * at session creation: afterwards the guest's own `cd` wins, which is the
     * point of a persistent shell. null means the session cannot be used and
     * the caller should fall back to one-shot execution.
     */
    private fun obtainSession(proot: File, settings: ContainerSettings, workdir: String): ShellSession? {
        val signature = sessionSignature(proot, settings, workdir)
        synchronized(sessionLock) {
            val existing = session
            if (existing != null) {
                val stale = System.currentTimeMillis() - lastSessionUseMs > SESSION_IDLE_MS
                if (signature != sessionSignature || !existing.isAlive || stale) {
                    runCatching { existing.shutdown() }
                    session = null
                    sessionSignature = null
                } else {
                    lastSessionUseMs = System.currentTimeMillis()
                    return existing
                }
            }
            val fresh = createSession(proot, settings, workdir, signature) ?: return null
            session = fresh
            sessionSignature = signature
            lastSessionUseMs = System.currentTimeMillis()
            return fresh
        }
    }

    private fun createSession(
        proot: File,
        settings: ContainerSettings,
        workdir: String,
        signature: String,
    ): ShellSession? {
        val args = buildProotArgs(proot, settings, workdir, ShellProtocol.SESSION_LOOP)
        val process = startProot(args, settings).first ?: return null
        val created = ShellSession(
            output = process.inputStream,
            input = process.outputStream,
            killFn = { runCatching { process.destroyForcibly() } },
            aliveFn = { process.isAlive },
        )
        val healthy = runCatching { created.ping() }.getOrDefault(false)
        if (!healthy) {
            if (created.sawUnsupported) sessionUnsupported = true
            runCatching { created.shutdown() }
            return null
        }
        return created
    }

    private fun destroySession() {
        synchronized(sessionLock) {
            session?.let { runCatching { it.shutdown() } }
            session = null
            sessionSignature = null
        }
    }

    private fun sessionSignature(proot: File, settings: ContainerSettings, workdir: String): String =
        listOf(
            proot.absolutePath,
            alpineRootfs.absolutePath,
            settings.simulatedRoot,
            settings.exposeSharedStorage,
            settings.noSeccomp,
            workdir,
        ).joinToString("|")

    // --- process plumbing ----------------------------------------------------

    /**
     * PRoot argument list shared by the session and one-shot paths. The last
     * three elements launch /bin/sh with [shellScript] via -c (the session
     * loop for persistent sessions, the raw command for one-shot runs).
     */
    private fun buildProotArgs(
        proot: File,
        settings: ContainerSettings,
        workdir: String,
        shellScript: String,
    ): List<String> = buildList {
        add(proot.absolutePath)
        add("--kill-on-exit")
        add("--link2symlink")
        if (settings.simulatedRoot) add("-0")
        addAll(listOf("-r", alpineRootfs.absolutePath))
        addAll(listOf("-b", "/dev", "-b", "/proc", "-b", "/sys"))
        addAll(listOf("-b", "${snapshotsDir.absolutePath}:/snapshots"))
        addAll(listOf("-b", "${sharedDir.absolutePath}:/shared"))
        addAll(listOf("-b", "${apkCacheDir.absolutePath}:/etc/apk/cache"))
        if (settings.exposeSharedStorage) {
            addAll(listOf("-b", "/storage/emulated/0:/mnt/host"))
        }
        addAll(listOf("-w", workdir))
        addAll(
            listOf(
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=dumb",
                "LANG=C.UTF-8",
                "/bin/sh", "-lc", shellScript,
            ),
        )
    }

    /** Starts PRoot, retrying once from app-private storage on noexec mounts. */
    private fun startProot(args: List<String>, settings: ContainerSettings): Pair<Process?, String?> {
        fun configure(builder: ProcessBuilder): ProcessBuilder {
            val env = builder.environment()
            env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
            env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            if (settings.noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
            env["HOME"] = context.filesDir.absolutePath
            return builder
        }
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        configure(pb)
        return try {
            pb.start() to null
        } catch (firstFailure: IOException) {
            // Some OEMs mount nativeLibraryDir noexec; canExecute() may still report
            // true because access(2) does not always consult mount flags. Retry once
            // from app-private storage, where the proot flavor (targetSdk 28) may exec.
            val fallback = copyProotToFiles(File(args[0]))
            if (fallback == null || fallback.absolutePath == args[0]) {
                return null to "Failed to start PRoot: ${firstFailure.message}"
            }
            val retryArgs = args.toMutableList().also { it[0] = fallback.absolutePath }
            val retry = ProcessBuilder(retryArgs).redirectErrorStream(true)
            configure(retry)
            try {
                retry.start() to null
            } catch (secondFailure: IOException) {
                null to "Failed to start PRoot: ${secondFailure.message}"
            }
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

    private companion object {
        /** Sessions idle longer than this are torn down and rebuilt on demand. */
        const val SESSION_IDLE_MS = 10 * 60 * 1000L
    }
}
