package com.wingman.assist

import java.time.Instant

/** Which app a conversation/profile belongs to. */
enum class App { TINDER, BUMBLE }

/** Who sent a message in a conversation. */
enum class Sender { ME, THEM }

/**
 * Reply tone — chosen PER MATCH by the strategist from how the person communicates.
 *  GENUINE  = warm, curious, substantive; meets earnestness with earnestness.
 *  PLAYFUL  = banter, teasing, light; for people who volley jokes.
 *  SCARCITY = high-value, a touch unhurried/aloof; for people playing it cool (meet cool with cool).
 *  WARM     = openly interested and enthusiastic; for people who are clearly keen.
 */
enum class Tone { GENUINE, PLAYFUL, SCARCITY, WARM }

/**
 * Reply pace — chosen PER MATCH, layered on their observed rhythm.
 *  MIRROR         = match their latency (default; builds 'in sync' rapport).
 *  SLIGHTLY_SLOWER= ~1.3x their latency (subtle scarcity signal).
 *  VARIABLE       = wider, less predictable gaps (intermittent reinforcement).
 *  WARM_PROMPT    = reliably prompt, just past the no-instant floor (signals interest).
 */
enum class Pace { MIRROR, SLIGHTLY_SLOWER, VARIABLE, WARM_PROMPT }

/** How much the bot may do on its own. Editable from the dev panel. */
enum class AutonomyMode {
    /** Sends everything itself, including big moves. */
    FULL_AUTO,
    /** Auto-sends normal chat; big moves (ask-out, number, meetup) need your tap. */
    APPROVE_BIG_MOVES,
    /** Every outgoing message needs your tap. */
    SUPERVISED,
}

/** What the bot should try to do next in a conversation. */
enum class EscalationAction {
    /** Keep the conversation warm; no special move. */
    NONE,
    /** Propose meeting up / suggest a concrete plan. */
    PROPOSE_DATE,
    /** They signalled wanting to move off-app — facilitate number / platform swap. */
    MOVE_OFF_APP,
}

/**
 * The strategist's read of ONE match, refreshed as the conversation grows.
 * Drives both tone (ReplyEngine) and pace (TimingEngine), plus escalation.
 */
data class MatchStrategy(
    val tone: Tone,
    val pace: Pace,
    /** How interested THEY seem, 0..1. */
    val interest: Double,
    /** How ready the moment is to be asked out, 0..1. */
    val readiness: Double,
    /** They explicitly hinted at moving to number/other app. */
    val wantsOffApp: Boolean,
    /** One-line why, surfaced in the dev panel / overlay for auditing. */
    val rationale: String,
) {
    companion object {
        /** Safe default before we've analysed anyone. */
        val DEFAULT = MatchStrategy(Tone.GENUINE, Pace.MIRROR, 0.5, 0.0, false, "default")
    }
}

/** A single chat message as parsed off the screen. */
data class Message(
    val sender: Sender,
    val text: String,
    val at: Instant,
)

/** A conversation with one match. */
data class Conversation(
    val id: String,
    val app: App,
    val matchName: String,
    val messages: MutableList<Message> = mutableListOf(),
    /** Latest per-match read; null until first analysis. */
    var strategy: MatchStrategy? = null,
) {
    val lastMessage: Message? get() = messages.lastOrNull()
    val isOurTurn: Boolean get() = lastMessage?.sender == Sender.THEM
    val depth: Int get() = messages.size
}

/**
 * You. Fed into prompts so replies sound like you, not a chatbot.
 * warmth is a *floor* on eagerness; the per-match Tone/Pace adapt around it.
 */
data class Persona(
    val name: String,
    val voice: String,
    val bio: String,
    val boundaries: String,
    val warmth: Double = 0.55,
)

/** What you're looking for, fed into the swipe decision. */
data class SwipePrefs(
    val criteria: String,
    val selectivity: Double = 0.5,
)

/** Output of a swipe evaluation. */
data class SwipeDecision(
    val like: Boolean,
    val confidence: Double,
    val reason: String,
)

/** A drafted reply + when to send it + whether it needs your approval first. */
data class ScheduledReply(
    val conversationId: String,
    val matchName: String,
    val text: String,
    val sendAt: Instant,
    val escalation: EscalationAction = EscalationAction.NONE,
    val needsApproval: Boolean = false,
)
