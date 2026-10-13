package com.wingman.assist

import android.content.Context

/**
 * The learning loop + analytics.
 *
 *  • Logs outcome events (opener sent, reply sent, got a reply, proposed a date, moved
 *    off-app) and maintains the funnel.
 *  • Tracks reply-rate PER TONE so we learn which approaches actually work for YOU, and
 *    exposes a bandit recommendation (epsilon-greedy) the pipeline can lean on.
 *
 * Persisted in SharedPreferences so it survives restarts. Counts are small integers.
 */
class LearningStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("wingman_learn", Context.MODE_PRIVATE)

    companion object {
        private const val MIN_SAMPLE = 8      // per-tone sample before we trust its rate
        private const val EPSILON = 0.15      // explore rate: 15% of the time, try a non-best tone
    }

    // --- outcome logging ---
    fun record(kind: OutcomeKind, tone: Tone? = null) {
        bump(kind.name)
        if (kind == OutcomeKind.REPLY_SENT && tone != null) bump("sent_${tone.name}")
        if (kind == OutcomeKind.GOT_REPLY && tone != null) bump("reply_${tone.name}")
    }

    fun noteMatch() = bump("MATCHES")

    /** A reply that came in response to one of our OPENERS (top-of-funnel health). */
    fun recordOpenerReply() = bump("OPENER_REPLY")

    // --- funnel ---
    fun funnel() = FunnelStats(
        matches = get("MATCHES"),
        openersSent = get(OutcomeKind.OPENER_SENT.name),
        repliesReceived = get("OPENER_REPLY"),
        dateProposals = get(OutcomeKind.PROPOSED_DATE.name),
        offAppMoves = get(OutcomeKind.MOVED_OFF_APP.name),
    )

    /** Reply rate for a tone, or null if we haven't sent enough with it yet. */
    fun replyRate(tone: Tone): Double? {
        val sent = get("sent_${tone.name}")
        if (sent < MIN_SAMPLE) return null
        return get("reply_${tone.name}").toDouble() / sent
    }

    /**
     * Bandit: with probability EPSILON (or until we have data) keep the strategist's pick
     * to keep exploring; otherwise nudge toward the best-performing tone we've seen.
     * Returns the tone the pipeline should actually use.
     */
    fun chooseTone(strategistPick: Tone): Tone {
        val rated = Tone.values().mapNotNull { t -> replyRate(t)?.let { t to it } }
        if (rated.isEmpty() || Math.random() < EPSILON) return strategistPick
        val best = rated.maxByOrNull { it.second }!!.first
        // Only override if the best beats the strategist's pick by a clear margin.
        val pickRate = replyRate(strategistPick) ?: 0.0
        val bestRate = rated.first { it.first == best }.second
        return if (bestRate - pickRate > 0.1) best else strategistPick
    }

    /** Human-readable per-tone performance for the analytics screen. */
    fun toneReport(): String = Tone.values().joinToString("\n") { t ->
        val sent = get("sent_${t.name}"); val rep = get("reply_${t.name}")
        val rate = if (sent == 0) "—" else "${(100.0 * rep / sent).toInt()}%"
        "  ${t.name.padEnd(9)} $rep/$sent replies ($rate)"
    }

    private fun bump(key: String) = sp.edit().putInt(key, get(key) + 1).apply()
    private fun get(key: String) = sp.getInt(key, 0)
}
