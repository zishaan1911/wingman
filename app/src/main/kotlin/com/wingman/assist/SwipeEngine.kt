package com.wingman.assist

import org.json.JSONObject

/**
 * Evaluates a profile from a screenshot (or stitched screenshots) and decides
 * whether to swipe right, via Groq's vision model.
 *
 * You pass JPEG bytes of the profile screen(s). The service is responsible for
 * scrolling the profile and capturing each panel; here we just reason over them.
 */
class SwipeEngine(private val groq: GroqClient) {

    fun evaluate(profileImages: List<ByteArray>, prefs: SwipePrefs): SwipeDecision {
        val system = buildString {
            appendLine("You evaluate dating-app profiles for the user and decide swipe direction, so the user")
            appendLine("doesn't waste effort on people they wouldn't want to date.")
            appendLine()
            appendLine("The user's type:")
            appendLine("- LOOKS (judge from the photos): ${prefs.looks.ifBlank { "no strong preference" }}")
            appendLine("- PERSONALITY/LIFESTYLE (judge from bio & prompts): ${prefs.personality.ifBlank { "no strong preference" }}")
            appendLine("- DEALBREAKERS (any present → like=false, no matter what else): ${prefs.dealbreakers.ifBlank { "none" }}")
            appendLine("Selectivity: ${"%.2f".format(prefs.selectivity)} (0 = very picky, right-swipe rarely; 1 = liberal).")
            appendLine()
            appendLine("Weigh BOTH looks and personality. A dealbreaker is an automatic pass. If the bio is empty,")
            appendLine("judge on photos but lower confidence. Do NOT invent details you can't see. Be respectful.")
            appendLine()
            appendLine("Respond with ONLY a JSON object, no markdown:")
            appendLine("""{"like": true|false, "confidence": 0.0-1.0, "reason": "one short sentence"}""")
        }
        val user = "Here are the profile panels. Decide whether to swipe right on the user's behalf. Return the JSON object only."

        val raw = groq.completeWithImages(system, user, profileImages, temperature = 0.3, maxTokens = 160)
        return parse(raw)
    }

    private fun parse(raw: String): SwipeDecision {
        // Be forgiving: pull the first {...} block in case the model adds stray text.
        val json = raw.substringAfter('{', "").let { if (it.isEmpty()) raw else "{$it" }
            .let { it.substring(0, it.lastIndexOf('}') + 1).ifEmpty { raw } }
        return try {
            val o = JSONObject(json)
            SwipeDecision(
                like = o.optBoolean("like", false),
                confidence = o.optDouble("confidence", 0.5),
                reason = o.optString("reason", "").trim(),
            )
        } catch (e: Exception) {
            // On parse failure, default to a low-confidence pass (don't like on garbage).
            SwipeDecision(like = false, confidence = 0.0, reason = "unparseable model output")
        }
    }
}
