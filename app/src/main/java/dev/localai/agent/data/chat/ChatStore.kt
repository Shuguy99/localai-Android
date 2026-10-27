package dev.localai.agent.data.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persistence for chat conversations. One JSON file per conversation under
 * `filesDir/chats/`, written atomically (tmp + rename) after every completed
 * turn. Two views are stored:
 *
 * - [turns] — the strict USER/ASSISTANT alternation the prompt builder needs
 *   to continue the dialogue across process death;
 * - [messages] — the full UI transcript (tool calls, notices) so the user
 *   sees the same chat after a restart.
 */
@Serializable
data class StoredMessage(
    val role: String,
    val text: String,
    val toolName: String? = null,
    val toolStatus: String? = null,
)

@Serializable
data class StoredTurn(val role: String, val text: String)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: List<StoredTurn> = emptyList(),
    val messages: List<StoredMessage> = emptyList(),
)

data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

class ChatStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun fileFor(id: String): File = File(dir, "$id.json")

    /** Atomic write: a crash mid-save can never corrupt the previous state. */
    fun save(conversation: Conversation) {
        val tmp = File(dir, "${conversation.id}.json.tmp")
        tmp.writeText(json.encodeToString(Conversation.serializer(), conversation))
        val dest = fileFor(conversation.id)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("Could not persist conversation ${conversation.id}")
        }
    }

    fun load(id: String): Conversation? {
        val f = fileFor(id)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(Conversation.serializer(), f.readText()) }.getOrNull()
    }

    /** Newest first. Corrupted files are skipped, not fatal. */
    fun list(): List<ConversationMeta> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { f ->
                runCatching {
                    val c = json.decodeFromString(Conversation.serializer(), f.readText())
                    ConversationMeta(c.id, c.title, c.updatedAt, c.messages.size)
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    fun delete(id: String): Boolean = fileFor(id).delete()

    companion object {
        const val MAX_TITLE = 60
        const val MAX_TOOL_TEXT = 4096

        fun titleFrom(firstUserText: String): String {
            val t = firstUserText.trim().lineSequence().firstOrNull().orEmpty()
            return t.take(MAX_TITLE).ifBlank { "New chat" }
        }
    }
}
