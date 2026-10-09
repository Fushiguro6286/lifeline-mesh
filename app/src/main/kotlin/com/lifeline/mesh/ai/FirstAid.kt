package com.lifeline.mesh.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One vetted first-aid guide from assets/firstaid.json (SETUP.md 8). [text] is shown verbatim, never rewritten. */
@Serializable
data class FirstAidEntry(val id: String, val title: String, val keywords: List<String> = emptyList(), val text: String, val source: String)

/**
 * First aid (CLAUDE.md 9.7, rule 10). The app never writes medical text: entries come only from the supplied file.
 * The model only picks an entry id for the question; a keyword match is the fallback. The screen always adds
 * "Contact emergency services if you can."
 */
object FirstAid {
    private val json = Json { ignoreUnknownKeys = true }

    /** Valid entries only (unique id, title, text and source present); a broken file gives an empty list. */
    fun parse(text: String): List<FirstAidEntry> {
        val all = runCatching { json.decodeFromString<List<FirstAidEntry>>(text) }.getOrElse { return emptyList() }
        val seen = HashSet<String>()
        return all.filter { e ->
            e.id.isNotBlank() && e.title.isNotBlank() && e.text.isNotBlank() && e.source.isNotBlank() && seen.add(e.id)
        }
    }

    /** The entry whose keywords (or title words) appear most in the question; null when nothing appears. */
    fun ruleMatch(question: String, entries: List<FirstAidEntry>): FirstAidEntry? {
        val q = question.lowercase()
        return entries
            .map { e -> e to (e.keywords + e.title).count { k -> k.isNotBlank() && k.lowercase() in q } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    fun request(question: String, entries: List<FirstAidEntry>) = LlmRequest(
        feature = "firstaid",
        system = "Pick the guide that matches the question. Reply with the guide id only, or none.\nGuides:\n" +
            entries.joinToString("\n") { e -> "${e.id}: ${e.title}" + if (e.keywords.isEmpty()) "" else " (${e.keywords.joinToString(", ")})" },
        user = question,
        maxTokens = 12,
        timeoutMs = 6_000,
    )

    /** An existing id (exact token), [NONE] for "none", or null when the answer is unusable. */
    fun validate(answer: String, entries: List<FirstAidEntry>): String? {
        val t = answer.trim().trim('"', '\'', '`', '.', ' ').lowercase()
        if (t == "none") return NONE
        return entries.firstOrNull { it.id.lowercase() == t }?.id
    }

    const val NONE = "none"
}
