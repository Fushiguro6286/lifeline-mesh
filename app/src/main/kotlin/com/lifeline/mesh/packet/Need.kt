package com.lifeline.mesh.packet

/** Needs bitmask from CLAUDE.md section 6. [label] is the LLM-facing label (the model never outputs bits). */
enum class Need(val bit: Int, val label: String) {
    TRAPPED(1, "trapped"),
    MEDICAL(2, "medical"),
    WATER_FOOD(4, "water_food"),
    FIRE(8, "fire"),
    ROAD_BLOCKED(16, "road_blocked"),
    PANIC(32, "panic"),
    /** A helper asks for an ambulance (Phase 10): Ambulance teams get a strong alarm. Set only by that request. */
    AMBULANCE(64, "ambulance");

    companion object {
        /** The needs a person can pick on the review sheet (panic and ambulance are set only by their own buttons). */
        val selectable = listOf(TRAPPED, MEDICAL, WATER_FOOD, FIRE, ROAD_BLOCKED)

        fun fromMask(mask: Int): Set<Need> = entries.filterTo(LinkedHashSet()) { mask and it.bit != 0 }
        fun toMask(needs: Collection<Need>): Int = needs.fold(0) { acc, n -> acc or n.bit }
    }
}
