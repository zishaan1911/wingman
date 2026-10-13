package com.wingman.assist

import android.content.Context
import org.json.JSONArray

/**
 * Durable per-match memory: the facts someone mentioned (job, pet, trip, exam, plans),
 * accumulated across the conversation and fed back into replies for natural callbacks
 * ("how'd that exam go?"). Callbacks are one of the strongest rapport signals and almost
 * no bot does them.
 *
 * Facts are produced by the strategist (same read, no extra API call) and merged here,
 * deduped, capped. Persisted per conversation id.
 */
class MatchMemory(ctx: Context) {

    private val sp = ctx.getSharedPreferences("wingman_mem", Context.MODE_PRIVATE)
    private val cap = 20

    fun facts(conversationId: String): List<String> {
        val raw = sp.getString(key(conversationId), null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** Merge newly-observed facts in, keeping them unique (case-insensitive) and capped. */
    fun merge(conversationId: String, newFacts: List<String>) {
        if (newFacts.isEmpty()) return
        val existing = facts(conversationId).toMutableList()
        val seen = existing.map { it.lowercase() }.toMutableSet()
        for (f in newFacts) {
            val k = f.lowercase()
            if (k !in seen) { existing.add(f); seen.add(k) }
        }
        val capped = existing.takeLast(cap)
        sp.edit().putString(key(conversationId), JSONArray(capped).toString()).apply()
    }

    private fun key(id: String) = "mem:$id"
}
