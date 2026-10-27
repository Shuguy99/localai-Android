package dev.localai.agent.agent

import dev.localai.agent.agent.CommandRisk.Risk
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRiskTest {

    private fun safe(command: String) = assertEquals(Risk.SAFE, CommandRisk.classify(command))
    private fun confirm(command: String) = assertEquals(command, Risk.CONFIRM, CommandRisk.classify(command))

    // --- read-only whitelist ---

    @Test
    fun `plain read-only commands are safe`() {
        safe("ls -la /workspace")
        safe("cat /workspace/main.kt")
        safe("grep -n TODO /workspace/app.kt")
        safe("wc -l /workspace/log.txt")
        safe("uname -a")
        safe("df -h")
        safe("du -sh /workspace")
        safe("which busybox")
        safe("ps aux")
        safe("echo hello")
    }

    @Test
    fun `pipelines of read-only commands are safe`() {
        safe("ls /workspace | wc -l")
        safe("cat access.log | grep ERROR | sort | uniq -c")
        safe("find /workspace -name '*.kt' | head -20")
    }

    @Test
    fun `cd chaining is safe`() {
        safe("cd /workspace && ls -la")
        safe("cd /tmp; pwd")
        safe("export FOO=bar && env | sort")
    }

    @Test
    fun `env assignments and prefix executors keep the wrapped command classified`() {
        safe("env | sort")
        safe("env LC_ALL=C ls /workspace")
        safe("FOO=1 bar=2 cat /workspace/f.txt")
        safe("busybox ls /workspace")
        confirm("env rm -rf /workspace")
        confirm("busybox rm -rf /workspace")
    }

    @Test
    fun `operators inside quotes do not split or escalate`() {
        safe("echo \"a;b|c&&d\"")
        safe("echo 'rm -rf /'")
        safe("grep -n \";\" /workspace/data.csv")
    }

    @Test
    fun `empty and blank commands are safe`() {
        safe("")
        safe("   ")
    }

    // --- mutation, network, executors ---

    @Test
    fun `filesystem mutations require confirmation`() {
        confirm("rm /workspace/old.txt")
        confirm("rm -rf /workspace/build")
        confirm("cp a.txt b.txt")
        confirm("mv /workspace/a /workspace/b")
        confirm("mkdir /workspace/new")
        confirm("touch /workspace/x")
        confirm("chmod +x run.sh")
        confirm("apk add python3")
        confirm("apk add --no-cache curl")
    }

    @Test
    fun `network and shell executors require confirmation`() {
        confirm("wget https://example.com/f.tar.gz")
        confirm("curl -s https://example.com")
        confirm("sh -c 'echo hi'")
        confirm("bash script.sh")
        confirm("echo hi | sh")
        confirm("xargs rm < files.txt")
    }

    @Test
    fun `redirection writes a file and requires confirmation`() {
        confirm("ls > out.txt")
        confirm("echo hi >> log.txt")
        confirm("grep -r x /workspace > /tmp/hits")
        confirm("sort < in.txt")
    }

    @Test
    fun `fd dup redirect alone stays safe`() {
        safe("ls /workspace 2>&1")
        safe("cat /workspace/missing 2>&1 | grep err")
    }

    @Test
    fun `command substitution requires confirmation`() {
        confirm("ls \$(pwd)")
        confirm("echo `whoami`")
        // Backticks and $() still execute inside double quotes.
        confirm("echo \"dir is \$(pwd)\"")
        confirm("echo \"user is `id -u`\"")
        // Single quotes are a literal context in sh.
        safe("echo 'literal \$(pwd) stays text'")
    }

    @Test
    fun `dangerous find and sed variants require confirmation`() {
        confirm("find /workspace -name '*.tmp' -delete")
        confirm("find /workspace -exec rm {} \\;")
        confirm("sed -i 's/a/b/' /workspace/f.txt")
        // Plain sed prints to stdout, but its w/e commands can write and exec;
        // detecting those inside scripts is not worth the risk, so sed always asks.
        confirm("sed -n '1,5p' /workspace/f.txt")
    }

    @Test
    fun `git python and interactive tools require confirmation`() {
        confirm("git status")
        confirm("python3 /workspace/x.py")
        confirm("tmux new -s work")
        confirm("kill 1234")
    }

    // --- segment splitter internals ---

    @Test
    fun `splitter handles quotes escapes and operators`() {
        assertEquals(
            listOf("echo \"a;b\"", "  cat f"),
            CommandRisk.splitSegments("echo \"a;b\";  cat f"),
        )
        assertEquals(
            listOf("ls ", " wc -l"),
            CommandRisk.splitSegments("ls | wc -l"),
        )
        assertEquals(
            listOf("cd /w ", " ls"),
            CommandRisk.splitSegments("cd /w && ls"),
        )
        assertEquals(listOf("echo a\\\"b"), CommandRisk.splitSegments("echo a\\\"b"))
    }
}
