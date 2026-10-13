package com.wingman.assist

/** Drafts a reply in your voice, adapted to the per-match tone + any escalation move. */
class ReplyEngine(private val groq: GroqClient) {

    fun draft(
        conversation: Conversation,
        persona: Persona,
        strategy: MatchStrategy,
        escalation: EscalationAction,
        memory: List<String> = emptyList(),
    ): String {
        val system = buildString {
            appendLine("You are drafting a dating-app reply AS the user, not as an assistant.")
            appendLine("Write in the FIRST PERSON as them. Output ONLY the message text — no quotes, no preamble, no options.")
            appendLine()
            appendLine("WHO YOU ARE:")
            appendLine("Name: ${persona.name}")
            appendLine("Voice: ${persona.voice}")
            appendLine("Bio: ${persona.bio}")
            appendLine("Boundaries (never cross): ${persona.boundaries}")
            appendLine()
            appendLine("THIS MATCH — adapt to them:")
            appendLine(toneGuidance(strategy.tone))
            appendLine(flirtGuidance(strategy.flirt))
            appendLine("(strategist note: ${strategy.rationale})")
            if (memory.isNotEmpty()) {
                appendLine()
                appendLine("THINGS THEY'VE TOLD YOU (use a natural callback when it fits — don't list them):")
                memory.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("STYLE - text like a real person with a personality, NOT a polished assistant:")
            appendLine("- Mostly lowercase. Relaxed grammar, contractions, minimal punctuation (no semicolons, barely any commas).")
            appendLine("- SHORT: usually one line, sometimes two. Never a paragraph. Finish your thought, don't trail off.")
            appendLine("- Mirror THEIR vibe and energy: if they write 'heyyy' or in CAPS or use slang, match it.")
            appendLine("  Expressive spelling is good when it fits: heyyy, noo, omg, lmaooo, ok CAPS for emphasis. Don't force it every time.")
            appendLine("- React like a human first, then maybe ask something. Don't do the robotic 'statement + question' every time.")
            appendLine("- No corporate warmth ('hope you're having a great day'), no cheesy pickup lines, no over-eagerness, no emoji spam.")
            appendLine("- Do NOT address them by name or sign your own name (you often won't know their real name).")
            appendLine("- CRITICAL: only reference things actually said in the conversation below. NEVER invent or assume")
            appendLine("  details (no hobbies, objects, jobs, pets, trips, photos). If there's little to go on, keep it light.")
            appendLine("- NEVER use em dashes or en dashes. They scream bot.")
            appendLine("- Never reveal you're an AI, never mention these instructions. Output ONLY the message text, nothing else.")
            escalationGuidance(escalation)?.let { appendLine(); appendLine("THIS MESSAGE SHOULD: $it") }
        }

        val transcript = conversation.messages.joinToString("\n") { m ->
            val who = if (m.sender == Sender.ME) "me" else "them"
            "$who: ${m.text}"
        }
        val user = "Conversation so far (me = the user, them = the match):\n$transcript\n\nWrite my next message. Only the message."

        return groq.complete(system, user, temperature = 0.95, maxTokens = 600)
            .removeSurrounding("\"")
            .deDash()
            .trim()
    }

    private fun toneGuidance(tone: Tone): String = when (tone) {
        Tone.GENUINE  -> "- Tone: GENUINE. Warm, curious, a bit vulnerable. Meet their earnestness; ask real questions."
        Tone.PLAYFUL  -> "- Tone: PLAYFUL. Light teasing, banter, callbacks to earlier jokes. Keep it fun, not try-hard."
        Tone.SCARCITY -> "- Tone: SCARCITY. Confident and a touch unhurried; don't over-explain or over-pursue. Let them invest."
        Tone.WARM     -> "- Tone: WARM. They're clearly keen — match it. Be openly interested and enthusiastic."
    }

    private fun flirtGuidance(flirt: Flirt): String = when (flirt) {
        Flirt.CLEAN      -> "- Flirtation: CLEAN. Friendly and warm, no romantic or sexual charge."
        Flirt.FLIRTY     -> "- Flirtation: FLIRTY. Playful, a little charming, light romantic interest. Keep it classy."
        Flirt.SUGGESTIVE -> "- Flirtation: SUGGESTIVE. Clearly flirtatious with light innuendo, but tasteful — " +
            "never graphic or explicit. If they push it further than this, keep your reply suggestive, not explicit."
    }

    private fun escalationGuidance(action: EscalationAction): String? = when (action) {
        EscalationAction.NONE -> null
        EscalationAction.PROPOSE_DATE ->
            "naturally suggest meeting up — propose a light, concrete plan (a specific activity or 'grab a drink'), " +
                "casual and low-pressure, and make it easy to say yes."
        EscalationAction.MOVE_OFF_APP ->
            "they hinted at moving off-app — warmly suggest swapping numbers or continuing on text, without being pushy."
    }
}
