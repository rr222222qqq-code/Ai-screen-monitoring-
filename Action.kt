package com.example.aiscreenagent

/** One structured action. In Phase 2 Claude will propose these as JSON; they pass the same validator. */
data class Action(
    val type: String,
    val text: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    /** Direction the CONTENT scrolls: "down" = reveal content below (finger moves up). */
    val direction: String? = null,
    val ms: Long? = null
)

data class ValidationResult(val ok: Boolean, val message: String = "")

data class ActionResult(
    val executed: Boolean,
    val verified: Boolean,
    val message: String,
    val needsConfirmation: Boolean = false
)

object ActionValidator {
    val ALLOWED = setOf(
        "inspect_screen", "tap", "tap_text", "swipe", "press_back",
        "type_text", "wait", "open_settings", "ask_confirmation", "finish"
    )
    private val DIRECTIONS = setOf("up", "down", "left", "right")
    private val OTP_LIKE = Regex("^\\d{4,8}$")

    private val SENSITIVE_WORDS = listOf(
        "send", "bhej", "delete", "remove", "erase", "reset", "pay", "purchase", "buy",
        "install", "uninstall", "transfer", "publish", "post", "security", "lock",
        "password", "factory", "भेज", "डिलीट", "हटा", "भुगतान"
    )

    private fun ok() = ValidationResult(true)
    private fun no(m: String) = ValidationResult(false, m)

    fun validate(a: Action, screenW: Int, screenH: Int): ValidationResult {
        if (a.type !in ALLOWED) return no("unknown action '${a.type}' (allowlist mein nahi hai)")
        return when (a.type) {
            "tap" -> {
                val x = a.x
                val y = a.y
                if (x == null || y == null || x !in 0 until screenW || y !in 0 until screenH)
                    no("tap coordinates screen ke bahar hain") else ok()
            }
            "tap_text" -> {
                val t = a.text
                if (t.isNullOrBlank() || t.length > 80) no("tap_text ko 1-80 character ka text chahiye") else ok()
            }
            "swipe" -> {
                val d = a.direction
                if (d == null || d !in DIRECTIONS) no("direction up/down/left/right honi chahiye") else ok()
            }
            "type_text" -> {
                val t = a.text
                when {
                    t.isNullOrEmpty() || t.length > 200 -> no("type_text ko 1-200 character chahiye")
                    t.any { it.isISOControl() } -> no("text mein control characters allowed nahi")
                    OTP_LIKE.matches(t.trim()) -> no("OTP jaisa number type karna blocked hai")
                    else -> ok()
                }
            }
            "wait" -> {
                val m = a.ms
                if (m == null || m !in 100L..5000L) no("wait 100-5000 ms ke beech hona chahiye") else ok()
            }
            else -> ok()
        }
    }

    /** Actions touching these words need a fresh user confirmation each time. */
    fun needsConfirmation(a: Action): Boolean {
        if (a.type != "tap_text") return false
        val t = a.text?.lowercase() ?: return false
        return SENSITIVE_WORDS.any { t.contains(it) }
    }
}
