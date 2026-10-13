package com.wingman.assist

import org.json.JSONObject

/**
 * Audits YOUR profile photos via the vision model: scores each, suggests the best order,
 * and flags weak ones. Your lead photo is the single biggest driver of match rate, so
 * this is the highest-leverage thing to get right.
 *
 * A/B testing the lead photo is handled by [LearningStore]'s funnel over time: you set a
 * lead-photo variant, let it run, and compare match rate between variants. Reordering the
 * photos themselves is a manual step in the app (reliable auto-reorder across app versions
 * isn't feasible via accessibility), so this gives you the decision, you make the swap.
 */
class PhotoAuditor(private val groq: GroqClient) {

    data class Audit(val notes: List<PhotoNote>, val bestOrder: List<Int>, val summary: String)

    fun audit(photos: List<ByteArray>, lookingFor: String): Audit {
        val system = buildString {
            appendLine("You are a dating-profile photo consultant. You see the user's profile photos in order.")
            appendLine("Rate each on how well it helps them match (clarity, genuine smile, solo & clearly them,")
            appendLine("variety, activity/social proof, no clutter or group-photo confusion).")
            appendLine("Context — they're hoping to attract: $lookingFor")
            appendLine()
            appendLine("Respond with ONLY this JSON, no markdown:")
            appendLine("""{"photos":[{"index":0,"score":0.0,"note":"short"}],"bestOrder":[0,1,2],"summary":"1-2 sentences"}""")
        }
        val user = "Here are the ${photos.size} profile photos in current order. Audit them."
        val raw = groq.completeWithImages(system, user, photos, temperature = 0.3, maxTokens = 500)
        return parse(raw, photos.size)
    }

    private fun parse(raw: String, n: Int): Audit {
        val body = raw.substringAfter('{', "").let { if (it.isEmpty()) raw else "{$it" }
            .let { val i = it.lastIndexOf('}'); if (i >= 0) it.substring(0, i + 1) else raw }
        return try {
            val o = JSONObject(body)
            val notes = o.optJSONArray("photos")?.let { arr ->
                (0 until arr.length()).map {
                    val p = arr.getJSONObject(it)
                    PhotoNote(p.optInt("index", it), p.optDouble("score", 0.0), p.optString("note", "").trim())
                }
            } ?: emptyList()
            val order = o.optJSONArray("bestOrder")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
                ?: (0 until n).toList()
            Audit(notes, order, o.optString("summary", "").trim())
        } catch (e: Exception) {
            Audit(emptyList(), (0 until n).toList(), "could not parse audit")
        }
    }
}
