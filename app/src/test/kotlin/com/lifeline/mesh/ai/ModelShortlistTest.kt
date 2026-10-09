package com.lifeline.mesh.ai

import com.lifeline.mesh.ai.LlmBackend.CPU
import com.lifeline.mesh.ai.LlmBackend.GPU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The six cases required by docs/MODEL_SELECTOR.md section 8, plus catalog parsing. */
class ModelShortlistTest {
    companion object {
        val e4b = ModelSpec("gemma4-e4b", "Gemma 4 E4B", "gemma-4-E4B-it.litertlm", 3_659_530_240, tier = 1,
            minTotalRamMb = 8000, minAvailRamMbCpu = 3800, minAvailRamMbGpu = 1500, storageHeadroomMb = 1000, backends = listOf(GPU, CPU))
        val e2b = ModelSpec("gemma4-e2b", "Gemma 4 E2B", "gemma-4-E2B-it.litertlm", 2_588_147_712, tier = 2,
            minTotalRamMb = 6000, minAvailRamMbCpu = 2200, minAvailRamMbGpu = 1200, storageHeadroomMb = 800, backends = listOf(GPU, CPU))
        val g1b = ModelSpec("gemma3-1b", "Gemma 3 1B", "gemma3-1b-it-int4.litertlm", 529_000_000, gated = true, tier = 3,
            minTotalRamMb = 4000, minAvailRamMbCpu = 1400, minAvailRamMbGpu = 1400, storageHeadroomMb = 400, backends = listOf(CPU, GPU))
        val catalog = listOf(g1b, e2b, e4b)
        val all = setOf("gemma4-e4b", "gemma4-e2b", "gemma3-1b")

        fun phone(totalGb: Int, availMb: Long = 6000, freeMb: Long = 100_000, arm64: Boolean = true) =
            DeviceProfile(totalGb * 1000L, availMb, freeMb, arm64, 36, "SM8850", thermalSevere = false, powerSaveLowBattery = false)
    }

    @Test fun sixteenGbPhoneWithAllModelsPicksTier1Gpu() {
        val s = ModelShortlist.build(phone(16), catalog, all)
        assertEquals(e4b to GPU, s.candidates.first().let { it.model to it.backend })
        assertEquals(listOf("gemma4-e4b", "gemma4-e4b", "gemma4-e2b", "gemma4-e2b", "gemma3-1b", "gemma3-1b"), s.candidates.map { it.model.id })
        // GPU before CPU within a tier, even when the catalog lists CPU first (Gemma 3 1B).
        assertEquals(listOf(GPU, CPU, GPU, CPU, GPU, CPU), s.candidates.map { it.backend })
    }

    @Test fun sixGbPhoneSkipsTier1() {
        val s = ModelShortlist.build(phone(6), catalog, all)
        assertTrue(s.candidates.none { it.model.id == "gemma4-e4b" })
        assertEquals("gemma4-e2b", s.candidates.first().model.id)
        assertTrue(s.rejected.any { it.model.id == "gemma4-e4b" && it.reason.contains("8 GB RAM") })
    }

    @Test fun noArm64GivesRulesOnly() {
        val s = ModelShortlist.build(phone(16, arm64 = false), catalog, all)
        assertTrue(s.candidates.isEmpty())
        assertEquals(3, s.rejected.size)
    }

    @Test fun lowStorageRejectsUninstalledButKeepsInstalled() {
        val s = ModelShortlist.build(phone(16, freeMb = 500), catalog, setOf("gemma4-e2b"))
        assertTrue(s.candidates.all { it.model.id == "gemma4-e2b" && it.installed })
        assertTrue(s.rejected.any { it.model.id == "gemma4-e4b" && it.reason.contains("free storage") })
        assertTrue(s.rejected.any { it.model.id == "gemma3-1b" && it.reason.contains("free storage") })
    }

    @Test fun gpuRejectedForLowRamWhileCpuStillQualifies() {
        // Gemma 3 1B: GPU and CPU both need 1400; E2B: GPU 1200 ok, CPU 2200 not. Try 2000 MB free.
        val s = ModelShortlist.build(phone(8, availMb = 2_000), listOf(e2b, g1b, e4b), all)
        assertTrue(Candidate(e2b, GPU, true) in s.candidates)
        assertTrue(s.rejected.any { it.model.id == "gemma4-e2b" && it.backend == CPU })
        // E4B: GPU needs 1500 (ok), CPU needs 3800 (rejected) on this phone.
        assertTrue(Candidate(e4b, GPU, true) in s.candidates)
        assertTrue(s.rejected.any { it.model.id == "gemma4-e4b" && it.backend == CPU && it.reason.contains("3800") })
    }

    @Test fun catalogParsesTheAssetFormat() {
        val text = """[{"id":"x","name":"X","fileName":"x.litertlm","sizeBytes":1,"sha256":null,"downloadUrl":null,
            "gated":true,"license":"Gemma","tier":3,"minTotalRamMb":4000,"minAvailRamMbCpu":1,"minAvailRamMbGpu":2,
            "storageHeadroomMb":3,"backends":["CPU","GPU"],"extra":"ignored"}]"""
        val c = ModelCatalog.parse(text).single()
        assertEquals(listOf(CPU, GPU), c.backends)
        assertTrue(c.gated)
    }

    @Test fun speedProbeThresholds() {
        assertTrue(ProbeResult(2_500, 10.0, true).passesSpeed())
        assertTrue(!ProbeResult(2_501, 50.0, true).passesSpeed())
        assertTrue(!ProbeResult(300, 9.9, true).passesSpeed())
        assertTrue(!ProbeResult(300, 50.0, true, error = "x").passesSpeed())
    }
}
