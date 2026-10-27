package dev.localai.agent.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ChatStore(tmp.newFolder())

    @Test
    fun `save and load round-trips a conversation`() {
        val store = store()
        val conv = Conversation(
            id = "abc",
            title = "hello",
            createdAt = 1L,
            updatedAt = 2L,
            turns = listOf(StoredTurn("USER", "hi"), StoredTurn("ASSISTANT", "hello!")),
            messages = listOf(
                StoredMessage("USER", "hi"),
                StoredMessage("TOOL", "ls -la", toolName = "exec_shell", toolStatus = "SUCCESS"),
            ),
        )
        store.save(conv)
        val loaded = store.load("abc")
        assertNotNull(loaded)
        assertEquals(conv, loaded)
    }

    @Test
    fun `load of a missing conversation is null`() {
        assertNull(store().load("nope"))
    }

    @Test
    fun `corrupted files are skipped by list and load`() {
        val store = store()
        store.save(Conversation("ok", "t", 5L, 5L, messages = listOf(StoredMessage("USER", "x"))))
        store.fileFor("broken").writeText("{ not json")
        val metas = store.list()
        assertEquals(1, metas.size)
        assertEquals("ok", metas[0].id)
        assertNull(store.load("broken"))
    }

    @Test
    fun `list sorts newest first`() {
        val store = store()
        store.save(Conversation("old", "o", 1L, 10L, emptyList(), listOf(StoredMessage("USER", "a"))))
        store.save(Conversation("new", "n", 2L, 20L, emptyList(), listOf(StoredMessage("USER", "b"))))
        assertEquals(listOf("new", "old"), store.list().map { it.id })
    }

    @Test
    fun `delete removes the file`() {
        val store = store()
        store.save(Conversation("gone", "g", 1L, 1L, emptyList(), listOf(StoredMessage("USER", "x"))))
        assertTrue(store.delete("gone"))
        assertNull(store.load("gone"))
    }

    @Test
    fun `title from first line capped at MAX_TITLE`() {
        assertEquals("hello world", ChatStore.titleFrom("hello world\nsecond line"))
        assertEquals(ChatStore.MAX_TITLE, ChatStore.titleFrom("x".repeat(100)).length)
        assertEquals("New chat", ChatStore.titleFrom("   "))
    }

    @Test
    fun `save overwrites previous state atomically and leaves no tmp behind`() {
        val store = store()
        store.save(Conversation("c", "t1", 1L, 1L, emptyList(), listOf(StoredMessage("USER", "v1"))))
        store.save(Conversation("c", "t2", 1L, 2L, emptyList(), listOf(StoredMessage("USER", "v2"))))
        assertEquals("v2", store.load("c")!!.messages.single().text)
        val dir = store.fileFor("c").parentFile!!
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(".tmp") }!!.size)
    }
}
