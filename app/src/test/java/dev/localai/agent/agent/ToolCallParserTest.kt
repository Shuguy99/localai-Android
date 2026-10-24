package dev.localai.agent.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun parsesFencedToolBlock() {
        val text = """
            I will inspect the workspace first.

            ```tool
            {"name":"list_dir","arguments":{"path":"/workspace"}}
            ```
        """.trimIndent()

        val call = parseOrFail(text)
        assertEquals("list_dir", call.name)
        assertEquals("/workspace", call.arguments["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun normalisesToolNameAndAliases() {
        val text = """```json
            {"tool_name":"WRITE_FILE","args":{"path":"/workspace/a.txt","content":"hi"}}
            ```"""

        val call = parseOrFail(text)
        assertEquals("write_file", call.name)
        assertEquals("hi", call.arguments["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun acceptsToolCallTags() {
        val text = """<tool_call>
            {"name":"exec_shell","arguments":{"command":"echo hello"}}
            </tool_call>"""

        val call = parseOrFail(text)
        assertEquals("exec_shell", call.name)
        assertEquals("echo hello", call.arguments["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun acceptsBareTrailingJson() {
        val call = parseOrFail("""{"name":"read_file","arguments":{"path":"/workspace/x"}}""")
        assertEquals("read_file", call.name)
    }

    @Test
    fun parsesArgumentsEncodedAsAString() {
        val text = """```tool
            {"name":"exec_shell","arguments":"{\"command\":\"pwd\"}"}
            ```"""

        val call = parseOrFail(text)
        assertEquals("pwd", call.arguments["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun ignoresUnknownTools() {
        val text = """```tool
            {"name":"delete_everything","arguments":{}}
            ```"""
        assertNull(ToolCallParser.parse(text))
    }

    @Test
    fun ignoresBracesInsideJsonStrings() {
        val text = """```tool
            {"name":"write_file","arguments":{"path":"/workspace/b.sh","content":"echo {not a block}"}}
            ```"""

        val call = parseOrFail(text)
        assertEquals("echo {not a block}", call.arguments["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun prefersLastCompleteCall() {
        val text = """
            ```tool
            {"name":"list_dir","arguments":{"path":"/workspace"}}
            ```
            Now the real one:
            ```tool
            {"name":"read_file","arguments":{"path":"/workspace/real.txt"}}
            ```
        """.trimIndent()

        val call = parseOrFail(text)
        assertEquals("read_file", call.name)
    }

    @Test
    fun keepsNonArgumentKeysForBareShape() {
        // Models sometimes flatten the arguments: {"name":"list_dir","path":"/workspace"}.
        val call = parseOrFail("""```tool
                {"name":"list_dir","path":"/workspace","recursive":false}
                ```""")
        assertEquals("/workspace", call.arguments["path"]?.jsonPrimitive?.content)
        assertEquals(false, (call.arguments["recursive"] as? JsonPrimitive)?.content?.toBoolean())
    }

    @Test
    fun returnsNullForPlainText() {
        assertNull(ToolCallParser.parse("Sure, I can help with that. No tool call here."))
    }

    private fun parseOrFail(text: String): ToolCall =
        ToolCallParser.parse(text) ?: throw AssertionError("expected a tool call in: $text")
}
