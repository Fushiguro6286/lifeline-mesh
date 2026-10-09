package com.lifeline.mesh.ai

/** Short prompts (CLAUDE.md 9.9). The model outputs labels only; Kotlin maps labels to bits. */
object Prompts {
    /** Bump when prompts change: the saved model choice is re-tested (the language probe depends on the prompt). */
    const val VERSION = 2

    const val EXTRACT_SYSTEM =
        "You convert emergency messages (English, Hinglish, Hindi or Kannada) into JSON. Reply with JSON only.\n" +
            "Keys: needs, people, severity.\n" +
            "needs: array of labels:\n" +
            "- \"trapped\": people cannot get out or away (on a roof, flood water around them, rising water, stuck, under debris)\n" +
            "- \"medical\": someone is injured, ill, bleeding, unconscious, pregnant or needs a doctor\n" +
            "- \"water_food\": they ask for drinking water or food (rising flood water is \"trapped\", not \"water_food\")\n" +
            "- \"fire\": fire or smoke\n" +
            "- \"road_blocked\": a road or bridge is blocked\n" +
            "people: how many people need help (integer). severity: 0-3, 3 = life threatening.\n" +
            "If unknown, use [] or 0."

    /** Constrained-decoding schema for the extraction answer. */
    const val EXTRACT_SCHEMA = """{"type":"object","properties":{"needs":{"type":"array","items":{"type":"string","enum":["trapped","medical","water_food","fire","road_blocked"]}},"people":{"type":"integer"},"severity":{"type":"integer"}},"required":["needs","people","severity"]}"""

    fun question(languageName: String, missing: String) =
        "Ask one short, calm question in $languageName to learn: $missing. No other text."

    fun languageName(tag: String) = when (tag) { "hi" -> "Hindi"; "kn" -> "Kannada"; else -> "English" }

    fun missingText(m: QualityGate.Missing) = when (m) {
        QualityGate.Missing.NEEDS -> "what kind of help they need (trapped, medical, water or food, fire, road blocked)"
        QualityGate.Missing.PEOPLE -> "how many people are with them"
    }

    /** Speed probe: fixed, short, 32 output tokens. */
    const val PROBE_SYSTEM = "You are a counter. Reply with numbers only."
    const val PROBE_USER = "Count from 1 to 40, separated by spaces."
}
