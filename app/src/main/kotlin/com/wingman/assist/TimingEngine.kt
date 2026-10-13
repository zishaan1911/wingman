package com.wingman.assist

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Decides WHEN to send a reply so it reads as a real, interested-but-not-desperate
 * human — which is also what keeps it under bot-detection radar.
 *
 * The five levers, blended:
 *  1. Floor      — never reply instantly. Humans don't. (anti-bot + anti-thirsty)
 *  2. Mirroring  — scale your delay to THEIR typical reply latency. Matching cadence
 *                  reads as "we're on the same wavelength"; always-faster reads eager,
 *                  always-slower reads disinterested.
 *  3. Momentum   — early in a convo, reply a bit quicker to build rapport; once it's
 *                  established, relax. Warmth (from Persona) nudges this.
 *  4. Diurnal    — learn when THEY are active (histogram of their message hours) and
 *                  land the send inside a likely-active window. Never fire in dead hours.
 *  5. Jitter     — add noise so intervals never look mechanical, and cap so you don't ghost.
 *
 * Everything is in local time via [zone].
 */
class TimingEngine(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val rng: Random = Random.Default,
) {

    companion object {
        // Never reply faster than this (seconds), even if they're machine-gunning you.
        val FLOOR: Duration = Duration.ofSeconds(45)
        // Don't wait longer than this during their active hours, or it reads as ghosting.
        val DAYTIME_CAP: Duration = Duration.ofHours(4)
        // Hours generally dead for everyone unless their own data says otherwise.
        val GENERIC_DEAD_HOURS = 2..6        // 2am–6am local
        // How strongly to mirror their latency (fraction of their recent latency).
        const val MIRROR_BASE = 0.8
        // Below this many exchanged messages, treat the convo as "early" (faster replies).
        const val EARLY_DEPTH = 6
        // ± jitter fraction applied to the final delay.
        const val JITTER = 0.25
    }

    /**
     * @param conversation the thread we're replying in (its messages carry timestamps)
     * @param persona      you (warmth tunes eagerness)
     * @param pace         the per-match pace chosen by the strategist
     * @param now          current instant
     * @return the instant to send at (always >= now + FLOOR)
     */
    fun computeSendTime(
        conversation: Conversation,
        persona: Persona,
        pace: Pace = Pace.MIRROR,
        now: Instant = Instant.now(),
    ): Instant {
        val theirLatency = estimateTheirLatency(conversation)

        // --- base delay from the per-match pace, scaled by their rhythm ---
        // warmth > 0.5 shortens (keener), < 0.5 lengthens (cooler).
        val warmthFactor = 1.3 - 0.6 * persona.warmth         // ~1.0 at warmth .5
        val (paceFactor, paceJitter) = paceParams(pace)
        var delaySec = theirLatency.seconds * paceFactor * warmthFactor

        // --- momentum: speed up while the convo is still young ---
        if (conversation.depth < EARLY_DEPTH) {
            val youth = conversation.depth.toDouble() / EARLY_DEPTH   // 0..1
            // up to ~45% faster at the very start, easing to neutral by EARLY_DEPTH
            delaySec *= (0.55 + 0.45 * youth)
        }

        // --- jitter so no two intervals look alike (wider for VARIABLE pace) ---
        val jittered = delaySec * (1.0 + rng.nextDouble(-paceJitter, paceJitter))

        // --- clamp to [floor, daytime cap] ---
        val clamped = jittered.toLong()
            .coerceAtLeast(FLOOR.seconds)
            .coerceAtMost(DAYTIME_CAP.seconds)

        var target = now.plusSeconds(clamped)

        // --- diurnal: if target lands in a dead hour for them, push to next active window ---
        target = shiftIntoActiveWindow(target, conversation)

        // Safety: never before floor.
        val earliest = now.plus(FLOOR)
        if (target.isBefore(earliest)) target = earliest
        return target
    }

    /** (base latency multiplier, jitter fraction) for each per-match pace. */
    private fun paceParams(pace: Pace): Pair<Double, Double> = when (pace) {
        Pace.MIRROR          -> MIRROR_BASE to JITTER          // 0.8x, ±25%
        Pace.SLIGHTLY_SLOWER -> 1.35 to JITTER                 // subtle scarcity
        Pace.VARIABLE        -> 1.0 to 0.6                      // unpredictable (±60%)
        Pace.WARM_PROMPT     -> 0.35 to 0.20                    // keen, near the floor
    }

    /**
     * Their typical reply latency, as an exponential moving average of the gaps
     * between one of MY messages and their following reply. Falls back to a sane
     * default when we don't have history yet.
     */
    private fun estimateTheirLatency(conversation: Conversation): Duration {
        val msgs = conversation.messages
        var ema: Double? = null
        val alpha = 0.5
        for (i in 1 until msgs.size) {
            val prev = msgs[i - 1]
            val cur = msgs[i]
            // a reply from them to me
            if (prev.sender == Sender.ME && cur.sender == Sender.THEM) {
                val gap = Duration.between(prev.at, cur.at).seconds.toDouble()
                if (gap <= 0) continue
                ema = if (ema == null) gap else alpha * gap + (1 - alpha) * ema!!
            }
        }
        // Default: assume ~8 min if we've never seen them reply.
        val seconds = (ema ?: 8 * 60.0)
        // Dampen extremes (someone who once took 2 days shouldn't push us to 1.5 days).
        val damped = 60.0 * ln(1 + seconds / 60.0) * 6.0   // log-compress long tails
        return Duration.ofSeconds(min(seconds, max(seconds * 0.0 + damped, 120.0)).toLong())
    }

    /**
     * Build a per-hour activity profile for THEM from the hours their messages landed,
     * with Laplace smoothing and a generic evening-leaning prior. If [target]'s hour is
     * low-activity (or a generic dead hour), roll forward to the start of the next
     * reasonably-active hour, plus a little jitter inside that hour.
     */
    private fun shiftIntoActiveWindow(target: Instant, conversation: Conversation): Instant {
        val profile = activityProfile(conversation)
        var t = ZonedDateTime.ofInstant(target, zone)

        fun hourOk(h: Int): Boolean {
            if (h in GENERIC_DEAD_HOURS) return false
            // "active" = at least 60% of the average hour's weight
            val avg = profile.average()
            return profile[h] >= 0.6 * avg
        }

        var guard = 0
        while (!hourOk(t.hour) && guard < 48) {
            // jump to top of next hour
            t = t.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            guard++
        }
        // land somewhere inside the chosen active hour, not exactly on :00
        val offsetMin = rng.nextInt(0, 55)
        return t.withMinute(offsetMin).withSecond(rng.nextInt(0, 60)).toInstant()
    }

    /** 24-slot hour-of-day weight profile for THEIR messages (smoothed). */
    private fun activityProfile(conversation: Conversation): DoubleArray {
        // Laplace prior: 1 everywhere, plus a mild evening lean (people date-app at night).
        val prof = DoubleArray(24) { 1.0 }
        for (h in 18..23) prof[h] += 1.0
        for (h in 12..17) prof[h] += 0.5
        for (h in GENERIC_DEAD_HOURS) prof[h] = 0.2

        for (m in conversation.messages) {
            if (m.sender != Sender.THEM) continue
            val h = ZonedDateTime.ofInstant(m.at, zone).hour
            prof[h] += 2.0   // observed activity outweighs the prior
        }
        return prof
    }
}

