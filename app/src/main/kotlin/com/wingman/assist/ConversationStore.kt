package com.wingman.assist

import java.time.Instant

/**
 * In-memory store of conversations, keyed by a stable id. Dedupes messages and
 * preserves observation timestamps (which TimingEngine mines for cadence + active hours).
 *
 * For a real build, back this with Room so history survives restarts — the timing
 * model is only as good as the history it has seen. This skeleton keeps it in memory.
 */
class ConversationStore {

    private val byId = LinkedHashMap<String, Conversation>()

    fun get(id: String): Conversation? = byId[id]

    fun all(): List<Conversation> = byId.values.toList()

    /**
     * Merge a freshly-parsed snapshot of a conversation's visible messages into the
     * store. New messages (not already seen) are appended with [observedAt] as their
     * timestamp. Returns the updated Conversation.
     */
    fun ingest(
        id: String,
        app: App,
        matchName: String,
        visibleMessages: List<Message>,
        observedAt: Instant = Instant.now(),
    ): Conversation {
        val convo = byId.getOrPut(id) { Conversation(id, app, matchName) }
        for (incoming in visibleMessages) {
            if (!alreadyHave(convo, incoming)) {
                // Stamp with observation time if the parser didn't supply one.
                convo.messages.add(incoming.copy(at = incoming.at))
            }
        }
        return convo
    }

    /** Naive dedupe: same sender + same text near the tail. Good enough for chat rows. */
    private fun alreadyHave(convo: Conversation, m: Message): Boolean {
        val tail = convo.messages.takeLast(12)
        return tail.any { it.sender == m.sender && it.text == m.text }
    }
}
