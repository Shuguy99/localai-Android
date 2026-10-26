package dev.localai.agent.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTopologyTest {

    @Test
    fun `empty map returns zero so the caller can apply its fallback`() {
        assertEquals(0, CpuTopology.bigCoreCount(emptyMap()))
    }

    @Test
    fun `homogeneous octa-core counts every core`() {
        val cores = (0 until 8).associateWith { 2_000_000 }
        assertEquals(8, CpuTopology.bigCoreCount(cores))
    }

    @Test
    fun `snapdragon 865 tri-cluster counts only the two fast clusters`() {
        // 1x 2.84 GHz prime + 3x 2.42 GHz gold + 4x 1.80 GHz silver
        val cores = mapOf(
            0 to 2_841_600,
            1 to 2_419_200, 2 to 2_419_200, 3 to 2_419_200,
            4 to 1_804_800, 5 to 1_804_800, 6 to 1_804_800, 7 to 1_804_800,
        )
        assertEquals(4, CpuTopology.bigCoreCount(cores))
    }

    @Test
    fun `helio g99 dual-cluster counts all cores`() {
        // 2x 2.2 GHz A76 + 6x 2.0 GHz A55: ratio 91% >= 80% threshold
        val cores = mapOf(
            0 to 2_200_000, 1 to 2_200_000,
            2 to 2_000_000, 3 to 2_000_000, 4 to 2_000_000,
            5 to 2_000_000, 6 to 2_000_000, 7 to 2_000_000,
        )
        assertEquals(8, CpuTopology.bigCoreCount(cores))
    }

    @Test
    fun `triple cluster with distant little cores excludes them`() {
        // Prime 3.2 + big 2.6 (81%) + little 1.9 (59%)
        val cores = mapOf(
            0 to 3_200_000,
            1 to 2_600_000, 2 to 2_600_000, 3 to 2_600_000,
            4 to 1_900_000, 5 to 1_900_000, 6 to 1_900_000, 7 to 1_900_000,
        )
        assertEquals(4, CpuTopology.bigCoreCount(cores))
    }

    @Test
    fun `big-core count never exceeds the total core count`() {
        val big = CpuTopology.bigCoreCount(mapOf(0 to 3_000_000, 1 to 2_500_000))
        assertEquals(2, big)
    }
}
