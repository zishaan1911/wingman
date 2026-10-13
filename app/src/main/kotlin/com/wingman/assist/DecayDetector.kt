package com.wingman.assist

import java.time.Duration

/**
 * Reads whether a thread is cooling off, from two signals:
 *  • their stated interest (from the strategist), and
 *  • whether their replies are getting slower AND shorter over time.
 *
 * COLD threads can be deprioritised or handed a re-engagement attempt instead of more
 * of the same — so your effort (and the model's calls) go to live conversations.
 */
class DecayDetector {

    fun assess(conversation: Conversation, strategy: MatchStrategy): DecayState {
        if (strategy.interest < 0.3) return DecayState.COLD

        val theirMsgs = conversation.messages.filter { it.sender == Sender.THEM }
        if (theirMsgs.size < 4) {
            return if (strategy.interest < 0.5) DecayState.COOLING else DecayState.WARM
        }

        // Compare the last two of their messages to the previous two: slower + shorter = cooling.
        val recent = theirMsgs.takeLast(2)
        val prior = theirMsgs.subList(theirMsgs.size - 4, theirMsgs.size - 2)
        val recentLen = recent.sumOf { it.text.length }.toDouble() / recent.size
        val priorLen = prior.sumOf { it.text.length }.toDouble() / prior.size
        val gettingShorter = recentLen < 0.6 * priorLen

        val recentGap = gapSeconds(theirMsgs, theirMsgs.size - 1)
        val priorGap = gapSeconds(theirMsgs, theirMsgs.size - 3)
        val gettingSlower = recentGap != null && priorGap != null && recentGap > 2.0 * priorGap

        return when {
            gettingShorter && gettingSlower -> DecayState.COLD
            gettingShorter || gettingSlower || strategy.interest < 0.45 -> DecayState.COOLING
            else -> DecayState.WARM
        }
    }

    /** Seconds between THEM message at index i and the message just before it in the full thread. */
    private fun gapSeconds(theirMsgs: List<Message>, i: Int): Double? {
        if (i <= 0 || i >= theirMsgs.size) return null
        return Duration.between(theirMsgs[i - 1].at, theirMsgs[i].at).seconds.toDouble().takeIf { it > 0 }
    }
}
