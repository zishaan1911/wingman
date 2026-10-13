package com.wingman.assist

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Fires scheduled replies at their computed instant, and PERSISTS them so a pending send
 * survives the process/service being killed and restarted.
 *
 * Why not WorkManager? A WorkManager worker can't perform accessibility actions — only the
 * live AccessibilityService can type + tap. That service is already a persistent process the
 * system re-binds after death, so the right fix is to persist the queue and re-arm it in
 * onServiceConnected (see [loadPending]). A send whose time passed while we were dead fires
 * as soon as you're back on that chat.
 */
class SendScheduler(ctx: Context, private val onFire: (ScheduledReply) -> Unit) {

    private val sp = ctx.getSharedPreferences("wingman_sched", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun schedule(reply: ScheduledReply) {
        cancel(reply.conversationId)
        persist(reply)
        arm(reply)
    }

    private fun arm(reply: ScheduledReply) {
        val delayMs = (reply.sendAt.toEpochMilli() - Instant.now().toEpochMilli()).coerceAtLeast(0)
        val future = exec.schedule({
            pending.remove(reply.conversationId)
            unpersist(reply.conversationId)
            onFire(reply)
        }, delayMs, TimeUnit.MILLISECONDS)
        pending[reply.conversationId] = future
    }

    fun cancel(conversationId: String) {
        pending.remove(conversationId)?.cancel(false)
        unpersist(conversationId)
    }

    fun cancelAll() {
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        sp.edit().clear().apply()
    }

    /** Reload persisted sends (call from onServiceConnected) and re-arm them. */
    fun loadPending(): List<ScheduledReply> {
        val out = ArrayList<ScheduledReply>()
        for ((id, raw) in sp.all) {
            if (raw !is String) continue
            val parsed = runCatching { fromJson(JSONObject(raw)) }.getOrNull()
            if (parsed != null) { out.add(parsed); arm(parsed) } else sp.edit().remove(id).apply()
        }
        return out
    }

    // --- persistence ---
    private fun persist(r: ScheduledReply) = sp.edit().putString(r.conversationId, toJson(r).toString()).apply()
    private fun unpersist(id: String) = sp.edit().remove(id).apply()

    private fun toJson(r: ScheduledReply) = JSONObject()
        .put("id", r.conversationId).put("name", r.matchName).put("text", r.text)
        .put("at", r.sendAt.toEpochMilli()).put("esc", r.escalation.name)
        .put("approve", r.needsApproval).put("tone", r.tone.name).put("opener", r.isOpener)

    private fun fromJson(o: JSONObject) = ScheduledReply(
        conversationId = o.getString("id"),
        matchName = o.optString("name", "match"),
        text = o.getString("text"),
        sendAt = Instant.ofEpochMilli(o.getLong("at")),
        escalation = runCatching { EscalationAction.valueOf(o.optString("esc", "NONE")) }.getOrDefault(EscalationAction.NONE),
        needsApproval = o.optBoolean("approve", false),
        tone = runCatching { Tone.valueOf(o.optString("tone", "GENUINE")) }.getOrDefault(Tone.GENUINE),
        isOpener = o.optBoolean("opener", false),
    )
}