/**
 * Paces auto-swiping so it never looks like a bot: variable gaps, work in bursts
 * with rests, a daily cap, and no swiping in dead hours. Call [nextDelay] after each
 * swipe to get how long to wait before the next one; it returns null when you should
 * stop for now (burst done or daily cap hit or dead hour).
 */
class SwipePacer(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val rng: Random = Random.Default,
    private val dailyCap: Int = 60,            // total swipes/day
    private val burstRange: IntRange = 8..18,  // swipes per burst before a rest
) {
    private var swipesToday = 0
    private var dayStamp = today()
    private var burstRemaining = rng.nextInt(burstRange.first, burstRange.last + 1)

    private fun today() = ZonedDateTime.now(zone).toLocalDate()

    /** @return delay until the next swipe, or null = stop (rest/cap/dead-hour). */
    fun nextDelay(now: ZonedDateTime = ZonedDateTime.now(zone)): Duration? {
        if (now.toLocalDate() != dayStamp) { dayStamp = now.toLocalDate(); swipesToday = 0 }
        if (now.hour in TimingEngine.GENERIC_DEAD_HOURS) return null
        if (swipesToday >= dailyCap) return null

        if (burstRemaining <= 0) {
            // take a rest: 8–40 min, then start a new burst
            burstRemaining = rng.nextInt(burstRange.first, burstRange.last + 1)
            return Duration.ofSeconds(rng.nextLong(8 * 60, 40 * 60))
        }

        swipesToday++
        burstRemaining--
        // human swipe cadence: mostly 1.5–6s, with occasional "reading the bio" pauses
        return if (rng.nextDouble() < 0.15) {
            Duration.ofSeconds(rng.nextLong(10, 35))      // lingering on a profile
        } else {
            Duration.ofMillis(rng.nextLong(1500, 6000))   // normal quick swipe
        }
    }
}
