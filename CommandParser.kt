package com.example.aiscreenagent

sealed class Parsed {
    object Stop : Parsed()
    data class Act(val action: Action) : Parsed()
    data class Unknown(val hint: String) : Parsed()
}

/** Phase 1 rule-based parser for Hindi/Hinglish/English. Phase 2 replaces it with Claude. */
object CommandParser {
    private val typeIn = Regex("""^(.*?)\s+(?:mein|me|mai|में|in)\s+(.+?)\s+type\b.*$""", RegexOption.IGNORE_CASE)
    private val typeSimple = Regex("""^type\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val filler = setOf(
        "option", "button", "ko", "par", "pe", "on", "the", "karo", "kar", "do", "dhoondo", "dhundo",
        "dhoond", "find", "tap", "click", "dabao", "press", "select", "open", "mein", "me", "please", "ek"
    )
    private val tapVerbs = listOf("dhoondo", "dhundo", "dhoond", "find", "tap", "click", "dabao", "select")

    fun parse(raw: String): Parsed {
        val s = raw.trim().trimEnd('.', '!', '?', '।').trim()
        if (s.isEmpty()) return Parsed.Unknown("Command khali hai.")
        val l = s.lowercase()
        fun has(vararg w: String) = w.any { l.contains(it) }

        typeIn.matchEntire(s)?.let { return Parsed.Act(Action("type_text", text = it.groupValues[2].trim())) }
        typeSimple.matchEntire(s)?.let { return Parsed.Act(Action("type_text", text = it.groupValues[1].trim())) }

        if (has("rok do", "automation rok", "stop", "band karo", "रोक")) return Parsed.Stop

        if (has("kya dikh", "what's on", "what is on", "screen par kya", "screen pe kya", "क्या दिख"))
            return Parsed.Act(Action("inspect_screen"))

        if (has("settings", "setting", "सेटिंग") && has("kholo", "khol", "open", "खोल", "chalu"))
            return Parsed.Act(Action("open_settings"))

        if (has("scroll", "स्क्रॉल")) {
            return when {
                has("neeche", "niche", "down", "नीचे") -> Parsed.Act(Action("swipe", direction = "down"))
                has("upar", "ooper", "up", "ऊपर") -> Parsed.Act(Action("swipe", direction = "up"))
                else -> Parsed.Unknown("Scroll kis direction mein? (neeche / upar)")
            }
        }

        if (has("back", "wapas", "vapas", "peeche", "पीछे", "वापस")) return Parsed.Act(Action("press_back"))

        if (tapVerbs.any { l.contains(it) }) {
            val target = s.split(Regex("\\s+")).filter { it.lowercase() !in filler }.joinToString(" ").trim()
            if (target.isNotEmpty()) return Parsed.Act(Action("tap_text", text = target))
        }
        return Parsed.Unknown("Samajh nahi aaya. Try: 'Settings kholo', 'Display option dhoondo', 'Neeche scroll karo', 'Back jao', 'Search box mein Hello type karo', 'Screen par kya dikh raha hai', 'Automation rok do'.")
    }
}
