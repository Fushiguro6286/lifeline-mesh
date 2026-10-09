package com.lifeline.mesh.ai

/** What the phone can offer, read by [DeviceProfiler]. Pure data for the decision logic. */
data class DeviceProfile(
    val totalRamMb: Long,
    val availRamMb: Long,
    val freeStorageMb: Long,
    val isArm64: Boolean,
    val sdkInt: Int,
    val socModel: String?,
    val thermalSevere: Boolean,
    val powerSaveLowBattery: Boolean,
    val phone: String = "",
)

data class Candidate(val model: ModelSpec, val backend: LlmBackend, val installed: Boolean)

data class Rejection(val model: ModelSpec, val backend: LlmBackend?, val reason: String)

data class Shortlist(val candidates: List<Candidate>, val rejected: List<Rejection>)

/**
 * Filters the ladder against the phone (docs/MODEL_SELECTOR.md sections 4 and 8): arm64, total RAM, free storage for
 * models not yet installed, and free RAM per backend. Best tier first, GPU before CPU. Pure Kotlin, unit-tested.
 */
object ModelShortlist {
    fun build(profile: DeviceProfile, catalog: List<ModelSpec>, installedIds: Set<String>): Shortlist {
        val keep = mutableListOf<Candidate>()
        val rejected = mutableListOf<Rejection>()
        for (m in catalog.sortedBy { it.tier }) {
            if (!profile.isArm64) { rejected += Rejection(m, null, "Needs a 64-bit ARM phone"); continue }
            if (profile.totalRamMb < m.minTotalRamMb) {
                rejected += Rejection(m, null, "Needs ${m.minTotalRamMb / 1000} GB RAM"); continue
            }
            val installed = m.id in installedIds
            val sizeMb = m.sizeBytes / 1_000_000
            if (!installed && profile.freeStorageMb < sizeMb + m.storageHeadroomMb) {
                rejected += Rejection(m, null, "Needs ${sizeMb + m.storageHeadroomMb} MB free storage"); continue
            }
            for (b in m.backends.filter { it != LlmBackend.NPU }.sortedBy { if (it == LlmBackend.GPU) 0 else 1 }) {
                val need = if (b == LlmBackend.GPU) m.minAvailRamMbGpu else m.minAvailRamMbCpu
                if (profile.availRamMb < need) rejected += Rejection(m, b, "Needs $need MB free RAM")
                else keep += Candidate(m, b, installed)
            }
        }
        return Shortlist(keep, rejected)
    }
}

/** Speed probe numbers for one model on one backend. */
data class ProbeResult(
    val ttftMs: Long,
    val decodeTokPerSec: Double,
    val loaded: Boolean,
    val error: String? = null,
) {
    fun passesSpeed(maxTtftMs: Long = 2_500, minTokPerSec: Double = 10.0): Boolean =
        loaded && error == null && ttftMs <= maxTtftMs && decodeTokPerSec >= minTokPerSec
}
