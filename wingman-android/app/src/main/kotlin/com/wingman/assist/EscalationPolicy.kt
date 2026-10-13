package com.wingman.assist

/**
 * Decides whether this reply should carry a "big move" — proposing a date or moving
 * off-app — based on the strategist's read and how much conversation there's been.
 *
 * Rules (tunable from the dev panel via [Settings]):
 *  • If they've signalled wanting to move off-app → MOVE_OFF_APP (respect the signal).
 *  • Else if the moment is ripe (readiness high + enough rapport) → PROPOSE_DATE.
 *  • Else → NONE.
 *
 * "Enough rapport" guards against asking someone out after two messages.
 */
class EscalationPolicy(private val settings: Settings) {

    fun decide(conversation: Conversation, strategy: MatchStrategy): EscalationAction {
        if (!settings.steerTowardDate) return EscalationAction.NONE

        if (strategy.wantsOffApp) return EscalationAction.MOVE_OFF_APP

        val enoughRapport = conversation.depth >= settings.minDepthToAskOut &&
            strategy.interest >= settings.minInterestToAskOut
        val ripe = strategy.readiness >= settings.readinessToAskOut

        return if (enoughRapport && ripe) EscalationAction.PROPOSE_DATE else EscalationAction.NONE
    }

    /** A "big move" is anything you might want to approve before it sends. */
    fun isBigMove(action: EscalationAction): Boolean = action != EscalationAction.NONE
}
