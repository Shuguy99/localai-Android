package dev.localai.agent.ui.chat

import dev.localai.agent.ui.chat.AssistantSegment as Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextTest {

    private fun parse(s: String) = AssistantText.parse(s)

    @Test
    fun `plain prose stays a single text segment`() {
        val out = parse("Hello, world!")
        assertEquals(listOf<Seg>(Seg.Text("Hello, world!")), out)
    }

    @Test
    fun `empty and blank input produce no segments`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   \n\t ").isEmpty())
    }

    @Test
    fun `closed think block at start`() {
        val out = parse("<think>pondering</think>The answer is 42.")
        assertEquals(2, out.size)
        assertEquals(Seg.Thinking("pondering", open = false), out[0])
        assertEquals(Seg.Text("The answer is 42."), out[1])
    }

    @Test
    fun `closed think block at end`() {
        val out = parse("Let me check.<think>hmm</think>")
        assertEquals(2, out.size)
        assertEquals(Seg.Text("Let me check."), out[0])
        assertEquals(Seg.Thinking("hmm", open = false), out[1])
    }

    @Test
    fun `think block in the middle`() {
        val out = parse("Before<think>reasoning</think>After")
        assertEquals(3, out.size)
        assertEquals(Seg.Text("Before"), out[0])
        assertEquals(Seg.Thinking("reasoning", open = false), out[1])
        assertEquals(Seg.Text("After"), out[2])
    }

    @Test
    fun `unclosed think block is an open segment`() {
        val out = parse("Prefix<think>still streaming")
        assertEquals(2, out.size)
        assertEquals(Seg.Text("Prefix"), out[0])
        assertEquals(Seg.Thinking("still streaming", open = true), out[1])
    }

    @Test
    fun `bare think tag with no body yet streams as open empty`() {
        val out = parse("<think>")
        assertEquals(listOf<Seg>(Seg.Thinking("", open = true)), out)
    }

    @Test
    fun `think tags are case insensitive`() {
        val out = parse("<THINK>loud</THINK>done")
        assertEquals(2, out.size)
        assertEquals(Seg.Thinking("loud", open = false), out[0])
        assertEquals(Seg.Text("done"), out[1])
    }

    @Test
    fun `tool fence with name is extracted`() {
        val raw = "Working on it.\n```tool\n{\"name\":\"exec_shell\",\"arguments\":{\"command\":\"ls\"}}\n```"
        val out = parse(raw)
        assertEquals(2, out.size)
        assertEquals(Seg.Text("Working on it."), out[0])
        val call = out[1] as Seg.ToolCall
        assertEquals("exec_shell", call.name)
        assertEquals(false, call.open)
        assertTrue(call.json.contains("exec_shell"))
    }

    @Test
    fun `unclosed tool fence streams as open`() {
        val raw = "```tool\n{\"name\":\"write_file\","
        val out = parse(raw)
        assertEquals(1, out.size)
        val call = out[0] as Seg.ToolCall
        assertTrue(call.open)
        assertEquals("write_file", call.name)
    }

    @Test
    fun `tool fence without a parseable name yields null`() {
        val raw = "```tool\nnot json at all\n```"
        val out = parse(raw)
        val call = out[0] as Seg.ToolCall
        assertNull(call.name)
        assertEquals(false, call.open)
    }

    @Test
    fun `bare tool fence header with no newline streams as open`() {
        val out = parse("```tool")
        assertEquals(listOf<Seg>(Seg.ToolCall("", null, open = true)), out)
    }

    @Test
    fun `code fence keeps its language`() {
        val out = parse("Run this:\n```python\nprint('hi')\n```\nDone.")
        assertEquals(3, out.size)
        assertEquals(Seg.Text("Run this:"), out[0])
        assertEquals(Seg.Code("python", "print('hi')", open = false), out[1])
        assertEquals(Seg.Text("Done."), out[2])
    }

    @Test
    fun `code fence without language`() {
        val out = parse("```\nplain code\n```")
        assertEquals(listOf<Seg>(Seg.Code("", "plain code", open = false)), out)
    }

    @Test
    fun `unclosed code fence streams as open`() {
        val out = parse("```kotlin\nval x = 1")
        assertEquals(listOf<Seg>(Seg.Code("kotlin", "val x = 1", open = true)), out)
    }

    @Test
    fun `fences inside thinking stay part of the thinking body`() {
        val raw = "<think>maybe\n```python\nx=1\n```\nok</think>Answer"
        val out = parse(raw)
        assertEquals(2, out.size)
        val thinking = out[0] as Seg.Thinking
        assertTrue(thinking.text.contains("```python"))
        assertEquals(false, thinking.open)
        assertEquals(Seg.Text("Answer"), out[1])
    }

    @Test
    fun `mixed content full agent reply`() {
        val raw = "<think>check files first</think>" +
            "Let me look at the directory.\n" +
            "```tool\n{\"name\":\"list_dir\",\"arguments\":{\"path\":\"/workspace\"}}\n```"
        val out = parse(raw)
        assertEquals(3, out.size)
        assertEquals(Seg.Thinking("check files first", open = false), out[0])
        assertEquals(Seg.Text("Let me look at the directory."), out[1])
        assertEquals("list_dir", (out[2] as Seg.ToolCall).name)
    }

    @Test
    fun `crlf line endings are normalised`() {
        val out = parse("A\r\n```sh\r\necho hi\r\n```\r\nB")
        assertEquals(3, out.size)
        assertEquals(Seg.Text("A"), out[0])
        assertEquals(Seg.Code("sh", "echo hi", open = false), out[1])
        assertEquals(Seg.Text("B"), out[2])
    }

    @Test
    fun `adjacent tool then text renders both`() {
        val raw = "```tool\n{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}\n```\nHere is the summary."
        val out = parse(raw)
        assertEquals(2, out.size)
        assertEquals("read_file", (out[0] as Seg.ToolCall).name)
        assertEquals(Seg.Text("Here is the summary."), out[1])
    }
}
