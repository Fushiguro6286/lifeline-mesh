package com.lifeline.mesh.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class LlmBackend { GPU, CPU, NPU }

/** One rung of the model ladder (docs/MODEL_SELECTOR.md section 2), read from assets/models.json. */
@Serializable
data class ModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    /** Exact file size: an installed file must match it. */
    val sizeBytes: Long,
    val sha256: String? = null,
    val downloadUrl: String? = null,
    val gated: Boolean = false,
    val license: String = "",
    val tier: Int,
    val minTotalRamMb: Long,
    val minAvailRamMbCpu: Long,
    val minAvailRamMbGpu: Long,
    val storageHeadroomMb: Long,
    val backends: List<LlmBackend>,
)

object ModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<ModelSpec> = json.decodeFromString<List<ModelSpec>>(text).sortedBy { it.tier }
}
