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

/**
 * How flirty to be with THIS match, inferred from how they communicate.
 *  CLEAN      = friendly, no romantic/sexual charge.
 *  FLIRTY     = playful, warm, light romantic interest.
 *  SUGGESTIVE = clearly flirtatious with innuendo; still tasteful, never graphic.
 * Anything beyond SUGGESTIVE (explicit) is never auto-sent — it's drafted and handed
 * to you to approve. And a possible-minor signal forces CLEAN, always.
 */
enum class Flirt { CLEAN, FLIRTY, SUGGESTIVE }

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
    /** Durable facts about the match worth remembering for callbacks (from the same read). */
    val facts: List<String> = emptyList(),
    /** How flirty to be with this match (inferred from how they talk). */
    val flirt: Flirt = Flirt.CLEAN,
    /** The latest exchange is explicitly sexual → draft but hand to the user, never auto-send. */
    val explicit: Boolean = false,
    /** Any signal the match may be under 18 → forces CLEAN and blocks all suggestive content. */
    val possibleMinor: Boolean = false,
) {
    companion object {
        /** Safe default before we've analysed anyone. */
        val DEFAULT = MatchStrategy(Tone.GENUINE, Pace.MIRROR, 0.5, 0.0, false, "default")
    }
}

/** How a conversation's momentum is trending (interest + reply-latency growth). */
enum class DecayState { WARM, COOLING, COLD }

/** Outcome events we log to learn what works for YOU specifically. */
enum class OutcomeKind { OPENER_SENT, REPLY_SENT, GOT_REPLY, PROPOSED_DATE, MOVED_OFF_APP }

/** Funnel snapshot for the analytics screen. */
data class FunnelStats(
    val matches: Int,
    val openersSent: Int,
    val repliesReceived: Int,
    val dateProposals: Int,
    val offAppMoves: Int,
) {
    /** Reply rate to our openers — the top-of-funnel health metric. */
    val openerReplyRate: Double get() = if (openersSent == 0) 0.0 else repliesReceived.toDouble() / openersSent
}

/** One photo's audit result. */
data class PhotoNote(val index: Int, val score: Double, val note: String)

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

/**
 * Your "type" — fed into the swipe decision so effort isn't wasted on people you wouldn't
 * date anyway. Looks is judged from photos, personality from the bio/prompts, and anything
 * matching a dealbreaker is an automatic pass regardless of the rest.
 */
data class SwipePrefs(
    /** Physical preferences (e.g. "tall, fit, into fashion; not my type: heavy smokers in pics"). */
    val looks: String,
    /** Personality / lifestyle you want (e.g. "ambitious, witty, outdoorsy, reads; wants kids"). */
    val personality: String,
    /** Hard noes — any of these present → pass. (e.g. "smoking, no bio, only group photos, under 25"). */
    val dealbreakers: String,
    /** 0 = very picky (right-swipe rarely), 1 = liberal. */
    val selectivity: Double = 0.5,
) {
    /** One-line summary for places that want a single "looking for" string (e.g. photo audit). */
    fun summary(): String = listOf(looks, personality).filter { it.isNotBlank() }.joinToString("; ")
}

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
    /** Tone used — tagged so the learning loop can credit/blame it on the outcome. */
    val tone: Tone = Tone.GENUINE,
    /** True if this is the first message (an opener) rather than a reply. */
    val isOpener: Boolean = false,
)
