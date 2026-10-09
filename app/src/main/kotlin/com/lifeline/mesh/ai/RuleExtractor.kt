package com.lifeline.mesh.ai

import com.lifeline.mesh.packet.Need

/** Who filled in the draft: shown on the review sheet as "Understood by: Rules / On-device AI". */
enum class DraftSource { RULES, AI }

/** An alert before sending. The user reviews and can edit every field. [people] 0 = not known yet. */
data class AlertDraft(
    val needs: Set<Need>,
    val people: Int,
    val severity: Int,
    val note: String,
    val source: DraftSource = DraftSource.RULES,
) {
    val panic get() = Need.PANIC in needs
}

/**
 * Keyword and number rules for English, Hinglish, Hindi and Kannada (CLAUDE.md 9.1). Always runs, instantly,
 * with no model. Written from scratch (the web prototype's source was not available); the tests in
 * RuleExtractorTest pin the behaviour, including the four demo sentences.
 *
 * Rules:
 * - Devanagari (०–९) and Kannada (೦–೯) digits count as digits.
 * - People = the largest number written next to a people word ("5 people", "5 log", "३ लोग", "4 ಜನ", "five of us",
 *   "ek bujurg" does not count: "bujurg" is not a people word). Kannada counting words like ಇಬ್ಬರು (two people)
 *   count on their own. "me"/"I" alone means 1.
 * - "Water" is a need only with a need word ("need water", "paani chahiye", "ನೀರು ಬೇಕು"); rising water means trapped.
 * - Severity: 3 if two of trapped/medical/fire, fire with people, rising water, or an urgent word; 2 for trapped,
 *   medical or fire; 1 for water/food or road; else 0.
 */
object RuleExtractor {

    fun extract(text: String): AlertDraft {
        val t = normalize(text)
        val needs = LinkedHashSet<Need>()
        if (hasAny(t, TRAPPED) || hasAny(t, RISING_WATER)) needs += Need.TRAPPED
        if (hasAny(t, MEDICAL)) needs += Need.MEDICAL
        if (hasAny(t, WATER_FOOD)) needs += Need.WATER_FOOD
        if (hasAny(t, FIRE)) needs += Need.FIRE
        if (hasAny(t, ROAD)) needs += Need.ROAD_BLOCKED
        val people = people(t)
        return AlertDraft(needs, people, severity(t, needs, people), text.trim())
    }

    // ------------------------------------------------------------------ normalizing

    /** Lower case, native digits to ASCII, punctuation to spaces, single spaces, padded for word matching. */
    internal fun normalize(text: String): String {
        val sb = StringBuilder(text.length + 2).append(' ')
        for (ch in text.lowercase()) {
            sb.append(
                when (ch) {
                    in '०'..'९' -> '0' + (ch - '०') // Devanagari digits
                    in '೦'..'೯' -> '0' + (ch - '೦') // Kannada digits
                    ',', '.', '!', '?', ';', ':', '"', '(', ')', '।', '॥', '\n', '\t', '-', '/' -> ' '
                    '’' -> '\''
                    else -> ch
                }
            )
        }
        return sb.append(' ').toString().replace(Regex(" +"), " ")
    }

    /**
     * Latin phrases match on word boundaries (so "ill" is not found in "will"); a trailing `*` makes a Latin stem
     * ("phans*" matches "phanse"). Indic stems match anywhere, because Hindi and Kannada attach endings to the stem.
     */
    private fun hasAny(t: String, phrases: List<String>): Boolean = phrases.any { p ->
        when {
            p.first().code >= 0x0900 -> t.contains(p)
            p.endsWith("*") -> Regex("(?<![a-z])" + Regex.escape(p.dropLast(1))).containsMatchIn(t)
            else -> Regex("(?<![a-z])" + Regex.escape(p) + "(?![a-z])").containsMatchIn(t)
        }
    }

    // ------------------------------------------------------------------ people

