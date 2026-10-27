package dev.localai.agent.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure (no Android context) parts of the memory estimator: the size-only total
 * used for remote files and the fit thresholds.
 */
class MemoryRulesTest {

    @Test
    fun `size-only total is weights plus runtime overhead plus heuristic kv`() {
        val size = 1L * 1024 * 1024 * 1024 // 1 GiB weights
        val total = MemoryGuard.sizeOnlyTotalBytes(size, contextSize = 4096)
        val expected = (size * 1.02).toLong() +
            128L * 1024 * 1024 +
            4096L * 64 * 1024
        assertEquals(expected, total)
    }

    @Test
    fun `kv heuristic scales linearly with context`() {
        val ctx1k = MemoryGuard.sizeOnlyTotalBytes(100L, 1024)
        val ctx2k = MemoryGuard.sizeOnlyTotalBytes(100L, 2048)
        assertEquals(ctx2k - ctx1k, 1024L * 64 * 1024)
    }

    @Test
    fun `fit thresholds match the local-estimate levels`() {
        assertEquals(MemoryGuard.Estimate.Level.OK, MemoryGuard.levelFor(0.49))
        assertEquals(MemoryGuard.Estimate.Level.WARN, MemoryGuard.levelFor(0.50))
        assertEquals(MemoryGuard.Estimate.Level.WARN, MemoryGuard.levelFor(0.69))
        assertEquals(MemoryGuard.Estimate.Level.DANGER, MemoryGuard.levelFor(0.70))
        assertEquals(MemoryGuard.Estimate.Level.DANGER, MemoryGuard.levelFor(1.5))
    }

    @Test
    fun `size estimate classifies against available ram`() {
        val guard = MemoryGuard.sizeOnlyTotalBytes(500L * 1024 * 1024, 4096)
        val ok = MemoryGuard.levelFor(guard.toDouble() / (4L * 1024 * 1024 * 1024))
        val danger = MemoryGuard.levelFor(guard.toDouble() / (512L * 1024 * 1024))
        assertEquals(MemoryGuard.Estimate.Level.OK, ok)
        assertEquals(MemoryGuard.Estimate.Level.DANGER, danger)
    }
}
