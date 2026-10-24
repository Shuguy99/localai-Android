package dev.localai.agent.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandPolicyTest {

    @Test
    fun blocksRecursiveDeleteOfRoot() {
        assertNotNull(CommandPolicy.rejection("rm -rf /"))
        assertNotNull(CommandPolicy.rejection("echo hi && rm -fr /"))
        assertNotNull(CommandPolicy.rejection("rm -rf / ; echo done"))
    }

    @Test
    fun allowsRecursiveDeleteInsideWorkspace() {
        assertNull(CommandPolicy.rejection("rm -rf /workspace/build"))
        assertNull(CommandPolicy.rejection("cd /workspace && rm -rf ./build"))
    }

    @Test
    fun blocksFilesystemCreationAndRawDeviceWrites() {
        assertNotNull(CommandPolicy.rejection("mkfs.ext4 /dev/block/sda1"))
        assertNotNull(CommandPolicy.rejection("dd if=/dev/zero of=/dev/block/by-name/boot bs=1M"))
        assertNotNull(CommandPolicy.rejection("cat image > /dev/mmcblk0"))
    }

    @Test
    fun blocksForkBombAndPowerCommands() {
        assertNotNull(CommandPolicy.rejection(":(){ :|:& };:"))
        assertNotNull(CommandPolicy.rejection("shutdown -h now"))
        assertNotNull(CommandPolicy.rejection("reboot"))
    }

    @Test
    fun blocksWorldUnreadableRootPermissions() {
        assertNotNull(CommandPolicy.rejection("chmod -R 000 /"))
    }

    @Test
    fun normalisesWhitespaceBeforeMatching() {
        assertNotNull(CommandPolicy.rejection("rm    -rf    /"))
    }

    @Test
    fun allowsOrdinaryShellWork() {
        assertNull(CommandPolicy.rejection("echo hello && uname -a && id"))
        assertNull(CommandPolicy.rejection("apk add --no-cache python3"))
        assertNull(CommandPolicy.rejection("grep -R TODO /workspace/src"))
        assertNull(CommandPolicy.rejection("dd if=/dev/zero of=/workspace/zeros bs=1M count=1"))
    }
}
