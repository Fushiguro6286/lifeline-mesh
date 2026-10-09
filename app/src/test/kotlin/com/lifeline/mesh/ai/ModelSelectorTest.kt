package com.lifeline.mesh.ai

import com.lifeline.mesh.ai.LlmBackend.CPU
import com.lifeline.mesh.ai.LlmBackend.GPU
import com.lifeline.mesh.ai.ModelShortlistTest.Companion.all
import com.lifeline.mesh.ai.ModelShortlistTest.Companion.catalog
import com.lifeline.mesh.ai.ModelShortlistTest.Companion.phone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted engine: fixed speed, and answers that are right or wrong per language. */
class FakeLlmEngine(
    override val model: ModelSpec,
    override val backend: LlmBackend,
    private val ttftMs: Long = 300,
    private val tokPerSec: Double = 40.0,
    private val goodLanguages: Set<String> = setOf("en", "hi", "kn"),
    private val failWith: String? = null,
) : LlmEngine {
    var closed = false
    val calls = mutableListOf<LlmRequest>()

    override suspend fun generate(request: LlmRequest): LlmResult {
        calls += request
        if (failWith != null) return LlmResult("", null, null, null, null, 10, error = failWith)
        val text = when (request.feature) {
            "probe" -> (1..32).joinToString(" ")
            "extract" -> {
                val lang = scriptLanguage(request.user)
                val case = AlertExtractor.PROBES.getValue(lang).firstOrNull { it.first == request.user }
                if (case != null && lang in goodLanguages) {
                    """{"needs":[${case.second.joinToString { "\"${it.label}\"" }}],"people":${case.third},"severity":2}"""
                } else """{"needs":["fire"],"people":0,"severity":1}"""
            }
            else -> "?"
        }
        return LlmResult(text, ttftMs, tokPerSec, 20, 32, ttftMs + 800)
    }

    override fun close() { closed = true }
}

class ModelSelectorTest {
    private val opened = mutableListOf<FakeLlmEngine>()

    private fun opener(behave: (ModelSpec, LlmBackend) -> FakeLlmEngine?): suspend (ModelSpec, LlmBackend) -> LlmEngine = { m, b ->
        (behave(m, b) ?: throw IllegalStateException("GPU delegate failed")).also { opened += it }
    }

    @Test fun picksTier1GpuWhenItPasses() = runTest {
        val out = ModelSelector.select(phone(16), catalog, all, opener { m, b -> FakeLlmEngine(m, b) })
        assertEquals("gemma4-e4b", out.selection.chosenId)
        assertEquals(GPU, out.selection.backend)
        assertEquals(mapOf("en" to true, "hi" to true, "kn" to true), out.selection.languages)
        assertEquals(1, opened.size) // never loads more than needed
        assertFalse(opened.single().closed) // kept loaded
    }

    @Test fun tier1ProbeFailingFallsThroughToTier2() = runTest {
        val out = ModelSelector.select(phone(16), catalog, all, opener { m, b ->
            if (m.id == "gemma4-e4b") FakeLlmEngine(m, b, ttftMs = 3_100, tokPerSec = 8.0) else FakeLlmEngine(m, b)
        })
        assertEquals("gemma4-e2b", out.selection.chosenId)
        assertEquals(GPU, out.selection.backend)
        // E4B GPU and E4B CPU both tried and closed before E2B.
        assertEquals(listOf("gemma4-e4b", "gemma4-e4b", "gemma4-e2b"), opened.map { it.model.id })
        assertTrue(opened.take(2).all { it.closed })
        assertTrue(out.selection.attempts.first().outcome.startsWith("Too slow: TTFT 3.1 s"))
        assertEquals(1, opened.count { !it.closed }) // never two models loaded at once
    }

    @Test fun loadFailureIsRecordedAndCpuTried() = runTest {
        val out = ModelSelector.select(phone(16), catalog, setOf("gemma4-e2b"), opener { m, b ->
            if (b == GPU) null else FakeLlmEngine(m, b)
        })
        assertEquals("gemma4-e2b", out.selection.chosenId)
        assertEquals(CPU, out.selection.backend)
        assertTrue(out.selection.attempts.any { it.backend == GPU && it.outcome.startsWith("Failed to load") })
        assertTrue(out.selection.attempts.any { it.modelId == "gemma4-e4b" && it.outcome == "Not installed" })
    }

    @Test fun nothingPassingMeansRulesOnly() = runTest {
        val out = ModelSelector.select(phone(16), catalog, all, opener { m, b -> FakeLlmEngine(m, b, failWith = "crash") })
        assertTrue(out.selection.rulesOnly)
        assertNull(out.engine)
        assertTrue(opened.all { it.closed })
    }

    @Test fun noModelsInstalledMeansRulesOnlyAndSuggestsADownload() = runTest {
        val out = ModelSelector.select(phone(16), catalog.map {
            if (it.id == "gemma4-e2b") it.copy(downloadUrl = "https://example/x") else it
        }, emptySet(), opener { _, _ -> error("must not load") })
        assertTrue(out.selection.rulesOnly)
        assertEquals("gemma4-e4b", out.selection.betterId) // best tier that fits
    }

    @Test fun languagesFailIndividually() = runTest {
        val out = ModelSelector.select(phone(16), catalog, setOf("gemma4-e2b"), opener { m, b -> FakeLlmEngine(m, b, goodLanguages = setOf("en", "hi")) })
        assertEquals(mapOf("en" to true, "hi" to true, "kn" to false), out.selection.languages)
        assertEquals("gemma4-e4b", out.selection.betterId)
    }

    @Test fun smallPhoneGetsSmallerTier() = runTest {
        val out = ModelSelector.select(phone(6, availMb = 2_500), catalog, all, opener { m, b -> FakeLlmEngine(m, b) })
        assertEquals("gemma4-e2b", out.selection.chosenId)
    }
}
