package com.wingman.assist

import android.content.Context

/**
 * Remembers the resource IDs the auto-detector discovers, per app + role (send button,
 * match-name header, input box). Once captured, lookups try the cached ID first — fast and
 * stable — and fall back to the heuristic only if it's gone (e.g. after an app update), at
 * which point a fresh ID is re-captured. This is the "automatic capture" of selectors.
 */
class SelectorStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("wingman_selectors", Context.MODE_PRIVATE)

    fun get(app: App, role: String): String? = sp.getString("${app.name}:$role", null)

    fun put(app: App, role: String, id: String?) {
        if (id.isNullOrBlank()) return
        if (get(app, role) == id) return
        sp.edit().putString("${app.name}:$role", id).apply()
    }
}
