package dev.localai.agent.agent

import dev.localai.agent.container.ContainerManager
import dev.localai.agent.data.settings.ContainerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException

data class ToolResult(
    val ok: Boolean,
    val output: String,
    val truncated: Boolean = false,
)

class ToolException(message: String) : IOException(message)

/**
 * The four tools exposed to the model. File paths are jailed to /workspace and
 * /shared; `exec_shell` is deliberately more powerful (that is the point of the
 * container) and is protected by timeout, output cap, a soft deny-list and the
 * per-turn workspace snapshot.
 */
class ToolExecutor(private val container: ContainerManager) {

    /** Null when the container can run; otherwise a human-readable reason. */
    fun containerIssue(settings: ContainerSettings): String? = container.preflight(settings)

    suspend fun execute(call: ToolCall, settings: ContainerSettings): ToolResult = withContext(Dispatchers.IO) {
        // File tools operate on the guest rootfs, so they are just as unavailable as
        // exec_shell when the container is missing or this build cannot exec it.
        container.preflight(settings)?.let { reason ->
            return@withContext ToolResult(false, "error: $reason")
        }
        try {
            when (call.name) {
                "read_file" -> readFile(call.arguments)
                "write_file" -> writeFile(call.arguments)
                "list_dir" -> listDir(call.arguments)
                "exec_shell" -> execShell(call.arguments, settings)
                else -> ToolResult(false, "error: unknown tool '${call.name}'")
            }
        } catch (e: ToolException) {
            ToolResult(false, "error: ${e.message}")
        } catch (e: Exception) {
            ToolResult(false, "error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readFile(args: JsonObject): ToolResult {
        val path = stringArg(args, "path") ?: throw ToolException("read_file requires 'path'")
        val maxBytes = intArg(args, "max_bytes", MAX_READ).coerceIn(1, MAX_READ)
        val file = resolve(path)
        if (!file.exists()) throw ToolException("no such file: $path")
        if (file.isDirectory) throw ToolException("is a directory: $path. Use list_dir.")
        if (file.length() > maxBytes * 8) {
            // Do not even read huge binaries into memory.
            throw ToolException(
                "file is ${file.length()} bytes, refusing to read more than ${maxBytes * 8}. " +
                    "Use exec_shell with head/tail to inspect it.",
            )
        }
        val bytes = file.inputStream().use { input ->
            val buf = ByteArray(maxBytes)
            val n = input.read(buf)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        }
        if (bytes.any { it == 0.toByte() }) {
            return ToolResult(true, "binary file ($path, ${file.length()} bytes) — not displayed")
        }
        val text = String(bytes, Charsets.UTF_8)
        val truncated = file.length() > bytes.size
        return ToolResult(
            true,
            buildString {
                if (truncated) append("[truncated to ${bytes.size} of ${file.length()} bytes]\n")
                append(text)
            },
            truncated,
        )
    }

    private fun writeFile(args: JsonObject): ToolResult {
        val path = stringArg(args, "path") ?: throw ToolException("write_file requires 'path'")
        val content = stringArg(args, "content") ?: throw ToolException("write_file requires 'content'")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE) {
            throw ToolException("content exceeds ${MAX_WRITE / 1024} KiB; split the file into smaller writes")
        }
        val file = resolve(path)
        if (file.isDirectory) throw ToolException("is a directory: $path")
        val createDirs = booleanArg(args, "create_dirs", true)
        if (createDirs) file.parentFile?.mkdirs() else if (file.parentFile?.isDirectory != true) {
            throw ToolException("parent directory does not exist: ${file.parentFile?.name}")
        }
        file.writeText(content, Charsets.UTF_8)
        return ToolResult(true, "wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path")
    }

    private fun listDir(args: JsonObject): ToolResult {
        val path = stringArg(args, "path") ?: "/workspace"
        val depth = intArg(args, "depth", 1).coerceIn(1, 3)
        val root = resolve(path)
        if (!root.exists()) throw ToolException("no such directory: $path")
        if (!root.isDirectory) throw ToolException("not a directory: $path")

        val lines = mutableListOf<String>()
        var truncated = false
        val visited = HashSet<String>()
        val allowedRoot = root.canonicalFile

        fun walk(dir: File, prefix: String, level: Int, boundary: File) {
            if (lines.size >= MAX_LIST) { truncated = true; return }
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
            if (!visited.add(canonical)) return
            val children = dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
            ) ?: return
            for (child in children) {
                if (lines.size >= MAX_LIST) { truncated = true; break }
                // Do not follow a symlink that leaves the allowed subtree.
                val canonicalChild = runCatching { child.canonicalPath }.getOrNull() ?: continue
                if (canonicalChild != boundary.path &&
                    !canonicalChild.startsWith(boundary.path + File.separator)
                ) {
                    continue
                }
                val isDir = child.isDirectory
                lines += "${if (isDir) "d" else "-"} ${formatSize(child.length()).padStart(8)}  $prefix${child.name}${if (isDir) "/" else ""}"
                if (isDir && level < depth) walk(child, "$prefix${child.name}/", level + 1, boundary)
            }
        }

        walk(root, "", 1, allowedRoot)
        return ToolResult(
            true,
            buildString {
                append("$path (depth=$depth)\n")
                if (lines.isEmpty()) append("(empty)\n")
                lines.forEach { append(it).append('\n') }
                if (truncated) append("[listing truncated at $MAX_LIST entries]\n")
            },
            truncated,
        )
    }