    private fun people(t: String): Int {
        var best = 0
        val tokens = t.trim().split(' ')
        for ((i, tok) in tokens.withIndex()) {
            // Digits glued to a people word, e.g. "4ಜನ", "3लोग".
            GLUED.find(tok)?.let { m ->
                if (isPeopleWord(m.groupValues[2])) best = maxOf(best, m.groupValues[1].toInt())
            }
            val n = numberOf(tok) ?: continue
            if (n !in 1..255) continue
            // A people word right after the number, allowing up to two words between ("5 elderly people", "4 of us").
            val ahead = tokens.subList(i + 1, minOf(tokens.size, i + 4))
            if (ahead.any { isPeopleWord(it) }) best = maxOf(best, n)
            // "family of 4", "we are 5"
            val before = tokens.subList(maxOf(0, i - 2), i).joinToString(" ")
            if (before.endsWith("family of") || before.endsWith("we are") || before.endsWith("hum") || before.endsWith("हम")) {
                best = maxOf(best, n)
            }
        }
        // Kannada words that already mean "N people".
        for ((word, n) in KN_PEOPLE_COUNT) if (t.contains(word)) best = maxOf(best, n)
        if (best == 0 && hasAny(t, ALONE)) best = 1
        return best
    }

    private fun numberOf(tok: String): Int? = tok.toIntOrNull() ?: NUMBER_WORDS[tok]

    private val GLUED = Regex("^(\\d{1,3})(\\D+)$")

    private fun isPeopleWord(tok: String): Boolean =
        tok in PEOPLE_WORDS || PEOPLE_STEMS.any { tok.startsWith(it) }

    // ------------------------------------------------------------------ severity

    private fun severity(t: String, needs: Set<Need>, people: Int): Int {
        val serious = needs.count { it == Need.TRAPPED || it == Need.MEDICAL || it == Need.FIRE }
        return when {
            serious >= 2 -> 3
            Need.FIRE in needs && people > 0 -> 3
            hasAny(t, RISING_WATER) -> 3
            hasAny(t, URGENT) && needs.isNotEmpty() -> 3
            serious == 1 -> 2
            needs.isNotEmpty() -> 1
            else -> 0
        }
    }

    // ------------------------------------------------------------------ vocabulary

    private val TRAPPED = listOf(
        // English
        "trapped", "stuck", "stranded", "on the roof", "on roof", "rooftop", "on the terrace", "cant get out",
        "can't get out", "cannot get out", "can not get out", "buried", "under debris", "under the debris", "collapsed",
        "surrounded by water", "cut off",
        // Hinglish
        "phans*", "phas gaye", "phase hue", "fans*", "fas gaye", "fase", "phas*", "chhat par", "chat par", "chhat pe", "chhath",
        "nikal nahi", "nikal nahin", "malbe", "dab gaye",
        // Hindi
        "फंस", "फँस", "फसे", "छत पर", "छत पे", "निकल नहीं", "मलबे", "दब गए", "दबे",
        // Kannada
        "ಸಿಲುಕಿ", "ಸಿಕ್ಕಿಹಾಕ", "ಮಾಳಿಗೆ", "ಛಾವಣಿ", "ಹೊರಗೆ ಬರಲು ಆಗುತ್ತಿಲ್ಲ", "ಅವಶೇಷ",
    )

    /** Rising or deep water: people are trapped, and it is life threatening. */
    private val RISING_WATER = listOf(
        "water is rising", "water rising", "rising water", "flooding", "drowning", "water level rising",
        "paani badh", "pani badh", "paani bhar", "pani bhar", "doob rahe", "dub rahe", "doob",
        "पानी बढ़", "पानी भर", "डूब", "बाढ़ का पानी",
        "ನೀರು ಏರ", "ನೀರು ಹೆಚ್ಚ", "ಮುಳುಗ", "ಪ್ರವಾಹ",
    )

    private val MEDICAL = listOf(
        "injured", "injury", "hurt", "bleeding", "blood", "sick", "ill", "unconscious", "fainted", "not breathing",
        "breathing", "broken leg", "broken arm", "fracture", "pregnant", "labour", "labor", "heart attack", "medicine",
        "doctor", "ambulance", "fever", "wounded", "diabetic", "insulin", "seizure",
        "beemar", "bimar", "ghayal", "khoon", "chot", "behosh", "dawai", "dawa", "saans", "bukhar",
        "बीमार", "घायल", "खून", "चोट", "बेहोश", "दवा", "डॉक्टर", "डाक्टर", "सांस", "साँस", "गर्भवती", "बुखार",
        "ಗಾಯ", "ಅನಾರೋಗ್ಯ", "ರಕ್ತ", "ಪ್ರಜ್ಞೆ", "ಔಷಧ", "ವೈದ್ಯ", "ಉಸಿರ", "ಜ್ವರ", "ಹುಷಾರಿಲ್ಲ", "ಗರ್ಭಿಣಿ",
    )

