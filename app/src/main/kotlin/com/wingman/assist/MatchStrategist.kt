package com.wingman.assist

import android.util.Log
import org.json.JSONObject

/**
 * Reads one conversation and infers the best per-match approach: what tone the person
 * responds to, what pace suits them, how interested they are, whether the moment is
 * ripe to ask them out, and whether they've hinted at moving off-app.
 *
 * This is the "adaptive" core: instead of one fixed strategy for everyone, each match
 * gets a tailored read that updates as the conversation develops. Matching a person's
 * own communication style is what lifts both rapport and throughput.
 */
class MatchStrategist(private val groq: GroqClient) {

    fun analyze(conversation: Conversation, persona: Persona): MatchStrategy {
        // Not worth a model call on an empty/one-line thread — use the default.
        if (conversation.messages.size < 2) return MatchStrategy.DEFAULT

        val system = buildString {
            appendLine("You are a dating-conversation strategist. Read the chat and decide how the USER should")
            appendLine("approach THIS specific match to maximise genuine connection and the chance of a date.")
            appendLine("Adapt to how the MATCH communicates — meet banter with banter, earnestness with earnestness,")
            appendLine("someone playing it cool with a touch of cool. Do not be manipulative; read the person.")
            appendLine()
            appendLine("Pick:")
            appendLine("- tone: one of GENUINE, PLAYFUL, SCARCITY, WARM")
            appendLine("    GENUINE=warm+curious+substantive; PLAYFUL=banter/teasing; SCARCITY=high-value, a touch")
            appendLine("    unhurried (use only when THEY are cool/slow, never on a keen person); WARM=openly keen.")
            appendLine("- pace: one of MIRROR, SLIGHTLY_SLOWER, VARIABLE, WARM_PROMPT")
            appendLine("    MIRROR=match their speed; SLIGHTLY_SLOWER=subtle scarcity; VARIABLE=less predictable;")
            appendLine("    WARM_PROMPT=reliably prompt (for keen, high-interest matches).")
            appendLine("- interest: 0..1, how into the conversation THEY seem.")
            appendLine("- readiness: 0..1, how ripe the moment is to suggest meeting up.")
            appendLine("- wantsOffApp: true if they hinted at texting / another app / a call.")
            appendLine()
            appendLine("Respond with ONLY this JSON, no markdown:")
            appendLine("""{"tone":"GENUINE","pace":"MIRROR","interest":0.0,"readiness":0.0,"wantsOffApp":false,"rationale":"short"}""")
        }

        val transcript = conversation.messages.joinToString("\n") { m ->
            val who = if (m.sender == Sender.ME) persona.name else conversation.matchName
            "$who: ${m.text}"
        }
        val user = "Match: ${conversation.matchName}\nMessages exchanged: ${conversation.depth}\n\n$transcript"

        return try {
            val raw = groq.complete(system, user, temperature = 0.2, maxTokens = 160)
            parse(raw)
        } catch (e: Exception) {
            Log.e("Wingman", "strategist failed", e)
            conversation.strategy ?: MatchStrategy.DEFAULT
        }
    }

    private fun parse(raw: String): MatchStrategy {
        val body = raw.substringAfter('{', "").let { if (it.isEmpty()) raw else "{$it" }
            .let { val i = it.lastIndexOf('}'); if (i >= 0) it.substring(0, i + 1) else raw }
        val o = JSONObject(body)
        return MatchStrategy(
            tone = runCatching { Tone.valueOf(o.optString("tone", "GENUINE").uppercase()) }.getOrDefault(Tone.GENUINE),
            pace = runCatching { Pace.valueOf(o.optString("pace", "MIRROR").uppercase()) }.getOrDefault(Pace.MIRROR),
            interest = o.optDouble("interest", 0.5).coerceIn(0.0, 1.0),
            readiness = o.optDouble("readiness", 0.0).coerceIn(0.0, 1.0),
            wantsOffApp = o.optBoolean("wantsOffApp", false),
            rationale = o.optString("rationale", "").trim(),
        )
    }
}