    private suspend fun execShell(args: JsonObject, settings: ContainerSettings): ToolResult {
        val command = stringArg(args, "command") ?: throw ToolException("exec_shell requires 'command'")
        if (command.length > MAX_SHELL) throw ToolException("command is too long (max $MAX_SHELL chars)")
        CommandPolicy.rejection(command)?.let { return ToolResult(false, "refused: $it") }
        val timeout = intArg(args, "timeout_sec", settings.timeoutSeconds).coerceIn(2, 300)
        val result = withContext(Dispatchers.IO) {
            container.exec(command, settings, timeoutOverrideSeconds = timeout)
        }
        val head = if (result.timedOut) {
            "timed out after ${timeout}s\n"
        } else {
            "exit=${result.exitCode} in ${result.durationMs}ms\n"
        }
        val body = result.output.ifBlank { "(no output)" }
        return ToolResult(
            ok = result.ok,
            output = head + body + if (result.truncated) "\n[output truncated]" else "",
            truncated = result.truncated,
        )
    }

    /**
     * Maps an absolute guest path to the host file inside the rootfs, rejecting
     * escapes via `..` or symlinks. Only /workspace and /shared are writable.
     */
    private fun resolve(guestPath: String): File {
        val path = guestPath.trim()
        if (!path.startsWith("/")) throw ToolException("path must be absolute (got '$path')")
        val allowedPrefix = listOf("/workspace", "/shared").firstOrNull {
            path == it || path.startsWith("$it/")
        } ?: throw ToolException("path must be inside /workspace or /shared")

        // /shared is a PRoot bind mount of a separate host directory, not a
        // subdirectory of alpineRootfs. Resolve each prefix to its real backing dir.
        val allowedRoot = when (allowedPrefix) {
            "/workspace" -> File(container.alpineRootfs, "workspace").canonicalFile
            else -> container.sharedDir.canonicalFile
        }
        val relative = path.removePrefix(allowedPrefix).trimStart('/')
        val target = File(allowedRoot, relative).canonicalFile
        if (target.path != allowedRoot.path &&
            !target.path.startsWith(allowedRoot.path + File.separator)
        ) {
            throw ToolException("path escapes $allowedPrefix")
        }
        return target
    }

    private fun stringArg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intArg(args: JsonObject, key: String, default: Int): Int =
        (args[key] as? JsonPrimitive)?.intOrNull ?: default

    private fun booleanArg(args: JsonObject, key: String, default: Boolean): Boolean =
        (args[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)}M"
        else -> "${bytes / (1024 * 1024 * 1024)}G"
    }

    private companion object {
        const val MAX_READ = 64 * 1024
        const val MAX_WRITE = 512 * 1024
        const val MAX_LIST = 300
        const val MAX_SHELL = 8 * 1024
    }
}

/**
 * Soft deny-list. PRoot already confines damage to the app's private rootfs, but
 * these commands are never useful for the agent and usually mean it is confused.
 */
internal object CommandPolicy {
    private val patterns = listOf(
        Regex("""(^|[;&|]\s*)rm\s+-[a-zA-Z]*r[a-zA-Z]*f?[a-zA-Z]*\s+/(\s*($|[;&|]))"""),
        Regex("""\bmkfs(\.[a-z0-9]+)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\b[^\n]*\bof=/dev/""", RegexOption.IGNORE_CASE),
        Regex(""">\s*/dev/(block|sd|mmcblk)"""),
        Regex(""":\s*\(\s*\)\s*\{"""),
        Regex("""\b(shutdown|reboot|poweroff)\b"""),
        Regex("""\bchmod\s+-R\s+0*00\s+/(\s|$)"""),
    )

    fun rejection(command: String): String? {
        val normalized = command.replace(Regex("\\s+"), " ")
        for (p in patterns) {
            if (p.containsMatchIn(normalized)) {
                return "this command is blocked by the safety policy. Use /workspace and a narrower command."
            }
        }
        return null
    }
}
