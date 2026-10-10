package com.lifeline.mesh.mesh

import com.lifeline.mesh.packet.Need

/**
 * The SOS for phones without the app (added 10 Oct 2026): this phone's Bluetooth name becomes a short SOS line, so
 * anyone nearby who opens their Bluetooth list sees it ("SOS · trapped+medical · 3 ppl · Asha"). One-way and visible
 * only while this phone is discoverable (the system asks, 5 minutes at a time). Pure Kotlin; the adapter calls are in
 * ui/send/NoAppCard.kt.
 */
object BluetoothSos {
    /** Many phones show only the first 40 characters of a Bluetooth name. */
    const val MAX_CHARS = 40

    private fun word(n: Need) = when (n) {
        Need.TRAPPED -> "trapped"
        Need.MEDICAL -> "medical"
        Need.WATER_FOOD -> "water"
        Need.FIRE -> "fire"
        Need.ROAD_BLOCKED -> "road blocked"
        Need.AMBULANCE -> "ambulance"
        Need.PANIC -> "help"
    }

    /** English on purpose: "SOS" and these words are the most widely understood on a stranger's phone. */
    fun name(needs: Set<Need>, people: Int, who: String): String {
        val real = needs - Need.PANIC
        val what = if (real.isEmpty()) "needs help" else real.joinToString("+") { word(it) }
        val parts = listOfNotNull("SOS", what, people.takeIf { it > 1 }?.let { "$it ppl" }, who.trim().ifBlank { null })
        val full = parts.joinToString(" · ")
        // Cut on whole characters (code points), never inside one.
        return if (full.codePointCount(0, full.length) <= MAX_CHARS) full
        else full.substring(0, full.offsetByCodePoints(0, MAX_CHARS)).trimEnd(' ', '·')
    }
}