    private val WATER_FOOD = listOf(
        "need water", "no water", "drinking water", "water needed", "thirsty", "food", "hungry", "nothing to eat",
        "starving", "need milk",
        "paani chahiye", "pani chahiye", "peene ka paani", "peene ka pani", "khana", "bhookh", "bhukh", "bhook",
        "पानी चाहिए", "पीने का पानी", "पीने को पानी", "खाना", "भूख", "भोजन", "राशन",
        "ನೀರು ಬೇಕು", "ಕುಡಿಯುವ ನೀರು", "ನೀರಿಲ್ಲ", "ಊಟ", "ಆಹಾರ", "ಹಸಿವು",
    )

    private val FIRE = listOf(
        "fire", "burning", "smoke", "flames", "on fire",
        "aag", "dhuan", "dhuaan", "jal raha", "jal rahi",
        "आग", "धुआं", "धुआँ", "जल रह", "लपट",
        "ಬೆಂಕಿ", "ಹೊಗೆ", "ಉರಿಯುತ್ತ",
    )

    private val ROAD = listOf(
        "road blocked", "blocked road", "road is blocked", "road closed", "tree fell", "fallen tree", "tree fallen",
        "landslide", "bridge collapsed", "bridge broken", "road washed",
        "rasta band", "raasta band", "sadak band", "ped gir",
        "रास्ता बंद", "सड़क बंद", "पेड़ गिर", "भूस्खलन", "पुल टूट",
        "ರಸ್ತೆ ಬಂದ್", "ರಸ್ತೆ ಮುಚ್ಚ", "ಮರ ಬಿದ್ದ", "ಭೂಕುಸಿತ", "ಸೇತುವೆ ಕುಸಿ",
    )

    private val URGENT = listOf(
        "urgent", "emergency", "dying", "critical", "immediately", "hurry", "asap", "serious",
        "jaldi", "turant", "jaldi aao", "जल्दी", "तुरंत", "गंभीर", "ಬೇಗ", "ತುರ್ತು", "ಗಂಭೀರ",
    )

    private val ALONE = listOf("help me", "i am", "i'm", "alone", "mujhe", "main akela", "मुझे", "मैं", "अकेला", "अकेली", "ನಾನು", "ನನಗೆ", "ಒಬ್ಬನೇ", "ಒಬ್ಬಳೇ")

    private val PEOPLE_WORDS = setOf(
        "people", "persons", "person", "peoples", "adults", "children", "kids", "members", "family", "us",
        "men", "women", "elderly", "babies", "souls", "log", "logo", "logon", "jan", "jane", "admi", "aadmi", "bache",
        "bacche", "sadasya", "ladke", "aurat", "auratein",
        "लोग", "लोगों", "व्यक्ति", "आदमी", "बच्चे", "सदस्य", "जन", "जने", "महिलाएं",
        "ಜನ", "ಜನರು", "ಮಂದಿ", "ವ್ಯಕ್ತಿ", "ಮಕ್ಕಳು", "ಸದಸ್ಯರು",
    )
    /** Inflected forms: "लोगों", "ಜನರಿಗೆ". */
    private val PEOPLE_STEMS = listOf("लोग", "ಜನ", "ಮಂದಿ")

    private val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "dozen" to 12,
        "couple" to 2,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6,
        "chhah" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "bees" to 20,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "सात" to 7,
        "आठ" to 8, "नौ" to 9, "दस" to 10, "बीस" to 20,
        "ಒಂದು" to 1, "ಎರಡು" to 2, "ಮೂರು" to 3, "ನಾಲ್ಕು" to 4, "ಐದು" to 5, "ಆರು" to 6, "ಏಳು" to 7, "ಎಂಟು" to 8,
        "ಒಂಬತ್ತು" to 9, "ಹತ್ತು" to 10,
    )

    /** Kannada words that themselves mean "N people". */
    private val KN_PEOPLE_COUNT = listOf("ಇಬ್ಬರು" to 2, "ಮೂವರು" to 3, "ನಾಲ್ವರು" to 4, "ಐವರು" to 5, "ಆರು ಜನ" to 6)
}
