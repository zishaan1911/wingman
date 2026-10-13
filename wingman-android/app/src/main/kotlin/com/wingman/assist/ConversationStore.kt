package com.wingman.assist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Store of conversations, keyed by a stable id, PERSISTED to disk as JSON so the timing,
 * mirroring, and decay models keep their history across app/service restarts (otherwise
 * every restart would reset cadence learning and re-open settled threads).
 *
 * Kept deliberately lightweight (SharedPreferences + JSON) to match the rest of the app and
 * avoid an ORM dependency; the data is small and append-only. Easy to swap for Room later if
 * it grows. Strategy is transient (recomputed), so it isn't persisted.
 */
class ConversationStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("wingman_convos", Context.MODE_PRIVATE)
    private val byId = LinkedHashMap<String, Conversation>()

    init { load() }

    fun get(id: String): Conversation? = byId[id]
    fun all(): List<Conversation> = byId.values.toList()

    fun ingest(
        id: String,
        app: App,
        matchName: String,
        visibleMessages: List<Message>,
        observedAt: Instant = Instant.now(),
    ): Conversation {
        val convo = byId.getOrPut(id) { Conversation(id, app, matchName) }
        var changed = false
        for (incoming in visibleMessages) {
            if (!alreadyHave(convo, incoming)) { convo.messages.add(incoming); changed = true }
        }
        if (changed) persist(convo)
        return convo
    }

    /** Persist everything still buffered (call on service shutdown). */
    fun flush() { byId.values.forEach { save(it) } }

    private fun alreadyHave(convo: Conversation, m: Message): Boolean {
        val tail = convo.messages.takeLast(12)
        return tail.any { it.sender == m.sender && it.text == m.text }
    }

    private fun persist(convo: Conversation) = save(convo)

    // --- serialization ---
    private fun load() {
        val ids = sp.getStringSet("ids", emptySet()) ?: emptySet()
        for (id in ids) {
            val raw = sp.getString("c:$id", null) ?: continue
            runCatching { fromJson(JSONObject(raw)) }.getOrNull()?.let { byId[it.id] = it }
        }
    }

    private fun save(convo: Conversation) {
        val ids = (sp.getStringSet("ids", emptySet()) ?: emptySet()).toMutableSet().apply { add(convo.id) }
        sp.edit()
            .putStringSet("ids", ids)
            .putString("c:${convo.id}", toJson(convo).toString())
            .apply()
    }

    private fun toJson(c: Conversation): JSONObject {
        val msgs = JSONArray()
        for (m in c.messages) {
            msgs.put(JSONObject()
                .put("s", m.sender.name)
                .put("t", m.text)
                .put("at", m.at.toEpochMilli()))
        }
        return JSONObject()
            .put("id", c.id).put("app", c.app.name).put("name", c.matchName).put("msgs", msgs)
    }

    private fun fromJson(o: JSONObject): Conversation {
        val c = Conversation(o.getString("id"), App.valueOf(o.getString("app")), o.getString("name"))
        val arr = o.optJSONArray("msgs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            c.messages.add(Message(Sender.valueOf(m.getString("s")), m.getString("t"), Instant.ofEpochMilli(m.getLong("at"))))
        }
        return c
    }
}
