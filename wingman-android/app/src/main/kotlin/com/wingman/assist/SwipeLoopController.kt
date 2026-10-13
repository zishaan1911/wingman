package com.wingman.assist

import android.util.Log
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drives auto-swiping in the background: keeps calling [doSwipe] on the cadence the
 * [SwipePacer] dictates (variable gaps, bursts with rests, daily cap, dead-hour blackout).
 *
 * It self-gates every tick:
 *   • [isEnabled] false (auto-swipe off / kill switch) → idle-poll slowly, don't swipe.
 *   • [canSwipeNow] false (not on the swipe deck) → idle-poll slowly, wait for the deck.
 *   • pacer returns null (dead hour / daily cap reached) → re-check much later.
 *
 * Single background thread; nothing blocks the accessibility event path. The pacer is the
 * single source of truth for "is this human-paced", so the loop just obeys it.
 */
class SwipeLoopController(
    private val pacer: SwipePacer,
    private val isEnabled: () -> Boolean,
    private val canSwipeNow: () -> Boolean,
    private val doSwipe: (onDone: () -> Unit) -> Unit,
    /** Called when enabled but not on the deck — a chance to launch the app (throttled by caller). */
    private val onNotReady: () -> Unit = {},
) {
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false

    companion object {
        private const val TAG = "Wingman"
        private val IDLE_POLL = Duration.ofSeconds(20)      // off / not on deck: check back soon
        private val REST_RECHECK = Duration.ofMinutes(20)   // dead hour / daily cap: check back later
        private val KICKOFF = Duration.ofSeconds(5)
    }

    fun start() {
        if (running) return
        running = true
        schedule(KICKOFF)
    }

    /** Stop scheduling further swipes (safe to start() again later). */
    fun stop() { running = false }

    /** Permanently release the thread (call from Service.onDestroy). */
    fun shutdown() { running = false; exec.shutdownNow() }

    private fun schedule(d: Duration) {
        if (!running || exec.isShutdown) return
        exec.schedule({ tick() }, d.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        if (!running) return
        if (!isEnabled()) { schedule(IDLE_POLL); return }
        if (!canSwipeNow()) { onNotReady(); schedule(IDLE_POLL); return }
        try {
            doSwipe { afterSwipe() }
        } catch (e: Exception) {
            Log.e(TAG, "swipe loop tick failed", e)
            schedule(IDLE_POLL)
        }
    }

    private fun afterSwipe() {
        if (!running) return
        when (val d = pacer.nextDelay()) {
            null -> schedule(REST_RECHECK)   // dead hour or daily cap hit
            else -> schedule(d)              // normal gap, or a between-burst rest
        }
    }
}
